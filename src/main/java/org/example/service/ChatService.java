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

    /** 主管路由：解析最终应答 Agent 类型 */
    public String resolveAgentType(String override, String question) {
        return supervisorAgent.decide(override, question, null);
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
        Agent agent = agentRegistry.get(agentType);
        String system = buildAgentSystemPrompt(agent, history);
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
            b.methodTools(agent.getMethodTools())
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
        Agent agent = agentRegistry.get(agentType);
        String system = buildAgentSystemPrompt(agent, history);
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
            b.methodTools(agent.getMethodTools())
                    .tools(getToolCallbacks());
        }
        return b.build();
    }

    private static boolean isExternalModel(String model) {
        return model != null && model.startsWith("ext:");
    }

    /** 组合 Agent 系统提示词 + 近期历史 */
    public String buildAgentSystemPrompt(Agent agent, List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder(agent.getSystemPrompt());
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
