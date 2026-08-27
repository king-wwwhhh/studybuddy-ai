package org.example.agent;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ChatModelFactory;
import org.example.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 主管 Agent（路由决策器）。
 * 这是「多 Agent 编排」的入口：根据用户意图选择最合适的学习 Agent。
 * 决策策略：关键词启发式优先（零延迟、零成本、确定性强），未命中时降级为轻量 LLM 意图分类。
 */
@Component
public class SupervisorAgent implements Agent {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgent.class);
    private static final ObjectMapper MAPPER = Mappers.JSON;

    private final ChatModelFactory chatModelFactory;

    @Autowired
    public SupervisorAgent(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /** Agent 类型 id（自身是路由者，不参与最终应答） */
    @Override
    public String getType() {
        return "supervisor";
    }

    @Override
    public String getName() {
        return "主管路由";
    }

    @Override
    public String getDescription() {
        return "根据用户意图把请求分发给最合适的学习 Agent（规划/答疑/测验/教练/闲聊）。";
    }

    @Override
    public String getSystemPrompt() {
        return "你是一个意图分类器。根据用户输入，从 {chat, study, quiz, planner, coach} 中选一个最匹配的标签。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[0];
    }

    // 关键词 → Agent 类型 的启发式映射（按优先级排列）
    private static final java.util.Map<Pattern, String> KEYWORD_RULES = new java.util.LinkedHashMap<>();

    static {
        KEYWORD_RULES.put(Pattern.compile("计划|规划|安排|时间表|复习计划|考前|倒计|冲刺安排|每天学"), "planner");
        KEYWORD_RULES.put(Pattern.compile("出题|测验|做题|模考|练习|考题|刷题|考考我|自测"), "quiz");
        KEYWORD_RULES.put(Pattern.compile("打卡|今天学|进度|监督|拖延|复习得|学了多久|完成情况"), "coach");
        KEYWORD_RULES.put(Pattern.compile("解释|讲解|什么意思|怎么理解|这道题|知识点|答疑|概念|公式|为什么"), "study");
    }

    /**
     * 决策最终应答 Agent 的类型 id。
     * @param override 前端显式指定的 Agent（非 null 且非 supervisor 时直接采纳）
     * @param question 当前问题
     * @param history  近期历史消息（用于消歧，暂仅作上下文）
     */
    public String decide(String override, String question, List<ChatMessage> history) {
        if (override != null && !override.isBlank() && !"supervisor".equals(override)) {
            logger.info("显式指定 Agent: {}", override);
            return override;
        }

        // 1) 关键词启发式
        if (question != null) {
            for (java.util.Map.Entry<Pattern, String> rule : KEYWORD_RULES.entrySet()) {
                if (rule.getKey().matcher(question).find()) {
                    logger.info("关键词路由命中: {} -> {}", rule.getKey(), rule.getValue());
                    return rule.getValue();
                }
            }
        }

        // 2) 降级为 LLM 意图分类
        try {
            String system = "你是意图分类器。只从以下标签中选一个最匹配用户请求的：\n" +
                    "chat=闲聊/通用问答；study=知识点答疑讲解；quiz=出题测验自测；" +
                    "planner=制定学习计划/时间安排；coach=学习进度监督/每日打卡/鼓励。\n" +
                    "只输出一个 JSON：{\"agent\":\"标签\"}，不要任何额外文字。";
            String user = "用户请求：" + question;
            String raw = chatModelFactory.complete(system, user, 0.0, 256).trim();
            JsonNode node = MAPPER.readTree(raw.replaceAll("(?s)^[^{]*", "").replaceAll("(?s)}.*$", "}"));
            String agent = node.path("agent").asText("");
            if (List.of("chat", "study", "quiz", "planner", "coach").contains(agent)) {
                logger.info("LLM 路由: {}", agent);
                return agent;
            }
        } catch (Exception e) {
            logger.warn("LLM 路由失败，回退 chat: {}", e.getMessage());
        }
        return "chat";
    }

    @Override
    public double getTemperature() {
        return 0.0;
    }
}
