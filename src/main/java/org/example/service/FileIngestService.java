package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.example.client.MilvusClientFactory;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.entity.KnowledgeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 文件摄取管线：抽取 → 分片 → 向量化 → 写入 Milvus（带 source_type/repo/file_sha）→ 落库。
 * 支持「按 _source 去重重建」：再次摄取同名文件会先删旧向量再写新向量。
 */
@Service
public class FileIngestService {

    private static final Logger logger = LoggerFactory.getLogger(FileIngestService.class);
    private static final Gson GSON = new Gson();
    private static final int EMBED_BATCH = 10; // DashScope Embedding 单次 batch 上限是 10，超过会 400 InvalidParameter
    /** Milvus content 字段 VARCHAR(8192) 按字节计；兜底截断到安全字节数，防止意外超限插入失败 */
    private static final int CONTENT_MAX_BYTES = 8000;
    private static final Pattern UNSAFE_SOURCE = Pattern.compile("[^A-Za-z0-9._/-]");

    @Autowired(required = false)  // Milvus 不可用时为 null，ingest 方法内部已降级跳过向量写入
    private MilvusServiceClient milvusClient;
    @Autowired
    private MilvusClientFactory milvusClientFactory;
    @Autowired
    private VectorEmbeddingService embeddingService;
    @Autowired
    private DocumentChunkService chunkService;
    @Autowired
    private GithubService githubService;
    @Autowired
    private TextExtractor textExtractor;
    @Autowired
    private JdbcTemplate jdbc;

    @Value("${github.repo-url:}")
    private String repoUrl;

    /** 摄取单个上传文件：推 GitHub（best-effort 真源）+ 向量化 + 落库 */
    public IngestResult ingest(MultipartFile file, String subPath) {
        IngestResult result = new IngestResult();
        boolean githubOk = true;
        try {
            String original = file.getOriginalFilename();
            if (original == null || original.isEmpty()) {
                throw new IllegalArgumentException("文件名不能为空");
            }
            byte[] data = file.getBytes();
            String repoPath = buildRepoPath(original, subPath);
            String sha = githubService.sha256(data);

            // 0. 同内容文件已摄取过 → 跳过（不推 GitHub、不抽取、不嵌入，防止重试/重复上传白烧配额）
            if (alreadyIngested(sha)) {
                logger.info("文件内容已存在（sha={}），跳过重复摄取: {}", sha, original);
                result.fileName = original;
                result.repoPath = repoPath;
                result.chunks = 0;
                result.fileSha = sha;
                result.status = "EXISTS";
                result.partial = false;
                result.message = "文件内容已存在，跳过重复摄取（未消耗嵌入配额）";
                return result;
            }

            // 1. 推送到 GitHub 真源（best-effort：pushFile 内部已降级，但兜底再 catch 一次）
            try {
                githubService.pushFile(data, repoPath, "add knowledge: " + original);
            } catch (Exception ge) {
                githubOk = false;
                logger.warn("GitHub 真源推送降级（其余链路继续）: {}", ge.getMessage());
            }

            // 2. 抽取文本
            TextExtractor.ExtractResult er = textExtractor.extract(data, original);
            if (er.text == null || er.text.isBlank()) {
                throw new RuntimeException("文本抽取为空（文件可能是扫描版图片/加密 PDF，暂不支持）");
            }

            // 3. 删除旧向量（按 _source 去重重建）—— 降级模式下跳过
            String safeSource = safeSource(repoPath);
            if (milvusClient != null) {
                deleteBySource(safeSource);
            }

            // 4. 分片
            List<DocumentChunk> chunks = chunkService.chunkDocument(er.text, repoPath);
            if (chunks.isEmpty()) {
                throw new RuntimeException("分片为空");
            }

            // 5. 批量向量化 + 写入 Milvus（Milvus 不可用时降级跳过）
            int total = chunks.size();
            if (milvusClient != null) {
                for (int start = 0; start < total; start += EMBED_BATCH) {
                    int end = Math.min(total, start + EMBED_BATCH);
                    List<DocumentChunk> batch = chunks.subList(start, end);
                    List<String> texts = new ArrayList<>();
                    for (DocumentChunk c : batch) texts.add(c.getContent());

                    List<List<Float>> vectors = embeddingService.generateEmbeddings(texts);
                    for (int i = 0; i < batch.size(); i++) {
                        insertChunk(batch.get(i), vectors.get(i), safeSource, original,
                                extension(original), sha, repoUrl, total);
                    }
                    logger.info("已向量化分片 {}/{} : {}", Math.min(end, total), total, repoPath);
                }
            } else {
                logger.warn("Milvus 降级模式：跳过向量写入，仅记录文件元数据。file={}", original);
            }

            // 6. 落库记录
            recordFile(original, repoPath, sha, total, file.getSize(), er.partial ? "PARTIAL" : "READY");

            result.fileName = original;
            result.repoPath = repoPath;
            result.chunks = total;
            result.fileSha = sha;
            result.status = er.partial ? "PARTIAL" : "READY";
            result.partial = er.partial;
            result.message = (milvusClient == null ? "（Milvus 降级：未写入向量库）" : "")
                    + (er.partial ? "文本为尽力抽取（PDF 可能不完整），建议补充可复制文本版本" : "摄取成功")
                    + (githubOk ? "" : "（GitHub 真源推送失败，数据已写入本地；可手动 push）");
            return result;

        } catch (Exception e) {
            logger.error("文件摄取失败: {}", e.getMessage(), e);
            result.status = "FAILED";
            result.message = e.getMessage();
            return result;
        }
    }

