package org.example.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习计划（期末速成的总体计划，含考试日期、科目、总天数）。
 */
@Data
public class StudyPlan {
    private Long id;
    private String title;
    private String subject;
    private LocalDate examDate;
    private Integer totalDays;
    /** PLANNING / ACTIVE / DONE */
    private String status;
    /** 1-5，5 最紧迫 */
    private Integer priority;
    /** 每天学习时长（小时），默认 2.0 */
    private Double hoursPerDay;
    /** 学员补充说明（基础/目标/偏好），给 AI 看 */
    private String notes;
    /** AI 规划时使用的目标原文 */
    private String goalText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
