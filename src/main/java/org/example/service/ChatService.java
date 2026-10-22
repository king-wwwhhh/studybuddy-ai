package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.Builder;
import org.example.agent.Agent;
import org.example.agent.AgentRegistry;
import org.example.agent.SupervisorAgent;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.HistoryRecallTool;
import org.example.agent.tool.InternalDocsTools;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装多 Agent 对话的公共逻辑：模型创建、系统提示词构建、Agent 路由、ReactAgent 构建与执行。
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired(required = false)  // MCP 未启用时为 null，对话仅使用方法工具
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}")
    private String dashScopeBaseUrl;

    // ===== 多 Agent 编排依赖 =====
    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private SupervisorAgent supervisorAgent;

    /** 历史召回工具：窗口外的旧对话只能靠它回查 */
    @Autowired
    private HistoryRecallTool historyRecallTool;
    @Autowired
    private ChatModelFactory chatModelFactory;
    @Autowired
    private MemoryService memoryService;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .baseUrl(dashScopeBaseUrl)
                .build();
    }

    /**
     * 创建 ChatModel。
     * 模型名不再用 SDK 的 DEFAULT_MODEL_NAME 常量（那是写死的），统一取模型目录的探测结果。
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(chatModelFactory.getDefaultModel())
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /** 标准对话模型（保留旧接口兼容） */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 8192, 0.9);
    }

    /** 本次请求的路由提示（显式选择与意图冲突时的软性提醒），由 buildAgentSystemPrompt 消费后清理 */
    private static final ThreadLocal<String> ROUTE_HINT = new ThreadLocal<>();

    /** 主管路由：解析最终应答 Agent 类型（无历史，兼容旧调用） */
    public String resolveAgentType(String override, String question) {
        return resolveAgentType(override, question, null);
    }

    /**
     * 主管路由：解析最终应答 Agent 类型。
     * 把最近两轮用户问题交给决策器做指代消歧，并把「显式选择 vs 实际意图」的冲突转成提示词里的软性提醒。
     */
    public String resolveAgentType(String override, String question, List<Map<String, String>> history) {
        SupervisorAgent.RouteDecision decision =
                supervisorAgent.decideDetailed(override, question, recentUserTurns(history));
        logger.info("路由决策: agent={} 来源={} 分数[{}] 证据[{}] 冲突={}",
                decision.agent, decision.source, decision.scores, decision.evidence, decision.conflictAgent);
        ROUTE_HINT.set(buildConflictHint(decision));
        return decision.agent;
    }

    /** 最近两轮用户提问，最近的在前（供指代消歧使用） */
    private static List<String> recentUserTurns(List<Map<String, String>> history) {
        List<String> turns = new ArrayList<>();
        if (history == null) {
            return turns;
        }
        for (int i = history.size() - 1; i >= 0 && turns.size() < 2; i--) {
            Map<String, String> m = history.get(i);
            if (m == null || !"user".equals(m.get("role"))) {
                continue;
            }
            String content = m.get("content");
            if (content != null && !content.isBlank()) {
                turns.add(content);
            }
        }
        return turns;
    }

    /**
     * 显式选择与问题意图冲突时：不改派用户的选择，但让模型知道这件事，
     * 不属于本角色职责就一句话说明并建议切换，而不是硬套本角色流程。
     */
    private static String buildConflictHint(SupervisorAgent.RouteDecision decision) {
        if (decision.conflictAgent == null) {
            return null;
        }
        return "【路由提示】用户本次显式选择的是「" + decision.agent + "」，但问题内容更像「"
                + decision.conflictAgent + "」类请求（证据：" + decision.evidence + "）。"
                + "保持当前角色作答即可；若这个问题确实不属于你的职责，请在回答开头用一句话说明并建议用户"
                + "切换到对应角色，不要硬套本角色流程，也不要编造不属于本角色的结果。";
    }

    /**
     * 执行一轮多 Agent 对话，返回答案与命中的 Agent。
     */
    public RouteResult answer(String question, String agentType, List<Map<String, String>> history) {
        return answer(question, agentType, history, null);
    }

    /**
     * 执行一轮多 Agent 对话，返回答案与命中的 Agent。
     * @param model 指定对话模型（为空则使用后端默认）。ext: 规格走 ExternalChatModel，且不注入方法工具。
     */
    public RouteResult answer(String question, String agentType, List<Map<String, String>> history, String model) {
        return answer(question, agentType, history, model, null);
    }

    /**
     * 同 {@link #answer(String, String, List, String)}，额外带 sessionId：
     * 系统提示里会写入它，模型才能在"看不到更早对话"时调用 `recallHistory` 回查。
     */
    public RouteResult answer(String question, String agentType, List<Map<String, String>> history,
                              String model, String sessionId) {
        Agent agent = agentRegistry.get(agentType);
        String system = buildAgentSystemPrompt(agent, history, sessionId);
        boolean external = isExternalModel(model);
        if (external) {
            system = system + "\n\n[环境提示] 当前会话选用的是外部 API，仅支持普通对话，" +
                    "不会执行知识库检索、计划、打卡等工具调用。请基于模型自身能力作答。";
        }
        ChatModel chatModel = chatModelFactory.createAny(model, agent.getTemperature(), 8192, 0.9);
        Builder b = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel)
                .systemPrompt(system);
        if (!external) {
            b.methodTools(methodToolsFor(agent))
                    .tools(getToolCallbacks());
        }
        String fullAnswer = executeChat(b.build(), question);
        return new RouteResult(fullAnswer, agent.getType(), agent.getName());
    }

    /**
     * 构建用于流式对话的 ReactAgent（由控制器流式消费）。
     */
    public ReactAgent buildAgent(String agentType, List<Map<String, String>> history) {
        return buildAgent(agentType, history, null);
    }

    /**
     * 构建用于流式对话的 ReactAgent（由控制器流式消费）。
     * <p>注意：当 model 是 ext: 规格时（外部 OpenAI 兼容模型），跳过 methodTools / tools，
     * 并在系统提示词里加一句"外部 API 仅支持对话，不检索知识库"，避免模型误调工具。</p>
     */
    public ReactAgent buildAgent(String agentType, List<Map<String, String>> history, String model) {
        return buildAgent(agentType, history, model, null);
    }

    /**
     * 构建流式对话的 ReactAgent，并带上 sessionId（供 `recallHistory` 回查更早的对话）。
     */
    public ReactAgent buildAgent(String agentType, List<Map<String, String>> history, String model, String sessionId) {
        Agent agent = agentRegistry.get(agentType);
        String system = buildAgentSystemPrompt(agent, history, sessionId);
        boolean external = isExternalModel(model);
        if (external) {
            system = system + "\n\n[环境提示] 当前会话选用的是外部 API，仅支持普通对话，" +
                    "不会执行知识库检索、计划、打卡等工具调用。请基于模型自身能力作答。";
        }
        ChatModel chatModel = chatModelFactory.createAny(model, agent.getTemperature(), 8192, 0.9);
        Builder b = ReactAgent.builder()
                .name(agent.getName())
                .model(chatModel)
                .systemPrompt(system);
        if (!external) {
            b.methodTools(methodToolsFor(agent))
                    .tools(getToolCallbacks());
        }
        return b.build();
    }

    /** Agent 自带工具 + 全局的「历史召回」工具：任何角色都可能被问到窗口外的旧对话 */
    private Object[] methodToolsFor(Agent agent) {
        Object[] base = agent.getMethodTools();
        Object[] merged = java.util.Arrays.copyOf(base, base.length + 1);
        merged[base.length] = historyRecallTool;
        return merged;
    }

    private static boolean isExternalModel(String model) {
        return model != null && model.startsWith("ext:");
    }

    /** 组合 Agent 系统提示词 + 近期历史 */
    public String buildAgentSystemPrompt(Agent agent, List<Map<String, String>> history) {
        return buildAgentSystemPrompt(agent, history, null);
    }

    /**
     * 组合 Agent 系统提示词 + 近期历史 + 会话记忆提示。
     *
     * @param sessionId 当前会话 id；非空时告诉模型「窗口外的对话可以调 recallHistory 回查」，
     *                  这是灰区/超窗内容的唯一补偿通路（否则模型只能答"没提过"或编造）。
     */
    public String buildAgentSystemPrompt(Agent agent, List<Map<String, String>> history, String sessionId) {
        StringBuilder sb = new StringBuilder(agent.getSystemPrompt());
        String routeHint = ROUTE_HINT.get();
        if (routeHint != null) {
            sb.append("\n\n").append(routeHint);
            ROUTE_HINT.remove(); // 一次请求只消费一次，避免线程复用时串味
        }
        if (history != null && !history.isEmpty()) {
            sb.append("\n\n--- 对话历史 ---\n");
            for (Map<String, String> m : history) {
                String role = m.get("role");
                String content = m.get("content");
                if (content == null) continue;
                if ("system".equals(role)) {
                    sb.append(content).append("\n");
                } else if ("user".equals(role)) {
                    sb.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    sb.append("助手: ").append(content).append("\n");
                }
            }
            sb.append("--- 对话历史结束 ---\n");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sb.append("\n\n【会话记忆】sessionId=").append(sessionId).append('\n')
                    .append("你能直接看到的只有上面「对话历史」里的最近几轮（更早的消息可能已被移出窗口或压成摘要）。")
                    .append("用户若提到「刚才/之前/我们说过/那个约束/你还记得吗」而你在历史里看不到，")
                    .append("**必须先调用 recallHistory(sessionId=\"").append(sessionId)
                    .append("\", query=相关关键词)** 回查本会话更早的真实消息，再据实回答；")
                    .append("若召回为空，如实说明你看不到那段对话，绝不编造。");
        }
        return sb.toString();
    }

    /** 持久化一轮对话到短期记忆（SQLite） */
    public void persistTurn(String sessionId, String agentType, String question, String answer) {
        try {
            memoryService.ensureSession(sessionId, agentType,
                    question != null && question.length() > 30 ? question.substring(0, 30) : question);
            memoryService.saveMessage(sessionId, "user", question);
            memoryService.saveMessage(sessionId, "assistant", answer);
        } catch (Exception e) {
            logger.warn("记忆持久化失败(不影响应答): {}", e.getMessage());
        }
    }

    /**
     * 构建系统提示词（兼容旧逻辑，已不再用于路由，保留以防调用）
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildAgentSystemPrompt(agentRegistry.get("chat"), history);
    }

    public Object[] buildMethodToolsArray() {
        return new Object[]{dateTimeTools, internalDocsTools};
    }

    /** 获取 MCP 工具回调（未启用时为空） */
    public ToolCallback[] getToolCallbacks() {
        return tools != null ? tools.getToolCallbacks() : new ToolCallback[0];
    }

    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /** 兼容旧接口：创建 ReactAgent（默认通用 Agent） */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /** 执行 ReactAgent 对话（非流式） */
    public String executeChat(ReactAgent agent, String question) {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        try {
            var response = agent.call(question);
            String answer = response.getText();
            logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
            return answer;
        } catch (Exception e) {
            throw new RuntimeException("ReactAgent 执行失败: " + e.getMessage(), e);
        }
    }

    /** 多 Agent 路由结果 */
    public static class RouteResult {
        public final String answer;
        public final String agentType;
        public final String agentName;
        public RouteResult(String answer, String agentType, String agentName) {
            this.answer = answer;
            this.agentType = agentType;
            this.agentName = agentName;
        }
    }
}
