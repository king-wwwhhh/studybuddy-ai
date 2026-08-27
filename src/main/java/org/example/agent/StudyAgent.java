package org.example.agent;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.KnowledgeSearchTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 答疑导师 Agent：基于知识库讲解知识点、解题。
 */
@Component
public class StudyAgent implements Agent {

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final DateTimeTools dateTimeTools;

    @Autowired
    public StudyAgent(KnowledgeSearchTool knowledgeSearchTool, DateTimeTools dateTimeTools) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.dateTimeTools = dateTimeTools;
    }

    @Override public String getType() { return "study"; }
    @Override public String getName() { return "答疑导师"; }
    @Override public String getDescription() { return "讲解知识点、拆解题目、答疑解惑（基于已上传资料）。"; }
    @Override public double getTemperature() { return 0.5; }

    @Override
    public String getSystemPrompt() {
        return "你是「答疑导师」，负责把知识点讲清楚。优先调用 knowledgeSearch 检索学员上传的笔记/教材/真题，" +
                "基于检索内容给出准确、循序渐进的讲解，必要时举例、对比、画图描述。若资料不足，坦诚说明并给出通用思路。" +
                "需要时间时调用 getCurrentDateTime。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[]{knowledgeSearchTool, dateTimeTools};
    }
}
