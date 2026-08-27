package org.example.agent.tool;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.QuizService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测验工具（Function Calling）。QuizAgent 出题、批改，并在答错时记录薄弱点。
 */
@Component
public class QuizTool {

    private static final Logger logger = LoggerFactory.getLogger(QuizTool.class);
    public static final String TOOL_NAME = "quizTool";

    private final QuizService quizService;
    private final ObjectMapper mapper = Mappers.JSON;

    @Autowired
    public QuizTool(QuizService quizService) {
        this.quizService = quizService;
    }

    @Tool(description = "Generate N quiz questions on a topic from study materials. Returns JSON array of {question, answer, chapter}.")
    public String generateQuiz(@ToolParam(description = "Topic to test") String topic,
                               @ToolParam(description = "Number of questions") int count) {
        try {
            return quizService.generateQuiz(topic, count);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Grade a student's answer against the reference. Returns {correct:bool, comment:string}.")
    public String grade(@ToolParam(description = "The question") String question,
                        @ToolParam(description = "Student's answer") String userAnswer,
                        @ToolParam(description = "Reference answer") String reference) {
        try {
            return quizService.grade(question, userAnswer, reference);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Grade and, if wrong, record a weak area for the plan. planId and chapter help build the weak-area heatmap.")
    public String gradeAndRecord(@ToolParam(description = "Plan id (nullable)") Long planId,
                                 @ToolParam(description = "Chapter / topic of the question") String chapter,
                                 @ToolParam(description = "The question") String question,
                                 @ToolParam(description = "Student's answer") String userAnswer,
                                 @ToolParam(description = "Reference answer") String reference) {
        try {
            return quizService.gradeAndRecord(planId, chapter, question, userAnswer, reference);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
