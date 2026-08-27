package org.example.service;

import org.example.entity.PlanTask;
import org.example.entity.StudyPlan;
import org.example.config.ChatModelFactory;
import org.example.entity.WeakArea;
import org.example.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 学习计划与任务服务（计划/执行面板的数据底座）。
 * 负责 StudyPlan / PlanTask / WeakArea 的 CRUD、进度聚合、教练重排。
 */
@Service
public class PlanService {

    private static final Logger logger = LoggerFactory.getLogger(PlanService.class);

    private final JdbcTemplate jdbc;
    private final ChatModelFactory chatModelFactory;

    @Autowired
    public PlanService(JdbcTemplate jdbc, ChatModelFactory chatModelFactory) {
        this.jdbc = jdbc;
        this.chatModelFactory = chatModelFactory;
    }

    // ===== 自动 Plan-Execute-Replan 配置（application.yml plan.auto-replan.*）=====
    @Value("${plan.auto-replan.enabled:true}")
    private boolean autoReplanEnabled;
    @Value("${plan.auto-replan.no-checkin-days:3}")
    private int noCheckinDays;
    @Value("${plan.auto-replan.cooldown-days:7}")
    private int cooldownDays;

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private static final RowMapper<StudyPlan> PLAN_ROW = (ResultSet rs, int i) -> {
        StudyPlan p = new StudyPlan();
        p.setId(rs.getLong("id"));
        p.setTitle(rs.getString("title"));
        p.setSubject(rs.getString("subject"));
        p.setExamDate(parseDate(rs.getString("exam_date")));
        p.setTotalDays(rs.getObject("total_days") != null ? rs.getInt("total_days") : null);
        p.setStatus(rs.getString("status"));
        p.setPriority(rs.getObject("priority") != null ? rs.getInt("priority") : 3);
        p.setHoursPerDay(rs.getObject("hours_per_day") != null ? rs.getDouble("hours_per_day") : 2.0);
        p.setNotes(rs.getString("notes"));
        p.setGoalText(rs.getString("goal_text"));
        p.setCreatedAt(parseDateTime(rs.getString("created_at")));
        p.setUpdatedAt(parseDateTime(rs.getString("updated_at")));
        return p;
    };

