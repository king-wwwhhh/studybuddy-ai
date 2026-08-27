package org.example.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日打卡（用户填写当天完成情况，供教练 Agent 监督与重排）。
 */
@Data
public class DailyCheckin {
    private Long id;
    private Long planId;
    private LocalDate date;
    /** 当天完成的任务 id 列表（JSON 数组字符串） */
    private String doneTaskIds;
    private String completionText;
    /** 情绪/状态标记，如 GREAT/OK/TIRED */
    private String mood;
    private Double hoursStudied;
    private LocalDateTime createdAt;
}
