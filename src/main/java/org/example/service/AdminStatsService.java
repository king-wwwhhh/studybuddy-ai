package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员统计（决策 D4：**只给聚合数字，不给其他用户的对话明细**）。
 *
 * <p>所有 SQL 都是 COUNT/SUM 级别的聚合，不返回 content 字段，避免隐私争议。
 * 统计口径写在这里而不是散落在 Controller，便于论文"数据看板"章节统一说明。</p>
 */
@Service
public class AdminStatsService {

    private static final Logger logger = LoggerFactory.getLogger(AdminStatsService.class);

    private final JdbcTemplate jdbc;

    public AdminStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 首页 4 张卡片 + 细分指标 */
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", count("SELECT COUNT(*) FROM users"));
        data.put("activeUsers7d", count("SELECT COUNT(DISTINCT id) FROM users WHERE last_login_at >= ?",
                LocalDateTime.now().minusDays(7).toString()));
        data.put("sessions", count("SELECT COUNT(*) FROM chat_session"));
        data.put("messages", count("SELECT COUNT(*) FROM chat_message"));
        data.put("knowledgeFiles", count("SELECT COUNT(*) FROM knowledge_files"));
        data.put("knowledgeChunks", count("SELECT COALESCE(SUM(chunks),0) FROM knowledge_files"));
        data.put("plans", count("SELECT COUNT(*) FROM study_plan"));
        data.put("checkins", count("SELECT COUNT(*) FROM daily_checkin"));
        data.put("messagesToday", count("SELECT COUNT(*) FROM chat_message WHERE created_at >= ?",
                LocalDate.now().toString()));
        return data;
    }

    /** 近 N 日趋势：每日消息数、新增用户数（用于折线图） */
    public List<Map<String, Object>> trend(int days) {
        int n = Math.max(1, Math.min(90, days));
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            String from = d.toString();
            String to = d.plusDays(1).toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", from);
            row.put("messages", count("SELECT COUNT(*) FROM chat_message WHERE created_at >= ? AND created_at < ?", from, to));
            row.put("newUsers", count("SELECT COUNT(*) FROM users WHERE created_at >= ? AND created_at < ?", from, to));
            row.put("checkins", count("SELECT COUNT(*) FROM daily_checkin WHERE created_at >= ? AND created_at < ?", from, to));
            out.add(row);
        }
        return out;
    }

    /** 知识库文件状态分布（饼图）：READY / PARTIAL / FAILED 等 */
    public List<Map<String, Object>> knowledgeStatusDistribution() {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT status, COUNT(*) AS cnt FROM knowledge_files GROUP BY status ORDER BY cnt DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("count", rs.getInt("cnt"));
                    return m;
                });
        return rows;
    }

    /** 用户列表（分页）；只给账号维度信息，不含对话内容 */
    public List<Map<String, Object>> listUsers(int limit) {
        return jdbc.query(
                "SELECT id,email,nickname,role,status,created_at,last_login_at FROM users ORDER BY id DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("email", rs.getString("email"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("role", rs.getString("role"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("lastLoginAt", rs.getString("last_login_at"));
                    return m;
                }, Math.max(1, Math.min(500, limit)));
    }

    private int count(String sql, Object... args) {
        try {
            Integer v = jdbc.queryForObject(sql, Integer.class, args);
            return v == null ? 0 : v;
        } catch (Exception e) {
            logger.debug("统计失败(按 0 处理): {} -> {}", sql, e.getMessage());
            return 0;
        }
    }
}
