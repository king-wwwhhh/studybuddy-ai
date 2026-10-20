package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.example.agent.model.ExternalChatModel;
import org.example.service.ModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 统一对话模型工厂。
 * 支持按模型名动态构建并缓存 DashScope 模型，供前端「切换模型」使用。
 */
@Service
public class ChatModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelFactory.class);

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    /**
     * 模型目录：负责发现「当前真正可用」的模型名，并在请求的模型不存在时自动降级。
     * 这样阿里云改名/下线模型时不需要改代码，只需重启（或等 24h 自动刷新）。
     */
    @Autowired
    private ModelCatalogService modelCatalog;

    /**
     * DashScope 基础地址。默认走阿里云公开网关；
     * 若使用企业/专属 MaaS 空间（CSV 中的 dashScope 字段），可在此覆盖。
     */
    @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}")
    private String baseUrl;

    /** 按模型名缓存已构建的 ChatModel，避免重复构建 */
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public DashScopeApi createApi() {
        return DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 模型规格识别。
     * <ul>
     *   <li>"ext:baseUrl|apiKey|modelId" → 外部 OpenAI-兼容模型</li>
     *   <li>其余（qwen3.7-max 等）→ DashScope 内置模型</li>
     *   <li>null/空白 → 默认模型（DashScope）</li>
     * </ul>
     * 这样外部模型也能进入多 Agent（SupervisorAgent 路由 + ReactAgent 工具循环）。
     */
    public ChatModel createAny(String modelSpec, double temperature, int maxToken, double topP) {
        if (modelSpec != null && modelSpec.startsWith("ext:")) {
            // ext:<baseUrl>|<apiKey>|<modelId>
            String payload = modelSpec.substring(4);
            String[] parts = payload.split("\\|", 3);
            if (parts.length < 3 || parts[0].isBlank() || parts[2].isBlank()) {
                throw new IllegalArgumentException("外部模型规格不合法，应为 ext:baseUrl|apiKey|modelId");
            }
            return createExternal(parts[0].trim(), parts[1], parts[2].trim(), temperature, maxToken, topP);
        }
        return create(modelSpec, temperature, maxToken, topP);
    }

    /**
     * 动态模型创建（前端可指定模型名）。
     * model 为空或空白时回落到默认模型；若指定的模型当前不可用（阿里云改名/下线），
     * 自动降级到可用模型，避免 404 Model not exist。按模型名缓存。
     */
    public DashScopeChatModel create(String model, double temperature, int maxToken, double topP) {
        String modelName = modelCatalog.nearestUsable(model);
        if (model != null && !model.isBlank() && !modelName.equals(model.trim())) {
            logger.warn("模型 {} 当前不可用，已自动降级为 {}", model, modelName);
        }
        // 缓存键必须包含 temperature/maxToken/topP：这些参数固化在 defaultOptions 里，
        // 否则先被小 maxToken 调用（如 Supervisor 路由的 256）构建的实例会污染后续主对话。
        String cacheKey = "dash:" + modelName + "|" + temperature + "|" + maxToken + "|" + topP;
        ChatModel cached = modelCache.get(cacheKey);
        if (cached instanceof DashScopeChatModel) return (DashScopeChatModel) cached;
        DashScopeChatModel built = DashScopeChatModel.builder()
                .dashScopeApi(createApi())
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(modelName)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
        modelCache.put(cacheKey, built);
        return built;
    }

    /**
     * 创建外部 OpenAI-兼容 ChatModel（如 DeepSeek / Moonshot / 自建网关）。
     * 缓存按 baseUrl+modelName 复用。ReactAgent 调用时会把 ToolCallback[] 通过
     * ToolCallingChatOptions 传进来；ExternalChatModel 内部将其渲染为 OpenAI tool_calls 协议。
     */
    public ExternalChatModel createExternal(String baseUrl, String apiKey, String modelId,
                                            double temperature, int maxToken, double topP) {
        String cacheKey = "ext:" + baseUrl + "|" + modelId + "|" + temperature + "|" + maxToken + "|" + topP;
        ChatModel cached = modelCache.get(cacheKey);
        if (cached instanceof ExternalChatModel) return (ExternalChatModel) cached;
        ExternalChatModel built = ExternalChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelId)
                .temperature(temperature)
                .maxTokens(maxToken > 0 ? maxToken : null)
                .topP(topP > 0 ? topP : null)
                .timeoutSec(120)
                .build();
        modelCache.put(cacheKey, built);
        return built;
    }

    /** 默认模型创建（保持旧接口，供内部调用） */
    public DashScopeChatModel create(double temperature, int maxToken, double topP) {
        return create(null, temperature, maxToken, topP);
    }

    /** 当前生效的默认模型（由模型目录探测得出，不写死） */
    public String getDefaultModel() {
        modelCatalog.ensureFresh();
        return modelCatalog.getDefaultModel();
    }

    /**
     * 一次性同步补全（供 Supervisor 路由、测验生成、打卡摘要压缩等内部调用）。
     * 默认使用默认模型。
     */
    public String complete(String system, String user, double temperature, int maxToken) {
        return complete(null, system, user, temperature, maxToken);
    }

    /** 一次性同步补全（可指定模型或外部模型规格 ext:baseUrl|apiKey|modelId） */
    public String complete(String model, String system, String user, double temperature, int maxToken) {
        ChatModel chatModel = createAny(model, temperature, maxToken, 0.9);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(user)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
