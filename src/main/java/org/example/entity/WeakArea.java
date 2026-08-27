package org.example.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 薄弱点（由测验错题 + 打卡缺口推导，供弱项热力图与加练建议）。
 */
@Data
public class WeakArea {
    private Long id;
    private Long planId;
    private String subject;
    private String chapter;
    private String reason;
    /** 1~5，越大越薄弱 */
    private Integer severity;
    private String suggestedAction;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