    private static final RowMapper<PlanTask> TASK_ROW = (ResultSet rs, int i) -> {
        PlanTask t = new PlanTask();
        t.setId(rs.getLong("id"));
        t.setPlanId(rs.getLong("plan_id"));
        t.setPhase(rs.getString("phase"));
        t.setWeek(rs.getObject("week") != null ? rs.getInt("week") : null);
        t.setDay(rs.getObject("day") != null ? rs.getInt("day") : null);
        t.setTitle(rs.getString("title"));
        t.setDescription(rs.getString("description"));
        t.setExpectedHours(rs.getObject("expected_hours") != null ? rs.getDouble("expected_hours") : null);
        t.setStatus(rs.getString("status"));
        t.setScheduledDate(parseDate(rs.getString("scheduled_date")));
        t.setActualDate(parseDate(rs.getString("actual_date")));
        t.setCompletionNote(rs.getString("completion_note"));
        t.setChapter(rs.getString("chapter"));
        return t;
    };

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); } catch (Exception e) { return null; }
    }

    /** 创建计划并批量写入任务 */
    public StudyPlan createPlan(String title, String subject, LocalDate examDate, int totalDays,
                                List<Map<String, Object>> tasks) {
        return createPlan(title, subject, examDate, totalDays, tasks, 3, 2.0, null, null);
    }

    /**
     * 创建计划并批量写入任务（完整字段：priority / hoursPerDay / notes / goalText）。
     */
    public StudyPlan createPlan(String title, String subject, LocalDate examDate, int totalDays,
                                List<Map<String, Object>> tasks,
                                Integer priority, Double hoursPerDay, String notes, String goalText) {
        String now = LocalDateTime.now().toString();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO study_plan(title,subject,exam_date,total_days,status,priority,hours_per_day,notes,goal_text,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, title);
            ps.setString(2, subject);
            ps.setString(3, examDate != null ? examDate.toString() : null);
            ps.setInt(4, totalDays);
            ps.setString(5, "ACTIVE");
            if (priority != null) ps.setInt(6, priority); else ps.setNull(6, java.sql.Types.INTEGER);
            if (hoursPerDay != null) ps.setDouble(7, hoursPerDay); else ps.setNull(7, java.sql.Types.DOUBLE);
            ps.setString(8, notes);
            ps.setString(9, goalText);
            ps.setString(10, now);
            ps.setString(11, now);
            return ps;
        }, kh);
        Long planId = ((Number) kh.getKey()).longValue();

        if (tasks != null) {
            for (Map<String, Object> t : tasks) {
                insertTask(planId, t);
            }
        }
        logger.info("创建计划 id={} 标题={} 任务数={}", planId, title, tasks != null ? tasks.size() : 0);
        return getPlan(planId);
    }

    private void insertTask(Long planId, Map<String, Object> t) {
        insertTaskReturningId(planId, t);
    }

    private Long insertTaskReturningId(Long planId, Map<String, Object> t) {
        org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO plan_task(plan_id,phase,week,day,title,description,expected_hours,status,scheduled_date,chapter) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, planId);
            ps.setString(2, str(t.get("phase")));
            Integer week = intOrNull(t.get("week"));
            if (week != null) ps.setInt(3, week); else ps.setNull(3, java.sql.Types.INTEGER);
            Integer day = intOrNull(t.get("day"));
            if (day != null) ps.setInt(4, day); else ps.setNull(4, java.sql.Types.INTEGER);
            ps.setString(5, str(t.get("title")));
            ps.setString(6, str(t.get("description")));
            Double hrs = dblOrNull(t.get("expectedHours"));
            if (hrs != null) ps.setDouble(7, hrs); else ps.setNull(7, java.sql.Types.DOUBLE);
            ps.setString(8, "TODO");
            ps.setString(9, str(t.get("scheduledDate")));
            ps.setString(10, str(t.get("chapter")));
            return ps;
        }, kh);
        return ((Number) kh.getKey()).longValue();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }
    private static Double dblOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    public List<StudyPlan> listPlans() {
        return jdbc.query("SELECT * FROM study_plan ORDER BY created_at DESC", PLAN_ROW);
    }

    public StudyPlan getPlan(Long id) {
        List<StudyPlan> r = jdbc.query("SELECT * FROM study_plan WHERE id=?", PLAN_ROW, id);
        return r.isEmpty() ? null : r.get(0);
    }

    public List<PlanTask> getTasks(Long planId) {
        return jdbc.query("SELECT * FROM plan_task WHERE plan_id=? ORDER BY week, day", TASK_ROW, planId);
    }

    public PlanTask updateTaskStatus(Long taskId, String status) {
        return updateTaskStatus(taskId, status, null);
    }

    /**
     * 更新任务状态。note 仅在置为 DONE 时有意义：写入 completion_note（"内容 · MM-dd HH:mm"），
     * 供「每日打卡 / 任务完成详情」带时间展示。
     */
    public PlanTask updateTaskStatus(Long taskId, String status, String note) {
        String actualDate = "DONE".equals(status) ? LocalDate.now().toString() : null;
        String completionNote = null;
        if ("DONE".equals(status) && note != null && !note.isBlank()) {
            String ts = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            completionNote = note.trim() + " · " + ts;
        }
        jdbc.update("UPDATE plan_task SET status=?, actual_date=?, completion_note=? WHERE id=?",
                status, actualDate, completionNote, taskId);
        List<PlanTask> r = jdbc.query("SELECT * FROM plan_task WHERE id=?", TASK_ROW, taskId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 删除计划及其下任务、打卡与薄弱点记录 */
    public int deletePlan(Long id) {
        jdbc.update("DELETE FROM weak_area WHERE plan_id=?", id);
        jdbc.update("DELETE FROM daily_checkin WHERE plan_id=?", id);
        jdbc.update("DELETE FROM plan_task WHERE plan_id=?", id);
        return jdbc.update("DELETE FROM study_plan WHERE id=?", id);
    }

    /**
     * 进度看板入口：观察执行状态并自动重排（Plan-Execute-Replan 闭环的「观察点」）。
     * 每次查看看板（前端看板 / CoachAgent 的 getDashboard 工具 / 打卡后刷新）都会检查
     * 是否满足自动重排条件：连续无打卡 + 有逾期未完成任务 + 冷却期已过。
     * 满足则调用 replan 重写剩余任务（保留已完成），并把 autoReplanned / coachNote
     * 带回给调用方，由前端或教练向学员解释调整。
     */
    public Map<String, Object> getDashboard(Long planId) {
        Map<String, Object> dash = buildDashboard(planId);
        maybeAutoReplan(planId, dash);
        return dash;
    }

    /** 进度聚合：总数/已完成/完成率/按状态分布/连续打卡（不含自动重排，供 replan 复用避免递归） */
    private Map<String, Object> buildDashboard(Long planId) {
        List<PlanTask> tasks = getTasks(planId);
        long total = tasks.size();
        long done = tasks.stream().filter(t -> "DONE".equals(t.getStatus())).count();
        double rate = total == 0 ? 0 : (double) done / total;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("TODO", tasks.stream().filter(t -> "TODO".equals(t.getStatus())).count());
        byStatus.put("DOING", tasks.stream().filter(t -> "DOING".equals(t.getStatus())).count());
        byStatus.put("DONE", done);
        StudyPlan plan = getPlan(planId);
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("planId", planId);
        dash.put("title", plan != null ? plan.getTitle() : null);
        dash.put("examDate", plan != null && plan.getExamDate() != null ? plan.getExamDate().toString() : null);
        dash.put("totalTasks", total);
        dash.put("doneTasks", done);
        // 完成率返回百分比整数（0-100），与前端展示一致
        dash.put("completionRate", (int) Math.round(rate * 100));
        dash.put("byStatus", byStatus);
        // 前端看板期望的形态：streak + statusDistribution 数组
        dash.put("statusDistribution", new java.util.ArrayList<>(List.of(
                Map.of("status", "TODO", "count", byStatus.get("TODO")),
                Map.of("status", "DOING", "count", byStatus.get("DOING")),
                Map.of("status", "DONE", "count", byStatus.get("DONE")))));
        try {
            List<String> dates = jdbc.query(
                    "SELECT DISTINCT date FROM daily_checkin WHERE plan_id=? ORDER BY date DESC",
                    (rs, i) -> rs.getString("date"), planId);
            if (dates != null && !dates.isEmpty()) {
                java.util.Set<LocalDate> set = new java.util.HashSet<>();
                for (String d : dates) { LocalDate ld = parseDate(d); if (ld != null) set.add(ld); }
                LocalDate cursor = LocalDate.now();
                int streak = 0;
                while (set.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
                dash.put("streak", streak);
            } else {
                dash.put("streak", 0);
            }
        } catch (Exception e) {
            dash.put("streak", 0);
        }
        return dash;
    }

    // ===== 自动 Plan-Execute-Replan =====

    /** 自动重排的固定指令模板（%d = 连续无打卡天数） */
    private static final String AUTO_REPLAN_INSTRUCTION =
            "学员已连续 %d 天未打卡，计划处于停滞。请根据当前日期与剩余时间，自动重排【未完成】任务：" +
            "合理调整每天的节奏与任务量，保证每天可完成、且能覆盖剩余全部天数。保留已完成任务不变。";
    /** 自动重排后的教练说明（%d = 无打卡天数，%d = 第几次自动调整） */
    private static final String COACH_NOTE_TEMPLATE =
            "检测到已连续 %d 天未打卡，已自动重排剩余任务（保留已完成、按剩余时间重写未完成），这是第 %d 次自动调整。今天记得打卡哦～";

    /**
     * 观察执行状态 → 自动重排（真正的「观察 → 决策 → 重排」闭环）。
     * 满足停滞条件时调用 replan 重写剩余任务，并把结果与教练说明合并进调用方看板。
     */
    private void maybeAutoReplan(Long planId, Map<String, Object> dash) {
        if (!autoReplanEnabled) return;
        try {
            if (!shouldAutoReplan(planId)) return;
            logger.info("检测到计划 {} 停滞，触发自动重排（连续 {} 天无打卡）", planId, noCheckinDays);
            // 用「重排前后 last_replan_at 是否变化」判断真实生效：
            // replan 内部 LLM 失败时会吞掉异常并返回原看板（无 error 键），不能据返回值误判成功。
            String before = lastReplanAt(planId);
            Map<String, Object> newDash = replan(planId, String.format(AUTO_REPLAN_INSTRUCTION, noCheckinDays));
            String after = lastReplanAt(planId);
            if (after != null && !after.equals(before)) {
                jdbc.update("UPDATE study_plan SET auto_replan_count = COALESCE(auto_replan_count, 0) + 1 WHERE id=?", planId);
                int count = autoReplanCount(planId);
                dash.putAll(newDash);
                dash.put("autoReplanned", true);
                dash.put("autoReplanCount", count);
                dash.put("coachNote", String.format(COACH_NOTE_TEMPLATE, noCheckinDays, count));
                logger.info("自动重排完成：planId={}，第 {} 次", planId, count);
            } else {
                logger.warn("自动重排未生效(LLM 失败或未改动任务)，不累计次数：planId={}", planId);
            }
        } catch (Exception e) {
            logger.warn("自动重排异常(不影响看板): {}", e.getMessage());
        }
    }

    /** 读取计划最近一次重排时间（手动/自动共用），可能为 null */
    private String lastReplanAt(Long planId) {
        try {
            return jdbc.queryForObject(
                    "SELECT last_replan_at FROM study_plan WHERE id=?", String.class, planId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 停滞判定：进行中 + 近 N 天无打卡 + 有逾期未完成任务 + 冷却期已过 */
    private boolean shouldAutoReplan(Long planId) {
        StudyPlan plan = getPlan(planId);
        if (plan == null || !"ACTIVE".equals(plan.getStatus())) return false;

        // 近 N 天（含今天）无任何打卡
        String since = LocalDate.now().minusDays(noCheckinDays).toString();
        Integer checkins = jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_checkin WHERE plan_id=? AND date>=?", Integer.class, planId, since);
        if (checkins == null || checkins > 0) return false;

        // 有「计划日期已到但未完成」的任务（落后证据，且避免计划刚开始就误触发）
        Integer overdue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_task WHERE plan_id=? AND scheduled_date IS NOT NULL " +
                        "AND scheduled_date<=? AND status<>'DONE'",
                Integer.class, planId, LocalDate.now().toString());
        if (overdue == null || overdue == 0) return false;

        // 冷却期：距上次任意重排（手动/自动）>= cooldownDays 天
        String last = jdbc.queryForObject(
                "SELECT last_replan_at FROM study_plan WHERE id=?", String.class, planId);
        if (last != null && !last.isBlank()) {
            LocalDate lastReplan = parseDate(last);
            if (lastReplan != null && !lastReplan.isBefore(LocalDate.now().minusDays(cooldownDays))) {
                return false;
            }
        }
        return true;
    }

    /** 读取自动重排累计次数 */
    private int autoReplanCount(Long planId) {
        try {
            Integer c = jdbc.queryForObject(
                    "SELECT auto_replan_count FROM study_plan WHERE id=?", Integer.class, planId);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 教练重排：根据指令让 LLM 重写剩余任务，失败则保留原样；已完成任务不受影响 */
    public Map<String, Object> replan(Long planId, String instruction) {
        StudyPlan plan = getPlan(planId);
        if (plan == null) return Map.of("error", "plan not found");
        List<PlanTask> tasks = getTasks(planId);
        List<PlanTask> doneTasks = new ArrayList<>();
        for (PlanTask t : tasks) {
            if ("DONE".equals(t.getStatus())) doneTasks.add(t);
        }
        try {
            LocalDate today = LocalDate.now();
            // 剩余可用天数：优先「今天 → 考试日」，再与计划总天数取小，兜底 30
            int maxDay = 30;
            if (plan.getExamDate() != null) {
                long rem = plan.getExamDate().toEpochDay() - today.toEpochDay();
                maxDay = (int) Math.max(1, rem);
            }
            if (plan.getTotalDays() != null && plan.getTotalDays() > 0) {
                maxDay = Math.min(maxDay, plan.getTotalDays());
            }
            maxDay = Math.max(1, maxDay);

            String system = "你是学习计划教练。根据学员指令，重写该计划的【尚未完成】任务列表。" +
                    "只输出 JSON 数组，不要 markdown code fence、不要任何说明文字。每个元素严格如下：" +
                    "{\"day\":整数从1开始,\"phase\":\"阶段名\",\"title\":\"任务标题\",\"description\":\"说明\",\"expectedHours\":数字,\"chapter\":\"章节标签\"}。" +
                    "注意：scheduledDate/week 不需给出，后端会按 day 自动计算（今天为 day 1）。";
            StringBuilder sb = new StringBuilder();
            sb.append("今天是：").append(today).append("\n");
            if (plan.getExamDate() != null) sb.append("考试日期：").append(plan.getExamDate()).append("\n");
            sb.append("剩余可用天数（从今天起）：").append(maxDay).append(" 天，任务 day 从 1 到 ").append(maxDay).append("，每天 1-3 个。\n");
            sb.append("计划标题：").append(plan.getTitle()).append("\n");
            sb.append("考试科目：").append(plan.getSubject()).append("\n");
            sb.append("当前任务：\n");
            for (PlanTask t : tasks) {
                sb.append("- [").append(t.getStatus()).append("] ").append(t.getTitle())
                        .append(" (week=").append(t.getWeek()).append(",day=").append(t.getDay()).append(")\n");
            }
            sb.append("\n学员指令：").append(instruction);
            String raw = chatModelFactory.complete(system, sb.toString(), 0.4, 8192);
            // 归一化：不信任 LLM 给的日期，一律按 day 相对今天重算（修复 LLM 幻觉历史日期的 bug）
            List<Map<String, Object>> newTasks = normalizeReplannedTasks(parseTaskArray(raw), today, maxDay);
            if (!newTasks.isEmpty()) {
                // 删除旧任务，写入新任务（保留已完成任务原样重新插入，维护打卡/进度数据）
                jdbc.update("DELETE FROM plan_task WHERE plan_id=?", planId);
                // 先恢复已完成任务
                for (PlanTask d : doneTasks) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("phase", d.getPhase());
                    m.put("week", d.getWeek());
                    m.put("day", d.getDay());
                    m.put("title", d.getTitle());
                    m.put("description", d.getDescription());
                    m.put("expectedHours", d.getExpectedHours());
                    m.put("scheduledDate", d.getScheduledDate() != null ? d.getScheduledDate().toString() : null);
                    Long newId = insertTaskReturningId(planId, m);
                    jdbc.update("UPDATE plan_task SET status='DONE', actual_date=? WHERE id=?",
                            d.getActualDate() != null ? d.getActualDate().toString() : LocalDate.now().toString(), newId);
                }
                for (Map<String, Object> t : newTasks) insertTask(planId, t);
                // 记录重排时间：手动/自动重排都刷新，作为自动重排的冷却锚点（避免刚重排又触发）
                jdbc.update("UPDATE study_plan SET last_replan_at=? WHERE id=?", LocalDate.now().toString(), planId);
                logger.info("计划 {} 重排完成，新任务数={}（保留已完成 {} 个）", planId, newTasks.size(), doneTasks.size());
            }
        } catch (Exception e) {
            logger.warn("重排失败，保留原任务: {}", e.getMessage());
        }
        return buildDashboard(planId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTaskArray(String raw) {
        return JsonExtractor.parseTopLevelArray(raw);
    }

    /**
     * 归一化重排任务：不信任 LLM 输出的日期/周数，一律按 day 相对今天重算。
     * day 缺失或越界时按顺序补位；scheduledDate = today + (day-1)；week = ceil(day/7)；expectedHours 兜底。
     */
    private List<Map<String, Object>> normalizeReplannedTasks(List<Map<String, Object>> parsed, LocalDate today, int maxDay) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parsed == null) return out;
        for (Map<String, Object> t : parsed) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            Integer d = intOrNull(t.get("day"));
            if (d == null || d < 1 || d > maxDay) d = out.size() + 1;
            if (d > maxDay) d = maxDay;
            m.put("day", d);
            m.put("week", (int) Math.ceil(d / 7.0));
            m.put("scheduledDate", today.plusDays(d - 1).toString());
            Double hrs = dblOrNull(t.get("expectedHours"));
            if (hrs == null || hrs <= 0) hrs = 1.5;
            m.put("expectedHours", hrs);
            // 章节标签兜底：AI 没给 chapter 时用阶段名，保证出题可选章节
            String ch = str(m.get("chapter"));
            String ph = str(m.get("phase"));
            if ((ch == null || ch.isBlank()) && ph != null && !ph.isBlank()) {
                m.put("chapter", ph);
            }
            out.add(m);
        }
        return out;
    }

    /**
     * AI 按天拆任务：基于学员目标调 LLM 生成按 day 1..N 的任务列表，落库到 study_plan/plan_task。
     * 失败时不抛错，返回空任务列表；调用方应检查 plan 已建好但 tasks 为空后给前端提示。
     */
    public StudyPlan generatePlanByDays(String title, String subject, LocalDate examDate,
                                        int totalDays, double hoursPerDay,
                                        Integer priority, String notes, String topics,
                                        List<String> kbContext) {
        int safeDays = Math.max(1, Math.min(totalDays, 60));
        double safeHrs = Math.max(0.5, Math.min(hoursPerDay <= 0 ? 2.0 : hoursPerDay, 12));
        // 计算每个任务的合理节奏：每天大概 2-3 个核心动作
        int tasksPerDay = Math.max(2, (int) Math.round(safeHrs / 1.5));

        StringBuilder sys = new StringBuilder();
        sys.append("你是教育规划师。请围绕学员目标生成「按天」的复习任务，整个计划正好覆盖 ").append(safeDays).append(" 天，每天约 ").append(safeHrs).append(" 小时，每天拆 ").append(tasksPerDay).append(" 个核心动作。\n");
        sys.append("任务粒度：每天 2-3 个，每个都有「明确标题 + 1-2 句说明 + 预计小时数」。\n");
        sys.append("Phase 可分阶段：例如「基础梳理」「专题强化」「真题冲刺」「查漏补缺」。\n");
        sys.append("只输出 JSON 数组，不要 markdown code fence、不要任何说明文字。每个元素严格如下：\n");
        sys.append("{\"day\":整数从 1 开始,\"phase\":\"阶段名\",\"title\":\"任务标题\",\"description\":\"执行说明\",\"expectedHours\":数字,\"chapter\":\"章节标签\"}\n");
        sys.append("注意：scheduledDate 不需给出，后端会按 day 自动设为今天 + (day-1)。");

        StringBuilder user = new StringBuilder();
        user.append("【计划标题】").append(title).append("\n");
        user.append("【科目】").append(subject != null ? subject : "未指定").append("\n");
        if (examDate != null) user.append("【考试日】").append(examDate.toString()).append("\n");
        user.append("【覆盖天数】").append(safeDays).append("，每天 ").append(safeHrs).append(" 小时\n");
        user.append("【任务密度】每天 ").append(tasksPerDay).append(" 个\n");
        if (priority != null) user.append("【学员优先级】").append(priority).append("（5 最高）。\n");
        if (notes != null && !notes.isBlank()) user.append("【学员补充】").append(notes).append("\n");
        if (topics != null && !topics.isBlank()) user.append("【重点章节/资料】").append(topics).append("\n");
        if (kbContext != null && !kbContext.isEmpty()) {
            user.append("\n【已上传资料摘要】\n");
            int n = 0;
            for (String s : kbContext) {
                if (s == null || s.isBlank()) continue;
                user.append("- ").append(s.length() > 200 ? s.substring(0, 200) + "..." : s).append("\n");
                if (++n >= 6) break;
            }
        }

        String raw;
        try {
            raw = chatModelFactory.complete(sys.toString(), user.toString(), 0.4, 8192);
        } catch (Exception e) {
            logger.warn("LLM 规划失败，返回空任务列表：{}", e.getMessage());
            raw = "[]";
        }
        List<Map<String, Object>> parsed = parseTaskArray(raw);
        // 规范化 day / scheduledDate / expectedHours
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> t : parsed) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            Integer d = intOrNull(t.get("day"));
            if (d == null || d < 1 || d > safeDays) d = normalized.size() + 1;
            if (d > safeDays) d = safeDays;
            m.put("day", d);
            m.put("week", (int) Math.ceil(d / 7.0));
            m.put("scheduledDate", today.plusDays(d - 1).toString());
            Double hrs = dblOrNull(t.get("expectedHours"));
            if (hrs == null || hrs <= 0) hrs = safeHrs / tasksPerDay;
            m.put("expectedHours", hrs);
            // 章节标签兜底：AI 没给该任务填 chapter 时，用阶段名(phase)作为章节，保证出题可选章节
            String ch = str(m.get("chapter"));
            String ph = str(m.get("phase"));
            if ((ch == null || ch.isBlank()) && ph != null && !ph.isBlank()) {
                m.put("chapter", ph);
            }
            normalized.add(m);
        }
        // 强制每天都至少有一个任务（哪怕 LLM 输出稀疏）
        java.util.Set<Integer> haveDays = new java.util.HashSet<>();
        for (Map<String, Object> n : normalized) haveDays.add(intOrNull(n.get("day")));
        for (int d = 1; d <= safeDays; d++) {
            if (haveDays.contains(d)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d);
            m.put("week", (int) Math.ceil(d / 7.0));
            m.put("phase", d <= safeDays / 3 ? "基础梳理" : (d <= safeDays * 2 / 3 ? "专题强化" : (d == safeDays ? "考前冲刺" : "查漏补缺")));
            m.put("chapter", d <= safeDays / 3 ? "基础梳理" : (d <= safeDays * 2 / 3 ? "专题强化" : (d == safeDays ? "考前冲刺" : "查漏补缺")));
            m.put("title", "Day " + d + " · 复习 ");
            m.put("description", "复盘前几日要点，按清单练习。");
            m.put("expectedHours", Math.max(1.0, safeHrs / Math.max(1, normalized.size() / safeDays)));
            m.put("scheduledDate", today.plusDays(d - 1).toString());
            normalized.add(m);
        }
        // 重新排序
        normalized.sort((a, b) -> Integer.compare(intOrNull(a.get("day")), intOrNull(b.get("day"))));
        StudyPlan p = createPlan(title, subject, examDate, safeDays, normalized, priority, safeHrs, notes,
                notes != null && !notes.isBlank() ? notes : title);
        logger.info("AI 拆任务：planId={} 共 {} 条（覆盖 {} 天）", p.getId(), normalized.size(), safeDays);
        return p;
    }

    /**
     * 取该计划下不重复的章节列表，给前端 quiz 出题按章节用。
     * 数据源三选一（合并）：
     *   1) plan_task.chapter（AI 规划时任务自带章节标签）
     *   2) plan_chapter（用户在「管理章节」里手动维护的章节名）
     *   3) 以上都为空时兜底为 plan_task.phase（阶段名，保证下拉框有内容可选）
     */
    public List<String> listChapters(Long planId) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT chapter FROM plan_task WHERE plan_id=? AND chapter IS NOT NULL AND chapter<>'' ORDER BY chapter",
                    (rs) -> { out.add(rs.getString(1)); }, planId);
        } catch (Exception ignored) {}
        try {
            jdbc.query(
                    "SELECT name FROM plan_chapter WHERE plan_id=? ORDER BY name",
                    (rs) -> { out.add(rs.getString(1)); }, planId);
        } catch (Exception ignored) { /* 老库可能还没有 plan_chapter 表 */ }
        if (out.isEmpty()) {
            try {
                jdbc.query(
                        "SELECT DISTINCT phase FROM plan_task WHERE plan_id=? AND phase IS NOT NULL AND phase<>'' ORDER BY phase",
                        (rs) -> { out.add(rs.getString(1)); }, planId);
            } catch (Exception ignored) {}
        }
        return new java.util.ArrayList<>(out);
    }

    /** 手动给计划添加一个章节名（重复则忽略），供「管理章节」UI 使用 */
    public void addChapter(Long planId, String name) {
        if (name == null || name.isBlank()) return;
        jdbc.update("INSERT OR IGNORE INTO plan_chapter(plan_id,name,created_at) VALUES(?,?,?)",
                planId, name.trim(), LocalDateTime.now().toString());
    }

    /** 手动删除一个章节名，供「管理章节」UI 使用 */
    public void removeChapter(Long planId, String name) {
        if (name == null || name.isBlank()) return;
        jdbc.update("DELETE FROM plan_chapter WHERE plan_id=? AND name=?", planId, name.trim());
    }

    // ===== WeakArea（薄弱点） =====
    private static final RowMapper<WeakArea> WEAK_ROW = (ResultSet rs, int i) -> {
        WeakArea w = new WeakArea();
        w.setId(rs.getLong("id"));
        w.setPlanId(rs.getLong("plan_id"));
        w.setSubject(rs.getString("subject"));
        w.setChapter(rs.getString("chapter"));
        w.setReason(rs.getString("reason"));
        w.setSeverity(rs.getObject("severity") != null ? rs.getInt("severity") : 3);
        w.setSuggestedAction(rs.getString("suggested_action"));
        w.setCreatedAt(parseDateTime(rs.getString("created_at")));
        w.setUpdatedAt(parseDateTime(rs.getString("updated_at")));
        return w;
    };

    public void upsertWeakArea(Long planId, String chapter, String reason, int severity, String suggested) {
        String now = LocalDateTime.now().toString();
        List<WeakArea> existing = jdbc.query(
                "SELECT * FROM weak_area WHERE plan_id=? AND chapter=?", WEAK_ROW, planId, chapter);
        if (existing.isEmpty()) {
            jdbc.update(
                    "INSERT INTO weak_area(plan_id,subject,chapter,reason,severity,suggested_action,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    planId, null, chapter, reason, severity, suggested, now, now);
        } else {
            jdbc.update(
                    "UPDATE weak_area SET severity=?, reason=?, suggested_action=?, updated_at=? WHERE id=?",
                    severity, reason, suggested, now, existing.get(0).getId());
        }
    }

    public List<WeakArea> getWeakAreas(Long planId) {
        return jdbc.query("SELECT * FROM weak_area WHERE plan_id=? ORDER BY severity DESC", WEAK_ROW, planId);
    }

    /** 薄弱点热力图数据（前端期望形态）：[{topic, weight, chapter, reason}]，weight 由 severity 映射 0-100 */
    public List<Map<String, Object>> getWeakHeatmap(Long planId) {
        List<WeakArea> areas = getWeakAreas(planId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WeakArea w : areas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", w.getChapter() != null ? w.getChapter() : "综合");
            m.put("chapter", w.getChapter());
            m.put("reason", w.getReason());
            m.put("suggestedAction", w.getSuggestedAction());
            int sev = w.getSeverity() == null ? 3 : w.getSeverity();
            m.put("severity", sev);
            m.put("weight", Math.max(10, Math.min(100, sev * 25)));
            out.add(m);
        }
        return out;
    }
}