    /** 从 GitHub 拉取最新并重建所有文件向量（按 sha 跳过未变更） */
    public SyncResult syncAll() {
        SyncResult sr = new SyncResult();
        try {
            githubService.pull();
            List<String> files = githubService.listFiles();
            sr.total = files.size();
            for (String rel : files) {
                try {
                    byte[] data = githubService.readFile(rel);
                    String sha = githubService.sha256(data);
                    String prevSha = querySha(rel);
                    if (sha.equals(prevSha)) {
                        sr.skipped++;
                        continue;
                    }
                    // 重新摄取：复用 ingest 逻辑（不重复推 GitHub）
                    reingest(rel, data, sha);
                    sr.reindexed++;
                } catch (Exception e) {
                    sr.failed++;
                    sr.errors.add(rel + ": " + e.getMessage());
                    logger.warn("同步单文件失败: {} -> {}", rel, e.getMessage());
                }
            }
            return sr;
        } catch (Exception e) {
            sr.message = e.getMessage();
            return sr;
        }
    }

    // ===================== 内部 =====================

    private void reingest(String repoPath, byte[] data, String sha) {
        String original = Paths.get(repoPath).getFileName().toString();
        TextExtractor.ExtractResult er = textExtractor.extract(data, original);
        if (er.text == null || er.text.isBlank()) return;
        String safeSource = safeSource(repoPath);
        if (milvusClient != null) {
            deleteBySource(safeSource);
        }
        List<DocumentChunk> chunks = chunkService.chunkDocument(er.text, repoPath);
        int total = chunks.size();
        if (milvusClient != null) {
            for (int start = 0; start < total; start += EMBED_BATCH) {
                int end = Math.min(total, start + EMBED_BATCH);
                List<DocumentChunk> batch = chunks.subList(start, end);
                List<String> texts = new ArrayList<>();
                for (DocumentChunk c : batch) texts.add(c.getContent());
                List<List<Float>> vectors = embeddingService.generateEmbeddings(texts);
                for (int i = 0; i < batch.size(); i++) {
                    insertChunk(batch.get(i), vectors.get(i), safeSource, original,
                            extension(original), sha, repoUrl, total);
                }
            }
        }
        recordFile(original, repoPath, sha, total, (long) data.length, er.partial ? "PARTIAL" : "READY");
    }

