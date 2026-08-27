package org.example.agent;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.KnowledgeSearchTool;
import org.example.agent.tool.QuizTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 出题考官 Agent：基于资料出题、批改、标记薄弱点。
 */
@Component
public class QuizAgent implements Agent {

    private final QuizTool quizTool;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final DateTimeTools dateTimeTools;

    @Autowired
    public QuizAgent(QuizTool quizTool, KnowledgeSearchTool knowledgeSearchTool, DateTimeTools dateTimeTools) {
        this.quizTool = quizTool;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.dateTimeTools = dateTimeTools;
    }

    @Override public String getType() { return "quiz"; }
    @Override public String getName() { return "出题考官"; }
    @Override public String getDescription() { return "根据主题或资料出题、批改作答、标记薄弱章节。"; }
    @Override public double getTemperature() { return 0.6; }

    @Override
    public String getSystemPrompt() {
        return "你是「出题考官」。先调用 knowledgeSearch 或依据学员指定主题，再用 generateQuiz 出 " +
                "检验理解的题目；学员作答后，用 grade / gradeAndRecord 批改并（答错时）记录薄弱点。" +
                "出题意图是帮助学员查漏补缺，而非刁难。需要时间时调用 getCurrentDateTime。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[]{quizTool, knowledgeSearchTool, dateTimeTools};
    }
}
