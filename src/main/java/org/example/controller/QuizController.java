package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.service.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 测验接口：出题 / 批改（批改错误自动记录薄弱点到 weak_area）。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    @Autowired
    private QuizService quizService;

    private static <T> ResponseEntity<ApiResponse<T>> ok(ApiResponse<T> r) {
        return ResponseEntity.ok(r);
    }

    /** 根据主题生成若干道选择题（返回结构化 {questions:[...]}）。
     * planId + chapter 都给时，会拉「该计划任务标题 + 知识库 top-3」作为上下文出题。 */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(@RequestBody QuizGenerateReq req) {
        if ((req.topic == null || req.topic.isBlank()) && (req.chapter == null || req.chapter.isBlank())) {
            return ok(ApiResponse.error("topic 或 chapter 至少一个不能为空"));
        }
        int count = req.count != null && req.count > 0 ? req.count : 5;
        String usedTopic = (req.topic != null && !req.topic.isBlank()) ? req.topic : req.chapter;
        String usedChapter = (req.chapter != null && !req.chapter.isBlank()) ? req.chapter : req.topic;
        String raw = quizService.generateQuiz(usedTopic, count, req.planId, usedChapter);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            Object arr = m.readValue(raw, Object.class);
            out.put("questions", arr instanceof java.util.List ? arr : java.util.List.of());
        } catch (Exception e) {
            out.put("questions", List.of());
            out.put("raw", raw);
            out.put("parseError", e.getMessage());
        }
        out.put("topic", usedTopic);
        out.put("planId", req.planId);
        out.put("chapter", usedChapter);
        return ok(ApiResponse.success(out));
    }

    /** 批改用户作答；若提供 planId + chapter 则自动记录薄弱点 */
    @PostMapping("/grade")
    public ResponseEntity<?> grade(@RequestBody QuizGradeReq req) {
        if (req.question == null && req.answers == null) {
            return ok(ApiResponse.error("question 或 answers 不能为空"));
        }
        // 前端批量交卷：{answers:[{question,userAnswer,correctAnswer}], planId, chapter}
        if (req.answers != null && !req.answers.isEmpty()) {
            List<org.example.service.QuizService.AnswerEntry> entries = new java.util.ArrayList<>();
            for (AnswerEntry a : req.answers) {
                org.example.service.QuizService.AnswerEntry e = new org.example.service.QuizService.AnswerEntry();
                e.question = a.question;
                e.userAnswer = a.userAnswer;
                e.correctAnswer = a.correctAnswer;
                entries.add(e);
            }
            String resultStr = quizService.gradeBatch(entries, req.planId, req.chapter);
            // gradeBatch 返回 JSON 字符串，解析为对象返回（前端直接取 weakTopics/score/total）
            Object payload;
            try {
                payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resultStr, Object.class);
            } catch (Exception ex) {
                payload = Map.of("raw", resultStr);
            }
            return ResponseEntity.ok(ApiResponse.success(payload));
        }
        String result;
        if (req.planId != null && req.chapter != null) {
            result = quizService.gradeAndRecord(req.planId, req.chapter,
                    req.question, req.userAnswer, req.reference);
        } else {
            result = quizService.grade(req.question, req.userAnswer, req.reference);
        }
        return ok(ApiResponse.success(result));
    }

    @lombok.Data
    public static class QuizGenerateReq {
        public String topic;
        public Integer count;
        /** 可选：关联计划 id，用于生成后把错题归入薄弱点 */
        public Long planId;
        /** 可选：所属章节，与 planId 一起用于薄弱点记录 */
        public String chapter;
    }

    @lombok.Data
    public static class QuizGradeReq {
        public Long planId;
        public String chapter;
        public String question;
        public String userAnswer;
        public String reference;
        /** 批量交卷：前端一次提交多道题的作答 */
        public List<AnswerEntry> answers;
    }

    @lombok.Data
    public static class AnswerEntry {
        public String question;
        public String userAnswer;
        public String correctAnswer;
    }
}
