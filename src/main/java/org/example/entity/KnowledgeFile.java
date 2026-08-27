package org.example.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件记录（SQLite：knowledge_files）
 * 记录已摄取的文件元数据，用于列表展示、按 sha 去重/变更检测、同步重建。
 */
@Data
public class KnowledgeFile {
    private Long id;
    private String fileName;     // 原始文件名
    private String repo;         // 仓库标识（repo-url）
    private String repoPath;     // 仓库内相对路径（= Milvus metadata._source）
    private String fileSha;      // 内容 SHA-256
    private Integer chunks;      // 切分后的分片数（= 向量条数）
    private Long sizeBytes;
    private String status;       // READY / FAILED / PARTIAL
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
