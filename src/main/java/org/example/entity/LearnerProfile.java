package org.example.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习者画像（长期个性化记忆：科目、考试日期、学习风格、薄弱科目等，单例行 id=1）。
 */
@Data
public class LearnerProfile {
    private Long id;
    private String name;
    /** JSON 数组：关注的科目列表 */
    private String subjects;
    private String examDate;
    private String learningStyle;
    private String goals;
    /** JSON 数组：历史薄弱科目 */
    private String weakSubjects;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
