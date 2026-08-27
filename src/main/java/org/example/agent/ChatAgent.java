package org.example.agent;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.KnowledgeSearchTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通用对话 Agent：兜底闲聊、通用问答，必要时检索知识库。
 */
@Component
public class ChatAgent implements Agent {

    private final DateTimeTools dateTimeTools;
    private final KnowledgeSearchTool knowledgeSearchTool;

    @Autowired
    public ChatAgent(DateTimeTools dateTimeTools, KnowledgeSearchTool knowledgeSearchTool) {
        this.dateTimeTools = dateTimeTools;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    @Override public String getType() { return "chat"; }
    @Override public String getName() { return "智能学伴"; }
    @Override public String getDescription() { return "通用闲聊与问答，必要时检索知识库。"; }
    @Override public double getTemperature() { return 0.8; }

    @Override
    public String getSystemPrompt() {
        return "你是「期末速成助手」的通用学伴，语气友好、鼓励。可以回答学习相关或日常问题。" +
                "需要查资料时调用 knowledgeSearch；需要当前时间时调用 getCurrentDateTime。回答要简洁有用。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[]{dateTimeTools, knowledgeSearchTool};
    }
}
