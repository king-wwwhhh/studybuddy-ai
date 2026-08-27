package org.example.entity;

import lombok.Data;

import java.time.LocalDate;

/**
 * 计划任务（计划拆出的阶段/周/日任务，可被打卡标记完成）。
 */
@Data
public class PlanTask {
    private Long id;
    private Long planId;
    private String phase;
    private Integer week;
    private Integer day;
    private String title;
    private String description;
    private Double expectedHours;
    /** TODO / DOING / DONE */
    private String status;
    private LocalDate scheduledDate;
    private LocalDate actualDate;
    private String completionNote;
    /** 章节标签，用于出题时按章节分流 */
    private String chapter;
}
