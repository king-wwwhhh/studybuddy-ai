package org.example.controller;

import org.example.entity.KnowledgeFile;
import org.example.service.FileIngestService;
import org.example.auth.OwnerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 知识库接口：上传（解析 + 切片 + 向量化）、列表、删除。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeController.class);

    @Autowired
    private FileIngestService fileIngestService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 孤儿向量清理（Milvus 有、数据库已无记录的残留分片） */
    @Autowired
    private org.example.service.OrphanVectorCleaner orphanVectorCleaner;

    /** 上传文件：推送私有仓库（真源）+ 抽取 + 向量化 */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FileIngestService.IngestResult>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        if (file.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("文件不能为空"));
        }
        try {
            FileIngestService.IngestResult result = fileIngestService.ingest(file, path);
            if ("FAILED".equals(result.status)) {
                return ResponseEntity.ok(ApiResponse.error(result.message));
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("知识库上传异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 删除知识库文件：删 Milvus 向量 + SQLite 记录 */
    /** 一键清空当前用户的知识库（只删自己的，不影响他人） */
    @DeleteMapping("/all")
    public ResponseEntity<ApiResponse<Integer>> deleteAll() {
        try {
            int n = fileIngestService.deleteAllForCurrentUser();
            return ResponseEntity.ok(ApiResponse.success(n));
        } catch (Exception e) {
            logger.error("清空知识库异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 清理孤儿向量（Milvus 里有、数据库里已无记录）。
     * dryRun=true 只统计不删，便于先看清影响面。
     */
    @PostMapping("/cleanup-orphans")
    public ResponseEntity<ApiResponse<java.util.List<String>>> cleanupOrphans(
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(value = "sources", required = false) java.util.List<String> sources) {
        try {
            java.util.List<String> target = (sources == null || sources.isEmpty())
                    ? new java.util.ArrayList<>() : sources;
            return ResponseEntity.ok(ApiResponse.success(
                    orphanVectorCleaner.cleanCandidates(target, dryRun)));
        } catch (Exception e) {
            logger.error("孤儿向量清理异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 全量扫描 + 清理孤儿向量（Milvus 有、数据库无的所有 source） */
    @PostMapping("/cleanup-all-orphans")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> cleanupAllOrphans(
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        try {
            java.util.Map<String, Integer> all = orphanVectorCleaner.scanAllSources();
            java.util.Set<String> alive = orphanVectorCleaner.aliveSources();
            java.util.Map<String, Integer> orphans = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : all.entrySet()) {
                String src = e.getKey();
                String base = src.startsWith("uploads/") ? src.substring("uploads/".length()) : src;
                if (!alive.contains(src) && !alive.contains(base)) {
                    orphans.put(src, e.getValue());
                }
            }
            java.util.List<String> removed = new java.util.ArrayList<>();
            if (!dryRun) {
                removed = orphanVectorCleaner.cleanCandidates(orphans.keySet(), false);
            }
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("totalSources", all.size());
            out.put("orphanSources", orphans);
            out.put("reclaimedChunks", orphans.values().stream().mapToInt(Integer::intValue).sum());
            out.put("removed", removed);
            out.put("dryRun", dryRun);
            return ResponseEntity.ok(ApiResponse.success(out));
        } catch (Exception e) {
            logger.error("全量孤儿清理异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Boolean>> delete(@PathVariable Long id) {
        try {
            boolean deleted = fileIngestService.deleteFile(id);
            if (!deleted) {
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }
            return ResponseEntity.ok(ApiResponse.success(true));
        } catch (Exception e) {
            logger.error("知识库删除异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 已摄取文件列表 */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<KnowledgeFile>>> list() {
        try {
            List<KnowledgeFile> rows = jdbc.query(
                    "SELECT id,file_name,repo,repo_path,file_sha,chunks,size_bytes,status,created_at,updated_at " +
                            "FROM knowledge_files WHERE " + OwnerSupport.visibleScope("user_id") +
                            " ORDER BY updated_at DESC", ROW_MAPPER);
            return ResponseEntity.ok(ApiResponse.success(rows));
        } catch (Exception e) {
            logger.error("知识库列表异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private static final RowMapper<KnowledgeFile> ROW_MAPPER = new RowMapper<KnowledgeFile>() {
        @Override
        public KnowledgeFile mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeFile f = new KnowledgeFile();
            f.setId(rs.getLong("id"));
            f.setFileName(rs.getString("file_name"));
            f.setRepo(rs.getString("repo"));
            f.setRepoPath(rs.getString("repo_path"));
            f.setFileSha(rs.getString("file_sha"));
            f.setChunks(rs.getInt("chunks"));
            f.setSizeBytes(rs.getLong("size_bytes"));
            f.setStatus(rs.getString("status"));
            f.setCreatedAt(parse(rs.getString("created_at")));
            f.setUpdatedAt(parse(rs.getString("updated_at")));
            return f;
        }

        private java.time.LocalDateTime parse(String s) {
            if (s == null) return null;
            try {
                return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e) {
                return null;
            }
        }
    };

    /** 统一响应 */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> r = new ApiResponse<>();
            r.code = 200;
            r.message = "success";
            r.data = data;
            return r;
        }

        public static <T> ApiResponse<T> error(String msg) {
            ApiResponse<T> r = new ApiResponse<>();
            r.code = 500;
            r.message = msg;
            r.data = null;
            return r;
        }

        public int getCode() { return code; }
        public void setCode(int c) { this.code = c; }
        public String getMessage() { return message; }
        public void setMessage(String m) { this.message = m; }
        public T getData() { return data; }
        public void setData(T d) { this.data = d; }
    }
}