    private void insertChunk(DocumentChunk chunk, List<Float> vector, String source,
                             String fileName, String sourceType, String sha, String repo, int total) {
        loadCollection();
        String id = UUID.nameUUIDFromBytes((source + "_" + chunk.getChunkIndex()).getBytes()).toString();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", source);
        metadata.put("_file_name", fileName);
        metadata.put("source_type", sourceType);
        metadata.put("repo", repo);
        metadata.put("file_sha", sha);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", total);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        JsonObject metaJson = GSON.toJsonTree(metadata).getAsJsonObject();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(fitMilvusContent(chunk.getContent()))));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metaJson)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                .withFields(fields)
                .build();

        R<MutationResult> resp = milvusClient.insert(insertParam);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("插入向量失败: " + resp.getMessage());
        }
    }

    /** Milvus VARCHAR 按 UTF-8 字节计数：超 8000 字节的 content 按合法字符边界截断，防止 insert 超限失败 */
    private static String fitMilvusContent(String content) {
        if (content == null || content.isEmpty()) return content;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= CONTENT_MAX_BYTES) return content;
        int end = CONTENT_MAX_BYTES;
        // 回退到合法字符边界：跳过当前多字节字符的续字节，再排除不完整的首字节
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        if ((bytes[end] & 0x80) != 0) end--; // 非 ASCII 且非续字节 → 多字节字符首字节，该字符不完整，一并去掉
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** 删除知识库文件：删 Milvus 向量 + 删 SQLite 记录（GitHub 真源备份保留）。返回是否找到并删除。 */
    public boolean deleteFile(Long id) {
        String repoPath = jdbc.query(
                "SELECT repo_path FROM knowledge_files WHERE id=?",
                (rs, rowNum) -> rs.getString("repo_path"), id)
                .stream().findFirst().orElse(null);
        if (repoPath == null) return false;
        deleteBySource(safeSource(repoPath));
        jdbc.update("DELETE FROM knowledge_files WHERE id=?", id);
        return true;
    }

    private void deleteBySource(String source) {
        try {
            if (milvusClient == null) return;
            loadCollection();
            String expr = String.format("metadata[\"_source\"] == \"%s\"", source);
            DeleteParam dp = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                    .withExpr(expr)
                    .build();
            R<MutationResult> resp = milvusClient.delete(dp);
            if (resp.getStatus() != 0) {
                logger.warn("删除旧向量警告: {}", resp.getMessage());
            } else {
                logger.info("已删除旧向量(_source={}), 条数={}", source, resp.getData().getDeleteCnt());
            }
        } catch (Exception e) {
            logger.warn("删除旧向量失败（可能首次摄取）: {}", e.getMessage());
        }
    }

    private void loadCollection() {
        if (milvusClient == null) return;
        R<RpcStatus> r = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME).build());
        if (r.getStatus() != 0 && r.getStatus() != 65535) {
            throw new RuntimeException("加载 collection 失败: " + r.getMessage());
        }
    }

    private void recordFile(String fileName, String repoPath, String sha, int chunks,
                            long size, String status) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Integer existing = jdbc.query(
                "SELECT id FROM knowledge_files WHERE repo_path=?",
                (rs, rowNum) -> rs.getInt("id"), repoPath)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            jdbc.update("UPDATE knowledge_files SET file_sha=?, chunks=?, size_bytes=?, status=?, updated_at=? WHERE repo_path=?",
                    sha, chunks, size, status, now, repoPath);
        } else {
            jdbc.update("INSERT INTO knowledge_files(file_name, repo, repo_path, file_sha, chunks, size_bytes, status, created_at, updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?)",
                    fileName, repoUrl, repoPath, sha, chunks, size, status, now, now);
        }
    }

    private String querySha(String repoPath) {
        try {
            return jdbc.queryForObject(
                    "SELECT file_sha FROM knowledge_files WHERE repo_path=?", String.class, repoPath);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同内容文件（按内容 sha）是否已在知识库中（任意状态） */
    private boolean alreadyIngested(String sha) {
        if (sha == null || sha.isEmpty()) return false;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_files WHERE file_sha=?", Integer.class, sha);
            return count != null && count > 0;
        } catch (Exception e) {
            logger.warn("查询已摄取文件失败: {}", e.getMessage());
            return false;
        }
    }

    /** 构建仓库内相对路径（防穿越，统一正斜杠） */
    private String buildRepoPath(String original, String subPath) {
        String name = original.replace('\\', '/');
        if (subPath != null && !subPath.isBlank()) {
            String s = subPath.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
            if (s.contains("..")) throw new IllegalArgumentException("非法的子目录路径");
            name = s + "/" + name;
        }
        return name;
    }

    private String safeSource(String repoPath) {
        String s = repoPath.replace('\\', '/');
        return UNSAFE_SOURCE.matcher(s).replaceAll("_");
    }

    private String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "unknown" : filename.substring(idx + 1).toLowerCase();
    }

    // ===================== 结果 DTO =====================

    public static class IngestResult {
        public String fileName;
        public String repoPath;
        public int chunks;
        public String fileSha;
        public String status;
        public boolean partial;
        public String message;
    }

    public static class SyncResult {
        public int total;
        public int reindexed;
        public int skipped;
        public int failed;
        public String message;
        public List<String> errors = new ArrayList<>();
    }
}
