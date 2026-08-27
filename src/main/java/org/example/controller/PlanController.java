package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.entity.DailyCheckin;
import org.example.entity.PlanTask;
import org.example.entity.StudyPlan;
import org.example.service.ChatService;
import org.example.service.CheckinService;
import org.example.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 计划与执行面板 REST 接口。
 * 覆盖：计划 CRUD、任务状态变更、每日打卡、教练重排、进度看板、薄弱点，以及 AI 规划端点。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    @Autowired
    private PlanService planService;
    @Autowired
    private CheckinService checkinService;
    @Autowired
    private ChatService chatService;

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(ApiResponse<T> r) {
        return ResponseEntity.ok(r);
    }

    /** 列出所有计划（内嵌各自任务，供前端直接渲染时间轴） */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudyPlan p : planService.listPlans()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            m.put("subject", p.getSubject());
            m.put("examDate", p.getExamDate() != null ? p.getExamDate().toString() : null);
            m.put("totalDays", p.getTotalDays());
            m.put("status", p.getStatus());
            m.put("priority", p.getPriority());
            m.put("hoursPerDay", p.getHoursPerDay());
            m.put("notes", p.getNotes());
            m.put("createdAt", p.getCreatedAt());
            m.put("updatedAt", p.getUpdatedAt());
            m.put("tasks", planService.getTasks(p.getId()));
            out.add(m);
        }
        return ok(ApiResponse.success(out));
    }

    /** 手动创建计划（tasks 可选；不传则仅建空计划壳） */
    @PostMapping
    public ResponseEntity<ApiResponse<StudyPlan>> create(@RequestBody PlanCreateReq req) {
        if (req.title == null || req.title.isBlank()) {
            return ok(ApiResponse.error("title 不能为空"));
        }
        int totalDays = req.totalDays != null ? req.totalDays : 30;
        LocalDate exam = parseDate(req.examDate);
        StudyPlan p = planService.createPlan(req.title, req.subject, exam, totalDays, req.tasks,
                req.priority, req.hoursPerDay, req.notes, req.goalText);
        // 把创建时填的字段回写到 DB（createPlan 已写，但补一下 goalText 实时变化）
        return ok(ApiResponse.success(p));
    }

    /**
     * 新建计划：从前端"+"表单直接保存（手动档）。
     * 与 POST /api/plans 等价，但显式区分意图，便于以后日志/权限扩展。
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<StudyPlan>> createDetailed(@RequestBody PlanCreateReq req) {
        return create(req);
    }

    /**
     * AI 按天拆任务并落库：从前端"AI 帮我规划"表单提交，字段尽量详细：
     *   标题 / 科目 / 考试日 / 总天数 / 每天时长 / 优先级 / notes / topics
     */
    @PostMapping("/ai-detailed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> aiDetailed(@RequestBody AiPlanReq req) {
        if (req.title == null || req.title.isBlank()) {
            return ok(ApiResponse.error("title 不能为空"));
        }
        int totalDays = req.totalDays != null && req.totalDays > 0 ? req.totalDays : 30;
        double hoursPerDay = req.hoursPerDay != null && req.hoursPerDay > 0 ? req.hoursPerDay : 2.0;
        StudyPlan p = planService.generatePlanByDays(
                req.title, req.subject, parseDate(req.examDate),
                totalDays, hoursPerDay,
                req.priority, req.notes, req.topics, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        out.put("tasks", planService.getTasks(p.getId()));
        out.put("chapters", planService.listChapters(p.getId()));
        return ok(ApiResponse.success(out));
    }

    /** 列出某计划下所有章节（用于前端 quiz 表单下拉） */
    @GetMapping("/{id}/chapters")
    public ResponseEntity<ApiResponse<List<String>>> chapters(@PathVariable Long id) {
        return ok(ApiResponse.success(planService.listChapters(id)));
    }

    /** 手动添加一个章节名到计划（「管理章节」UI） */
    @PostMapping("/{id}/chapters")
    public ResponseEntity<ApiResponse<String>> addChapter(@PathVariable Long id,
                                                          @RequestBody ChapterReq req) {
        if (req.name == null || req.name.isBlank()) {
            return ok(ApiResponse.error("name 不能为空"));
        }
        planService.addChapter(id, req.name);
        return ok(ApiResponse.success("ok"));
    }

    /** 手动删除一个章节名（「管理章节」UI） */
    @DeleteMapping("/{id}/chapters")
    public ResponseEntity<ApiResponse<String>> removeChapter(@PathVariable Long id,
                                                             @RequestParam("name") String name) {
        planService.removeChapter(id, name);
        return ok(ApiResponse.success("ok"));
    }

    /** 获取计划 + 其下任务 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        StudyPlan p = planService.getPlan(id);
        if (p == null) return ok(ApiResponse.error("plan not found"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", p);
        m.put("tasks", planService.getTasks(id));
        return ok(ApiResponse.success(m));
    }

    /** 删除计划（含任务 / 打卡 / 薄弱点） */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        planService.deletePlan(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", id);
        return ok(ApiResponse.success(m));
    }

    /** 更新单个任务状态（status: TODO / DOING / DONE） */
    @PutMapping("/{id}/tasks/{taskId}")
    public ResponseEntity<ApiResponse<PlanTask>> updateTask(@PathVariable Long id,
                                                            @PathVariable Long taskId,
                                                            @RequestBody StatusReq req) {
        PlanTask t = planService.updateTaskStatus(taskId, req.status, req.note);
        if (t == null) return ok(ApiResponse.error("task not found"));
        return ok(ApiResponse.success(t));
    }

    /** 每日打卡（兼容前端字段：completedContent / durationMinutes / mood，以及后端标准字段） */
    @PostMapping("/{id}/checkin")
    public ResponseEntity<ApiResponse<DailyCheckin>> checkin(@PathVariable Long id,
                                                             @RequestBody CheckinReq req) {
        // 前端传的 doneTaskIds 可能是数组，转成逗号字符串
        String doneIds = req.doneTaskIds;
        if (req.doneTaskIdList != null && !req.doneTaskIdList.isEmpty()) {
            doneIds = String.join(",", req.doneTaskIdList);
        }
        Double hours = req.hoursStudied;
        if (hours == null && req.durationMinutes != null) {
            hours = req.durationMinutes / 60.0;
        }
        String text = req.completionText != null ? req.completionText : req.completedContent;
        DailyCheckin c = checkinService.addCheckin(id, parseDate(req.date),
                doneIds, text, req.mood, hours);
        return ok(ApiResponse.success(c));
    }

    /** 教练重排：根据指令让 LLM 重写剩余任务（instruction 可空，默认自适应调整） */
    @PostMapping("/{id}/replan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replan(@PathVariable Long id,
                                                                   @RequestBody(required = false) ReplanReq req) {
        String instruction = (req != null && req.instruction != null && !req.instruction.isBlank())
                ? req.instruction : "学员请求重新规划剩余任务，请按当前进度与剩余时间合理调整节奏与任务量。";
        Map<String, Object> dash = planService.replan(id, instruction);
        if (dash.containsKey("error")) return ok(ApiResponse.error(String.valueOf(dash.get("error"))));
        return ok(ApiResponse.success(dash));
    }

    /** 进度看板：总数/已完成/完成率/按状态分布 */
    @GetMapping("/{id}/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard(@PathVariable Long id) {
        return ok(ApiResponse.success(planService.getDashboard(id)));
    }

    /** 薄弱点列表（由测验错题 + 打卡缺口推导），返回前端期望的 {weakAreas:[{topic,weight,...}]} */
    @GetMapping("/{id}/weakness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> weakness(@PathVariable Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weakAreas", planService.getWeakHeatmap(id));
        return ok(ApiResponse.success(out));
    }

    /** AI 规划：调用规划师 Agent，依据需求通过 createPlan 工具落库 */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(@RequestBody AiPlanReq req) {
        if (req.goal == null || req.goal.isBlank()) {
            return ok(ApiResponse.error("goal 不能为空"));
        }
        String prompt = "请为以下需求制定一份学习计划，并直接调用 createPlan 工具保存到系统：\n"
                + "学员目标：" + req.goal + "\n"
                + (req.subject != null && !req.subject.isBlank() ? "科目：" + req.subject + "\n" : "")
                + (req.examDate != null && !req.examDate.isBlank() ? "考试日期：" + req.examDate + "\n" : "")
                + (req.totalDays != null ? "可用总天数：" + req.totalDays + "\n" : "")
                + (req.topics != null && !req.topics.isBlank() ? "重点章节 / 资料：" + req.topics + "\n" : "")
                + "（请参考已上传的知识库与学员画像）";
        ChatService.RouteResult r = chatService.answer(prompt, "planner", List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", r.answer);
        out.put("agent", r.agentName);
        out.put("plans", planService.listPlans());
        return ok(ApiResponse.success(out));
    }

    // ===== 请求体 =====
    @lombok.Data
    public static class PlanCreateReq {
        public String title;
        public String subject;
        public String examDate;
        public Integer totalDays;
        public Integer priority;        // 1-5，5 最紧迫
        public Double hoursPerDay;      // 每天学习时长
        public String notes;            // 备注（基础/目标/偏好）
        public String goalText;         // AI 目标文本
        public List<Map<String, Object>> tasks;
    }

    @lombok.Data
    public static class StatusReq {
        public String status;
        /** 完成说明（置 DONE 时写入 completion_note，带时间戳） */
        public String note;
    }

    @lombok.Data
    public static class CheckinReq {
        public String date;
        public String doneTaskIds;
        public List<String> doneTaskIdList;
        public String completionText;
        public String completedContent;
        public String mood;
        public Double hoursStudied;
        public Double durationMinutes;
    }

    @lombok.Data
    public static class ReplanReq {
        public String instruction;
    }

    @lombok.Data
    public static class ChapterReq {
        public String name;
    }

    @lombok.Data
    public static class AiPlanReq {
        public String title;            // 标题（必填）
        public String goal;             // 老字段，AI 自由规划
        public String subject;
        public String examDate;
        public Integer totalDays;
        public Double hoursPerDay;
        public Integer priority;
        public String notes;
        public String topics;           // 重点章节 / 资料
    }
}
