package org.example.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话（短期记忆持久化：刷新/重启不丢，支持超长摘要压缩）。
 */
@Data
public class ChatSession {
    private String id;
    /** 处理该会话的 Agent 名称，如 planner/study/quiz/coach/chat */
    private String agentName;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
