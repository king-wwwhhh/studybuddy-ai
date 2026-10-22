package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
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
import java.util.function.Supplier;
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

    /** 旧实现的 source 规范化规则（非 ASCII 全替换成 `_`）；仅用于删除时兼容存量向量 */
    private static final Pattern LEGACY_UNSAFE_SOURCE = Pattern.compile("[^A-Za-z0-9._/-]");

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

    /** 写入后读回校验的抽样条数：0 或负数表示关闭校验 */
    @Value("${rag.verify-sample-size:20}")
    private int verifySampleSize = 20;

    /** 读回校验的总尝试次数（含首次）；用于区分"瞬时不可见"与真失败 */
    @Value("${rag.verify-attempts:3}")
    private int verifyAttempts = VerifyRetryPolicy.DEFAULT_ATTEMPTS;

    /** 复查退避基数（毫秒），按指数增长并封顶 2s */
    @Value("${rag.verify-retry-base-ms:200}")
    private long verifyRetryBaseMs = VerifyRetryPolicy.DEFAULT_BASE_MILLIS;

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

            // 3. 旧版本信息（写完之后用它精确清理）；注意：不再「先删旧向量」
            String safeSource = safeSource(repoPath);
            Previous previous = queryPrevious(repoPath);

            // 4. 分片
            List<DocumentChunk> chunks = chunkService.chunkDocument(er.text, repoPath);
            if (chunks.isEmpty()) {
                throw new RuntimeException("分片为空");
            }

            // 5. 批量向量化 + 写入 Milvus（先写新版本，旧向量此时仍然可用）
            int total = chunks.size();
            if (milvusClient != null) {
                List<Long> insertCounts = new ArrayList<>();
                for (int start = 0; start < total; start += EMBED_BATCH) {
                    int end = Math.min(total, start + EMBED_BATCH);
                    List<DocumentChunk> batch = chunks.subList(start, end);
                    List<String> texts = new ArrayList<>();
                    for (DocumentChunk c : batch) texts.add(c.getContent());

                    List<List<Float>> vectors = embeddingService.generateEmbeddings(texts);
                    for (int i = 0; i < batch.size(); i++) {
                        insertCounts.add(insertChunk(batch.get(i), vectors.get(i), safeSource, original,
                                extension(original), sha, repoUrl, total));
                    }
                    logger.info("已向量化分片 {}/{} : {}", Math.min(end, total), total, repoPath);
                }
                // 5.1 写入完整性校验：不完整就抛错，此时旧向量还在，检索不受影响
                if (!VectorWritePlan.writeComplete(insertCounts, total)) {
                    throw new RuntimeException("向量写入不完整（期望 " + total + " 条），已保留旧版本向量");
                }
                // 5.2 读回校验：抽样 query 确认新数据真的可检索；不通过就保留旧版本（可回退）
                List<String> writtenIds = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    writtenIds.add(VectorWritePlan.chunkId(safeSource, sha, i));
                }
                if (verifyReadBack(writtenIds, "写入")) {
                    // 5.3 写成功且读回确认之后，才清理旧版本（按确定性主键定点删除，不误删新行）
                    int removed = deletePreviousVersions(repoPath, previous);
                    logger.info("先写后删完成: 写入 {} 条, 清理旧版本 {} 条, file={}", total, removed, repoPath);
                } else {
                    logger.warn("读回校验未通过：保留旧版本向量（数据可回退，可人工重试）: {}", repoPath);
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
        Previous previous = queryPrevious(repoPath);
        List<DocumentChunk> chunks = chunkService.chunkDocument(er.text, repoPath);
        int total = chunks.size();
        if (milvusClient != null) {
            List<Long> insertCounts = new ArrayList<>();
            for (int start = 0; start < total; start += EMBED_BATCH) {
                int end = Math.min(total, start + EMBED_BATCH);
                List<DocumentChunk> batch = chunks.subList(start, end);
                List<String> texts = new ArrayList<>();
                for (DocumentChunk c : batch) texts.add(c.getContent());
                List<List<Float>> vectors = embeddingService.generateEmbeddings(texts);
                for (int i = 0; i < batch.size(); i++) {
                    insertCounts.add(insertChunk(batch.get(i), vectors.get(i), safeSource, original,
                            extension(original), sha, repoUrl, total));
                }
            }
            // 先写后删：写入不完整就保留旧向量（同步路径失败会让整个 sync 记一次失败，但数据不丢）
            if (!VectorWritePlan.writeComplete(insertCounts, total)) {
                throw new RuntimeException("向量写入不完整（期望 " + total + " 条），已保留旧版本向量: " + repoPath);
            }
            List<String> writtenIds = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                writtenIds.add(VectorWritePlan.chunkId(safeSource, sha, i));
            }
            if (verifyReadBack(writtenIds, "同步重摄取")) {
                deletePreviousVersions(repoPath, previous);
            } else {
                logger.warn("读回校验未通过：保留旧版本向量: {}", repoPath);
            }
        }
        recordFile(original, repoPath, sha, total, (long) data.length, er.partial ? "PARTIAL" : "READY");
    }

    /** 写入单个分片，返回服务端确认的条数（用于「先写后删」的完整性校验） */
    private long insertChunk(DocumentChunk chunk, List<Float> vector, String source,
                             String fileName, String sourceType, String sha, String repo, int total) {
        loadCollection();
        // 主键带内容版本：内容变了就是一批新主键，新旧可共存，写成功后再定点清理旧主键
        String id = VectorWritePlan.chunkId(source, sha, chunk.getChunkIndex());

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
        if (chunk.getHeadingPath() != null && !chunk.getHeadingPath().isBlank()) {
            metadata.put("heading_path", chunk.getHeadingPath()); // 章节路径：检索命中后可说明「出自哪一章」
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
        return resp.getData() == null ? 0L : resp.getData().getInsertCnt();
    }

    /**
     * 读回校验：抽样 query 确认写入的分片真的可检索。
     *
     * <p>不可用即跳过（返回 true）：Milvus 客户端为 null、query RPC 报错、服务端不支持本次查询
     * —— 都不能因为"校验做不了"就阻塞写入。抽样条数 ≤ 0 时同样跳过。</p>
     */
    private boolean verifyReadBack(List<String> ids, String stage) {
        if (milvusClient == null || ids == null || ids.isEmpty() || verifySampleSize <= 0) {
            return true;
        }
        List<String> sample = WriteVerification.sample(ids, verifySampleSize);
        return verifyWithRetry(() -> checkWrittenOnce(sample, stage), stage);
    }

    /** 单次写入校验：null 表示校验不可用（调用方直接放行） */
    private Boolean checkWrittenOnce(List<String> sample, String stage) {
        OptionalInt actual = queryVisibleCount(sample);
        if (actual.isEmpty()) {
            return null;
        }
        boolean ok = WriteVerification.verified(sample.size(), actual.getAsInt());
        if (!ok) {
            logger.info("{}", WriteVerification.describe(stage, sample.size(), actual.getAsInt()));
        }
        return ok;
    }

    /** 统计给定主键当前可见条数；不可用（客户端缺失 / RPC 报错 / 不支持该查询）返回 empty */
    private OptionalInt queryVisibleCount(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return OptionalInt.empty();
        }
        return queryCountByExpr(VectorWritePlan.deleteExprByIds(ids));
    }

    /** 按表达式统计可见条数；不可用时返回 empty（调用方一律按"跳过校验"处理） */
    private OptionalInt queryCountByExpr(String expr) {
        if (milvusClient == null || expr == null || expr.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            loadCollection();
            R<QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .withOutFields(List.of("id"))
                    .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                    .build());
            if (resp.getStatus() != 0 || resp.getData() == null) {
                logger.warn("读回查询不可用: {}", resp.getMessage());
                return OptionalInt.empty();
            }
            return OptionalInt.of(new QueryResultsWrapper(resp.getData()).getRowRecords().size());
        } catch (Exception e) {
            logger.warn("读回查询异常: {}", e.getMessage());
            return OptionalInt.empty();
        }
    }

    /** 用户删文件后校验：按 source 统计，应该一条都查不到（同样走有限次延迟复查） */
    private void verifyNoRowsBySource(String source, String stage) {
        if (milvusClient == null || verifySampleSize <= 0) {
            return;
        }
        String escaped = source == null ? "" : source.replace("\\", "\\\\").replace("\"", "\\\"");
        String expr = "metadata[\"_source\"] == \"" + escaped + "\"";
        verifyWithRetry(() -> {
            OptionalInt remaining = queryCountByExpr(expr);
            return remaining.isEmpty() ? null : WriteVerification.deletionVerified(remaining.getAsInt());
        }, stage);
    }

    /**
     * 删除后校验：抽样确认这些主键已经查不到。
     * 仍可见只告警**不回滚**（删除无法撤销），提示「墓碑尚未 compact」或「该主键被重新写入」两种可能。
     */
    private void verifyDeletion(List<String> deletedIds, String stage) {
        if (milvusClient == null || verifySampleSize <= 0 || deletedIds == null || deletedIds.isEmpty()) {
            return;
        }
        List<String> sample = WriteVerification.sample(deletedIds, verifySampleSize);
        verifyWithRetry(() -> {
            OptionalInt remaining = queryVisibleCount(sample);
            return remaining.isEmpty() ? null : WriteVerification.deletionVerified(remaining.getAsInt());
        }, stage);
    }

    /**
     * 有限次延迟复查：第 1 次立即执行，之后按指数退避重试。
     *
     * <p>返回 true 表示「通过」或「校验不可用（不阻塞主流程）」；返回 false 才是"重试用尽仍不通过"。
     * 这样能把「墓碑尚未 compact / 刚写入还没可见」这类瞬时状态与真正的问题区分开。</p>
     */
    private boolean verifyWithRetry(Supplier<Boolean> check, String stage) {
        List<Long> delays = VerifyRetryPolicy.delaySchedule(verifyAttempts, verifyRetryBaseMs,
                VerifyRetryPolicy.DEFAULT_MAX_MILLIS);
        int attempts = delays.size();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleepMillis(delays.get(attempt - 1));
            }
            Boolean result = check.get();
            if (result == null) {
                logger.warn("{} 校验不可用（跳过，不阻塞）", stage);
                return true;
            }
            if (result) {
                logger.info("{}", VerifyRetryPolicy.describe(stage, attempt, true));
                return true;
            }
            if (!VerifyRetryPolicy.shouldRetry(attempt, false, attempts)) {
                logger.warn("{}", VerifyRetryPolicy.describe(stage, attempt, false));
                return false;
            }
            logger.info("{} 第 {}/{} 次未通过，{}ms 后复查", stage, attempt, attempts, delays.get(attempt));
        }
        return false;
    }

    private static void sleepMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 旧版本记录：清理旧向量需要知道上一版的分片数与内容 sha */
    private static final class Previous {
        final String sha;
        final int chunks;

        Previous(String sha, int chunks) {
            this.sha = sha;
            this.chunks = chunks;
        }
    }

    /** 读上一版本记录；没有记录（首次摄取）返回 null */
    private Previous queryPrevious(String repoPath) {
        try {
            return jdbc.query(
                    "SELECT file_sha, chunks FROM knowledge_files WHERE repo_path=?",
                    rs -> rs.next() ? new Previous(rs.getString("file_sha"), rs.getInt("chunks")) : null,
                    repoPath);
        } catch (Exception e) {
            logger.warn("查询旧版本记录失败（本次跳过旧版本清理，不影响新数据写入）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清理上一版本留在 Milvus 里的向量（新版本已写入成功后才调用）。
     *
     * <p>按确定性主键定点删除，同时覆盖「历史无版本主键」和「版本化主键」两套方案，
     * 以及历史 `safeSource` 归一化产生的 old source，避免旧行残留导致同一文件检索出两份。</p>
     *
     * @return 删除条数（失败返回 0，不影响已写入的新数据）
     */
    private int deletePreviousVersions(String repoPath, Previous previous) {
        if (previous == null || previous.chunks <= 0) {
            return 0;
        }
        String currentSource = safeSource(repoPath);
        String legacySource = legacySource(repoPath);
        List<String> ids = new ArrayList<>(VectorWritePlan.previousIds(currentSource, previous.sha, previous.chunks));
        if (!legacySource.equals(currentSource)) {
            ids.addAll(VectorWritePlan.previousIds(legacySource, previous.sha, previous.chunks));
        }
        int removed = 0;
        for (List<String> batch : VectorWritePlan.batchIds(ids, VectorWritePlan.DEFAULT_DELETE_BATCH)) {
            String batchExpr = VectorWritePlan.deleteExprByIds(batch);
            if (batchExpr == null) {
                continue;
            }
            try {
                loadCollection();
                R<MutationResult> resp = milvusClient.delete(DeleteParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                        .withExpr(batchExpr)
                        .build());
                if (resp.getStatus() != 0) {
                    logger.warn("清理旧版本向量失败（该批 {} 个主键，剩余批次继续）: {}",
                            batch.size(), resp.getMessage());
                    continue;
                }
                long cnt = resp.getData() == null ? 0L : resp.getData().getDeleteCnt();
                removed += (int) cnt;
            } catch (Exception e) {
                logger.warn("清理旧版本向量异常（该批 {} 个主键，新数据已写入）: {}", batch.size(), e.getMessage());
            }
        }
        logger.info("已清理旧版本向量 {} 条（候选主键 {} 个，分 {} 批）",
                removed, ids.size(), VectorWritePlan.batchIds(ids, VectorWritePlan.DEFAULT_DELETE_BATCH).size());
        // 删除侧读回：抽样确认这些主键已经查不到（仍可见只告警，不回滚）
        verifyDeletion(ids, "旧版本清理");
        return removed;
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
        deleteVectorsOf(repoPath, safeSource(repoPath));
        // 删除侧读回：确认该 source 下确实查不到分片（仍可见只告警，删除无法回滚）
        verifyNoRowsBySource(safeSource(repoPath), "删文件");
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

    /**
     * 规范化 source：只处理会破坏 JSON 元数据或 Milvus delete 表达式的字符。
     *
     * <p>关键点：<b>中文等非 ASCII 必须原样保留</b>。历史实现用 `[^A-Za-z0-9._/-]` 白名单把所有
     * 非 ASCII 字符替换成 `_`，导致「第一章.pdf」和「第二章.pdf」都归一成 `___.pdf` —— 同一个 source，
     * 于是上传第二个文件时会按 source 删掉第一个文件的全部向量，而且不报错。</p>
     */
    private String safeSource(String repoPath) {
        return repoPath.replace('\\', '/')
                .replace('"', '\'')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
    }

    /** 旧实现产生的 source（历史数据用），只在删除时作为兼容取值 */
    private String legacySource(String repoPath) {
        return LEGACY_UNSAFE_SOURCE.matcher(repoPath.replace('\\', '/')).replaceAll("_");
    }

    /**
     * 删除某文件的全部旧向量：当前 source + 历史 source。
     *
     * <p>为什么要删两份：旧实现把中文名归一成 `___.pdf` 之类，改逻辑后只按新 source 删，
     * 存量向量就成了孤儿（既删不掉、又会和新向量同时被检索到）。</p>
     */
    private void deleteVectorsOf(String repoPath, String currentSource) {
        deleteBySource(currentSource);
        String legacy = legacySource(repoPath);
        if (!legacy.equals(currentSource)) {
            deleteBySource(legacy);
        }
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
