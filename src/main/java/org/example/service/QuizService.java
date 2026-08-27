package org.example.service;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.config.ChatModelFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测验服务（出题 / 批改 / 薄弱点推导）。
 * 出题与批改均通过 LLM 完成，批改错误时自动记录薄弱章节到 weak_area。
 * <p>出题阶段支持「按计划+章节+知识库」三源融合：
 * <ol>
 *   <li>计划上下文：planId + chapter 都给时，注入该计划标题 / 任务标题列表</li>
 *   <li>知识库上下文：调 VectorSearchService 检索 top-3 片段注入</li>
 *   <li>主题上下文：topic 字符串作为兜底</li>
 * </ol></p>
 */
@Service
public class QuizService {

    private static final Logger logger = LoggerFactory.getLogger(QuizService.class);
    private static final ObjectMapper MAPPER = Mappers.JSON;
    private final ChatModelFactory chatModelFactory;
    private final JdbcTemplate jdbc;
    private final PlanService planService;
    private final VectorSearchService vectorSearchService;

    @Autowired
    public QuizService(ChatModelFactory chatModelFactory, JdbcTemplate jdbc,
                       PlanService planService, VectorSearchService vectorSearchService) {
        this.chatModelFactory = chatModelFactory;
        this.jdbc = jdbc;
        this.planService = planService;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 根据主题生成单选题（plan + RAG 增强版）。
     * 返回 JSON 数组字符串：
     * [{"question":"题目","type":"单选题","options":["A. xxx","B. xxx","C. xxx","D. xxx"],"answer":"正确选项完整文本","chapter":"所属章节"}]
     */
    public String generateQuiz(String topic, int count, Long planId, String chapter) {
        if (count <= 0) count = 5;
        if (count > 20) count = 20;
        try {
            StringBuilder sys = new StringBuilder();
            sys.append("你是出题老师。围绕给定主题出 ").append(count).append(" 道单选题（每题 4 个选项），检验学生对概念的理解。\n");
            sys.append("只输出 JSON 数组，不要 markdown code fence、不要任何解释。每个元素严格如下：\n");
            sys.append("{\"question\":\"题目\",\"type\":\"单选题\",\"options\":[\"A. 选项文本\",\"B. 选项文本\",\"C. 选项文本\",\"D. 选项文本\"],\"answer\":\"正确选项的完整文本（必须与 options 中某一项的【完整字符串】完全一致）\",\"chapter\":\"所属章节\"}\n");

            StringBuilder user = new StringBuilder();

            // 1) 知识库 RAG 上下文
            List<String> kbSnippets = null;
            try {
                String q = topic != null ? topic : (chapter != null ? chapter : "考试");
                List<org.example.service.VectorSearchService.SearchResult> hits =
                        vectorSearchService.searchSimilarDocuments(q, Math.min(3, count));
                if (hits != null && !hits.isEmpty()) {
                    kbSnippets = new ArrayList<>();
                    for (org.example.service.VectorSearchService.SearchResult h : hits) {
                        String c = h.getContent();
                        if (c != null && !c.isBlank()) kbSnippets.add(c);
                    }
                }
            } catch (Exception ignored) { /* RAG 不可用时降级 */ }

            // 2) 计划上下文
            String planTitle = null;
            List<String> planTaskTitles = null;
            if (planId != null) {
                org.example.entity.StudyPlan plan = planService.getPlan(planId);
                if (plan != null) {
                    planTitle = plan.getTitle();
                    List<org.example.entity.PlanTask> tasks = planService.getTasks(planId);
                    if (tasks != null) {
                        planTaskTitles = new ArrayList<>();
                        for (org.example.entity.PlanTask t : tasks) {
                            if (t.getTitle() != null && !t.getTitle().isBlank()) planTaskTitles.add(t.getTitle());
                        }
                    }
                }
            }

            if (planTitle != null) {
                user.append("【所属计划】").append(planTitle).append("\n");
            }
            if (planTaskTitles != null && !planTaskTitles.isEmpty()) {
                user.append("【本计划关键任务】\n");
                int n = 0;
                for (String s : planTaskTitles) {
                    user.append("- ").append(s.length() > 80 ? s.substring(0, 80) : s).append("\n");
                    if (++n >= 12) break;
                }
            }
            if (chapter != null && !chapter.isBlank()) {
                user.append("【指定章节】").append(chapter).append("\n");
            }
            if (kbSnippets != null && !kbSnippets.isEmpty()) {
                user.append("\n【参考资料片段（来自知识库 top-3）】\n");
                int n = 0;
                for (String s : kbSnippets) {
                    user.append("• ").append(s.length() > 220 ? s.substring(0, 220) + "..." : s).append("\n");
                    if (++n >= 6) break;
                }
            }
            user.append("\n【出题主题】").append(topic != null ? topic : (chapter != null ? chapter : "通用")).append("\n");
            user.append("围绕上述主题出 ").append(count).append(" 道题；每题 chapter 字段填 ").append(chapter != null ? chapter : "（与任务章节对应的章节名）").append("。");

            String raw = chatModelFactory.complete(sys.toString(), user.toString(), 0.6, 8000);
            String parsed = sanitizeArrayForController(raw, topic, chapter);
            // 服务层返回的是 JSON 字符串；controller 会再次解析
            return parsed;
        } catch (Exception e) {
            logger.warn("出题失败: {}", e.getMessage());
            return "[]";
        }
    }

    /** 兼容旧调用：仅 topic + count */
    public String generateQuiz(String topic, int count) {
        return generateQuiz(topic, count, null, null);
    }

    /** 把 LLM 输出包成稳定 JSON 数组字符串供控制器解析 */
    private String sanitizeArrayForController(String raw, String topic, String chapter) {
        List<Map<String, Object>> arr = JsonExtractor.parseTopLevelArray(raw);
        if (arr.isEmpty()) return "[]";
        // 补全 answer 文本匹配 / chapter 默认值
        List<Map<String, Object>> ok = new ArrayList<>();
        for (Map<String, Object> q : arr) {
            Object qs = q.get("question");
            if (qs == null || String.valueOf(qs).isBlank()) continue;
            Object opts = q.get("options");
            if (!(opts instanceof List) || ((List<?>) opts).size() < 2) continue;
            String ans = String.valueOf(q.get("answer"));
            final List<?> optsList = (List<?>) opts;
            final String ans0 = ans;
            // 兜底：answer 完整文本没有匹配到 options 时，尝试取字母 A/B/C/D 自动对应
            boolean needFallback = optsList.stream().map(String::valueOf).noneMatch(s -> s.equals(ans0));
            if (needFallback && ans0.length() == 1) {
                char c = Character.toUpperCase(ans0.charAt(0));
                int idx = c - 'A';
                if (idx >= 0 && idx < optsList.size()) {
                    ans = String.valueOf(optsList.get(idx));
                    q.put("answer", ans);
                }
            }
            if (q.get("chapter") == null || String.valueOf(q.get("chapter")).isBlank()) {
                q.put("chapter", chapter != null ? chapter : (topic != null ? topic : "通用"));
            }
            ok.add(q);
        }
        // 自动补足数量：LLM 输出稀疏时，复制第一条的形态补到 count 条
        try {
            int target = 0;
            try { target = Integer.parseInt(String.valueOf(arr.size() == 0 ? 0 : Math.min(arr.size(), 5))); }
            catch (Exception ignored) {}
            if (target > 0 && ok.size() < target) {
                Map<String, Object> first = ok.get(0);
                while (ok.size() < target) {
                    ok.add(new LinkedHashMap<>(first));
                }
            }
        } catch (Exception ignored) {}
        try {
            return MAPPER.writeValueAsString(ok);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 批改用户作答，返回 JSON：{correct:bool, comment:string} */
    public String grade(String question, String userAnswer, String reference) {
        try {
            String system = "你是阅卷老师。给定题目、参考答案与学生作答，判断正误并给出简短点评。" +
                    "只输出 JSON: {\"correct\":true/false,\"comment\":\"点评\"}。不要其他文字。";
            String user = "题目：" + question + "\n参考答案：" + reference + "\n学生作答：" + userAnswer;
            String raw = chatModelFactory.complete(system, user, 0.2, 800);
            Map<String, Object> obj = JsonExtractor.parseTopLevelObject(raw);
            return "{\"correct\":" + obj.getOrDefault("correct", false) +
                    ",\"comment\":" + MAPPER.writeValueAsString(obj.getOrDefault("comment", "")) + "}";
        } catch (Exception e) {
            return "{\"correct\":false,\"comment\":\"批改失败：" + e.getMessage() + "\"}";
        }
    }

    /** 批改并记录薄弱点（错误则写入 weak_area） */
    public String gradeAndRecord(Long planId, String chapter, String question, String userAnswer, String reference) {
        String result = grade(question, userAnswer, reference);
        try {
            JsonNode node = MAPPER.readTree(result);
            if (!node.path("correct").asBoolean(false) && planId != null) {
                int severity = 4;
                jdbc.update(
                        "INSERT INTO weak_area(plan_id,subject,chapter,reason,severity,suggested_action,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                        planId, null, chapter, "测验作答错误", severity, "针对性加练该题知识点",
                        LocalDateTime.now().toString(), LocalDateTime.now().toString());
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * 批量批改（前端交卷模式）：本地比对 answer 判定正误，答错则按 chapter 记薄弱点。
     * 返回 JSON：{"score":正确数,"total":总数,"weakTopics":["章节1",...],"items":[{question,userAnswer,correctAnswer,correct}]}
     */
    public String gradeBatch(List<AnswerEntry> answers, Long planId, String chapter) {
        try {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            int score = 0;
            java.util.LinkedHashSet<String> weak = new java.util.LinkedHashSet<>();
            for (AnswerEntry a : answers) {
                boolean correct = a.correctAnswer != null && a.correctAnswer.equals(a.userAnswer);
                if (correct) score++;
                else weak.add(chapter != null && !chapter.isBlank() ? chapter : "综合");
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("question", a.question);
                m.put("userAnswer", a.userAnswer);
                m.put("correctAnswer", a.correctAnswer);
                m.put("correct", correct);
                items.add(m);
            }
            // 有错题且带 planId → 记录薄弱点
            if (planId != null && !weak.isEmpty()) {
                jdbc.update(
                        "INSERT INTO weak_area(plan_id,subject,chapter,reason,severity,suggested_action,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                        planId, null, String.join(", ", weak), "测验作答错误", 4, "针对性加练该题知识点",
                        LocalDateTime.now().toString(), LocalDateTime.now().toString());
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("score", score);
            out.put("total", answers.size());
            out.put("weakTopics", weak.isEmpty() ? java.util.List.of() : new java.util.ArrayList<>(weak));
            out.put("items", items);
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            logger.warn("批量批改失败: {}", e.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 前端交卷题项 */
    @lombok.Data
    public static class AnswerEntry {
        public String question;
        public String userAnswer;
        public String correctAnswer;
    }
}
