package org.example.service;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ChatModelFactory;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短期记忆服务（会话历史持久化 + 恢复 + 超长摘要压缩）。
 * 让对话在刷新/重启后不丢失，并对超长历史做压缩，控制 token 成本。
 */
@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final ObjectMapper MAPPER = Mappers.JSON;
    private final JdbcTemplate jdbc;
    private final ChatModelFactory chatModelFactory;

    @Autowired
    public MemoryService(JdbcTemplate jdbc, ChatModelFactory chatModelFactory) {
        this.jdbc = jdbc;
        this.chatModelFactory = chatModelFactory;
    }

    private static final RowMapper<ChatMessage> MSG_ROW = (ResultSet rs, int i) -> {
        ChatMessage m = new ChatMessage();
        m.setId(rs.getLong("id"));
        m.setSessionId(rs.getString("session_id"));
        m.setRole(rs.getString("role"));
        m.setContent(rs.getString("content"));
        m.setCreatedAt(rs.getString("created_at") != null ? LocalDateTime.parse(rs.getString("created_at")) : null);
        return m;
    };

    public void ensureSession(String id, String agentName, String title) {
        String now = LocalDateTime.now().toString();
        jdbc.update("INSERT OR IGNORE INTO chat_session(id,agent_name,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                id, agentName, title != null ? title : "会话", now, now);
    }

    public void saveMessage(String sessionId, String role, String content) {
        String now = LocalDateTime.now().toString();
        jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES(?,?,?,?)",
                sessionId, role, content, now);
        jdbc.update("UPDATE chat_session SET agent_name=COALESCE(agent_name,'chat'), updated_at=? WHERE id=?",
                now, sessionId);
    }

    /** 按时间正序返回会话全部消息（调用方按需取窗口尾部） */
    public List<ChatMessage> loadHistory(String sessionId) {
        return jdbc.query("SELECT * FROM chat_message WHERE session_id=? ORDER BY created_at ASC", MSG_ROW, sessionId);
    }

    /** 加载会话全部消息（role/content 正序），供前端「切换会话」恢复对话气泡 */
    public List<Map<String, Object>> loadMessages(String sessionId) {
        return loadHistory(sessionId).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            return map;
        }).toList();
    }

    /**
     * 按关键词召回当前会话的历史消息（供 `recallHistory` 工具使用）。
     *
     * <p>这是"灰区/超窗内容"的补偿通路：窗口里看不到的消息，模型可以主动来查。
     * 目前是「全量载入 + 内存打分」，会话特别长时应改成 SQL 预过滤或 FTS5。</p>
     */
    public List<ChatMessage> searchMessages(String sessionId, String query, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.List.of();
        }
        return HistoryRecallMatcher.topMatches(loadHistory(sessionId), query, limit);
    }

    public ChatSession getSession(String sessionId) {
        return jdbc.query("SELECT * FROM chat_session WHERE id=?", (rs, i) -> {
            ChatSession s = new ChatSession();
            s.setId(rs.getString("id"));
            s.setAgentName(rs.getString("agent_name"));
            s.setTitle(rs.getString("title"));
            return s;
        }, sessionId).stream().findFirst().orElse(null);
    }

    /** 列出全部会话（按最近活跃时间倒序），供前端历史会话面板恢复 */
    public List<Map<String, Object>> listSessions() {
        return jdbc.query(
                "SELECT id, agent_name, title, created_at, updated_at FROM chat_session ORDER BY updated_at DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("agentName", rs.getString("agent_name"));
                    m.put("title", rs.getString("title"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("updatedAt", rs.getString("updated_at"));
                    return m;
                });
    }

    /** 超长历史摘要压缩：把多轮对话压成一段摘要，供保留关键上下文 */
    public String summarize(List<ChatMessage> messages) {
        // 至少一对（user+assistant）才值得压缩：配合「移出即压缩」（trigger=2），
        // 灰区不会因为消息太少而被静默跳过
        if (messages == null || messages.size() < 2) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        try {
            String summary = chatModelFactory.complete(
                    "请把以下对话压缩成一段简洁的中文摘要，保留关键事实、用户目标与未决问题，不要复述客套话。",
                    sb.toString(), 0.2, 800);
            return summary;
        } catch (Exception e) {
            logger.warn("摘要压缩失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 加载会话上下文，超长时把旧消息压缩成一段摘要 + 保留最近 N 轮。
     * 返回的每条消息形如 {role, content}，第一条可能是 {role:"system", content:"[历史摘要]..."}。
     * @param keepPairs 保留最近多少对（user+assistant）作为全文
     */
    public List<Map<String, String>> loadSummarized(String sessionId, int keepPairs) {
        List<ChatMessage> all = loadHistory(sessionId);
        List<Map<String, String>> out = new java.util.ArrayList<>();
        if (all.isEmpty()) return out;

        int keep = keepPairs * 2;
        if (all.size() > keep) {
            List<ChatMessage> old = all.subList(0, all.size() - keep);
            String summary = summarize(old);
            if (summary != null && !summary.isBlank()) {
                Map<String, String> sys = new LinkedHashMap<>();
                sys.put("role", "system");
                sys.put("content", "[历史摘要] " + summary);
                out.add(sys);
            }
            all = new java.util.ArrayList<>(all.subList(all.size() - keep, all.size()));
        }
        for (ChatMessage m : all) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            out.add(map);
        }
        return out;
    }
}
