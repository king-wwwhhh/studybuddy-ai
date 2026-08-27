package org.example.service;

import org.example.entity.DailyCheckin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 每日打卡服务（监督执行数据底座）。
 * 负责打卡记录写入、连续打卡(streak)计算、以及打卡面板聚合。
 */
@Service
public class CheckinService {

    private static final Logger logger = LoggerFactory.getLogger(CheckinService.class);
    private final JdbcTemplate jdbc;

    @Autowired
    public CheckinService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private static final RowMapper<DailyCheckin> ROW = (ResultSet rs, int i) -> {
        DailyCheckin c = new DailyCheckin();
        c.setId(rs.getLong("id"));
        c.setPlanId(rs.getLong("plan_id"));
        c.setDate(parseDate(rs.getString("date")));
        c.setDoneTaskIds(rs.getString("done_task_ids"));
        c.setCompletionText(rs.getString("completion_text"));
        c.setMood(rs.getString("mood"));
        c.setHoursStudied(rs.getObject("hours_studied") != null ? rs.getDouble("hours_studied") : null);
        c.setCreatedAt(rs.getString("created_at") != null ? LocalDateTime.parse(rs.getString("created_at")) : null);
        return c;
    };

    public DailyCheckin addCheckin(Long planId, LocalDate date, String doneTaskIds,
                                   String completionText, String mood, Double hoursStudied) {
        String now = LocalDateTime.now().toString();
        jdbc.update(
                "INSERT INTO daily_checkin(plan_id,date,done_task_ids,completion_text,mood,hours_studied,created_at) VALUES(?,?,?,?,?,?,?)",
                planId,
                date != null ? date.toString() : LocalDate.now().toString(),
                doneTaskIds,
                completionText,
                mood,
                hoursStudied,
                now);
        List<DailyCheckin> r = jdbc.query(
                "SELECT * FROM daily_checkin WHERE plan_id=? ORDER BY created_at DESC LIMIT 1", ROW, planId);
        logger.info("打卡记录 planId={} date={}", planId, date);
        return r.isEmpty() ? null : r.get(0);
    }

    public List<DailyCheckin> listByPlan(Long planId) {
        return jdbc.query("SELECT * FROM daily_checkin WHERE plan_id=? ORDER BY date DESC", ROW, planId);
    }

    /** 连续打卡天数（截至今天，向前连续有记录的日期数） */
    public int streak(Long planId) {
        List<String> all = jdbc.query(
                "SELECT DISTINCT date FROM daily_checkin WHERE plan_id=? ORDER BY date DESC", (rs, i) -> rs.getString("date"), planId);
        if (all.isEmpty()) return 0;
        Set<LocalDate> dates = new HashSet<>();
        for (var r : all) { LocalDate d = parseDate(r); if (d != null) dates.add(d); }
        LocalDate cursor = LocalDate.now();
        int count = 0;
        while (dates.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /** 打卡面板聚合 */
    public Map<String, Object> dashboard(Long planId) {
        List<DailyCheckin> all = listByPlan(planId);
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("planId", planId);
        dash.put("totalCheckins", all.size());
        dash.put("streak", streak(planId));
        dash.put("recent", all.stream().limit(7).toList());
        double totalHours = all.stream().mapToDouble(c -> c.getHoursStudied() != null ? c.getHoursStudied() : 0).sum();
        dash.put("totalHours", Math.round(totalHours * 10) / 10.0);
        return dash;
    }
}
