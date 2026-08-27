package org.example.agent.tool;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划工具（Function Calling）。
 * PlannerAgent 用它创建/查看计划；CoachAgent 用它更新任务状态与重排。
 */
@Component
public class PlanTool {

    private static final Logger logger = LoggerFactory.getLogger(PlanTool.class);
    public static final String TOOL_NAME = "planTool";

    private final PlanService planService;
    private final ObjectMapper mapper = Mappers.JSON;

    @Autowired
    public PlanTool(PlanService planService) {
        this.planService = planService;
    }

    @Tool(description = "Create a study plan with daily tasks. Input JSON: {\"title\":\"期末考试冲刺\",\"subject\":\"操作系统\",\"examDate\":\"2026-09-01\",\"totalDays\":30,\"tasks\":[{\"phase\":\"基础\",\"week\":1,\"day\":1,\"title\":\"进程与线程\",\"expectedHours\":2,\"scheduledDate\":\"2026-08-01\"}]}. Returns the created plan JSON.")
    public String createPlan(@ToolParam(description = "Plan definition as JSON string") String planJson) {
        try {
            JsonNode root = mapper.readTree(planJson);
            String title = root.path("title").asText("未命名计划");
            String subject = root.path("subject").asText(null);
            String examDate = root.path("examDate").asText(null);
            int totalDays = root.path("totalDays").asInt(30);
            List<Map<String, Object>> tasks = mapper.convertValue(
                    root.path("tasks").isMissingNode() ? List.of() : root.path("tasks"), List.class);
            LocalDate exam = parseDateLenient(examDate);
            var plan = planService.createPlan(title, subject, exam, totalDays, tasks);
            // 若 LLM 给的日期没解析出来，显式告诉它，避免它误以为整单失败
            if (exam == null && examDate != null && !examDate.isBlank()) {
                return mapper.writeValueAsString(plan)
                        .replaceFirst("\\}$", ",\"warning\":\"examDate '" + examDate + "' 无法解析，计划已创建但未设置考试日期\"}");
            }
            return mapper.writeValueAsString(plan);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 容错解析日期：优先 ISO，其次常见中英文格式，最后从文本里抓取 YYYY-MM-DD；都失败返回 null。 */
    private static LocalDate parseDateLenient(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try { return LocalDate.parse(t); } catch (Exception ignore) { }
        String[] fmts = { "yyyy年M月d日", "yyyy/M/d", "yyyy.M.d", "M月d日", "M/d", "yyyy-MM-dd HH:mm:ss", "yyyyMMdd" };
        for (String f : fmts) {
            try { return LocalDate.parse(t, DateTimeFormatter.ofPattern(f)); } catch (Exception ignore) { }
        }
        Matcher m = Pattern.compile("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").matcher(t);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception ignore) { }
        }
        return null;
    }

    @Tool(description = "List all study plans (id, title, subject, examDate, status).")
    public String listPlans() {
        try {
            return mapper.writeValueAsString(planService.listPlans());
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Get a plan's tasks and progress dashboard by planId.")
    public String getDashboard(@ToolParam(description = "Plan id") Long planId) {
        try {
            return mapper.writeValueAsString(planService.getDashboard(planId));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Update a task's status. status must be TODO / DOING / DONE.")
    public String updateTaskStatus(@ToolParam(description = "Task id") Long taskId,
                                   @ToolParam(description = "New status: TODO/DOING/DONE") String status) {
        try {
            return mapper.writeValueAsString(planService.updateTaskStatus(taskId, status));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Coach replan: rewrite remaining tasks of a plan according to an instruction (student fell behind, wants easier pace, etc.). Returns new dashboard.")
    public String replan(@ToolParam(description = "Plan id") Long planId,
                         @ToolParam(description = "Coaching instruction, e.g. '把本周任务减半'") String instruction) {
        try {
            return mapper.writeValueAsString(planService.replan(planId, instruction));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
