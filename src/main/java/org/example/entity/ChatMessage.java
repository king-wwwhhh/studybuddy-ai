package org.example.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息（会话内的单条 user/assistant 内容，构成短期记忆上下文）。
 */
@Data
public class ChatMessage {
    private Long id;
    private String sessionId;
    /** user / assistant / system */
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
