package org.example.controller;

import org.example.entity.KnowledgeFile;
import org.example.service.FileIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 知识库接口：上传（推送 GitHub 真源 + 向量化）、同步重建、列表。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeController.class);

    @Autowired
    private FileIngestService fileIngestService;
    @Autowired
    private JdbcTemplate jdbc;

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

    /** 从 GitHub 拉取最新并重建向量（按 sha 跳过未变更文件） */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<FileIngestService.SyncResult>> sync() {
        try {
            FileIngestService.SyncResult result = fileIngestService.syncAll();
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("知识库同步异常", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 删除知识库文件：删本地 Milvus 向量 + SQLite 记录（GitHub 真源备份保留） */
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
                            "FROM knowledge_files ORDER BY updated_at DESC", ROW_MAPPER);
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
