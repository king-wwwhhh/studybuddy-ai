package org.example.agent.model;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 外部 OpenAI 兼容 ChatModel（仅对话，无工具调用）。
 *
 * <p>设计取舍：通用外部 API（DeepSeek / OpenAI 兼容网关）的 function calling
 * 协议千差万别；做完整协议适配成本太高，且外部 API 默认不做知识库检索（也不该做）。
 * 因此本模型只承担"按角色转发对话"的责任；用户已经明确认可
 * "外部 API 仅支持对话，不检索知识库"。</p>
 *
 * <p>{@link ChatService#buildAgent} 检测到 spec 是 {@code ext:} 开头时，
 * 不再注入 methodTools / tools，调用本模型前会在系统提示词里加一句提示。</p>
 */
public class ExternalChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(ExternalChatModel.class);
    private static final ObjectMapper MAPPER = Mappers.JSON;

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final HttpClient http;
    private final int timeoutSec;

    private ExternalChatModel(Builder b) {
        this.baseUrl = stripSlash(b.baseUrl);
        if (b.apiKey == null || b.apiKey.isBlank()) {
            throw new IllegalArgumentException("ExternalChatModel: apiKey is required");
        }
        if (b.modelName == null || b.modelName.isBlank()) {
            throw new IllegalArgumentException("ExternalChatModel: modelName is required");
        }
        this.apiKey = b.apiKey;
        this.modelName = b.modelName;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.topP = b.topP;
        this.timeoutSec = b.timeoutSec;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(20, Math.max(5, b.timeoutSec / 2))))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public String getModelName() { return modelName; }
    public String getBaseUrl() { return baseUrl; }

    // ----------------- ChatModel -----------------

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String body = renderRequest(prompt, false);
            HttpResponse<String> resp = doPost(body);
            int code = resp.statusCode();
            String txt = resp.body();
            if (code / 100 != 2) {
                throw new RuntimeException("External model HTTP " + code + ": " + trim(txt, 280));
            }
            Map<String, Object> json = MAPPER.readValue(txt, Map.class);
            return parseResponse(json);
        } catch (Exception e) {
            throw new RuntimeException("ExternalChatModel.call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 流式：POST {stream:true}，按 SSE 解析 data: {json}\n\n 块。
        // 每个 chunk 转为一个 ChatResponse（content 仅含 delta），emit 给上层 ReactAgent。
        // ReactAgent 会按 NodeOutput 聚合并最终得到完整 assistant content。
        AtomicReference<java.util.concurrent.Future<?>> httpRef = new AtomicReference<>();
        return Flux.create(sink -> {
            sink.onCancel(() -> {
                java.util.concurrent.Future<?> f = httpRef.get();
                if (f != null) f.cancel(true);
            });
            try {
                String body = renderRequest(prompt, true);
                URI endpoint = URI.create(baseUrl + (baseUrl.endsWith("/chat/completions") ? "" : "/chat/completions"));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                // 用 sendAsync 便于 sink.onCancel 取消
                java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> future = http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
                httpRef.set(future);
                HttpResponse<InputStream> resp;
                try {
                    resp = future.get();
                } catch (java.util.concurrent.ExecutionException ee) {
                    sink.error(new RuntimeException("ExternalChatModel.stream request failed: " + (ee.getCause() == null ? ee.getMessage() : ee.getCause().getMessage()), ee));
                    return;
                }
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    byte[] err = resp.body().readAllBytes();
                    String txt = new String(err, StandardCharsets.UTF_8);
                    sink.error(new RuntimeException("External model HTTP " + code + ": " + trim(txt, 280)));
                    return;
                }
                // 聚合 chunk 内的 prompt_tokens/completion_tokens；DeepSeek 把它放在最后一个 chunk
                AtomicReference<String> modelId = new AtomicReference<>(modelName);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;
                        if ("[DONE]".equals(data)) {
                            sink.complete();
                            return;
                        }
                        try {
                            Map<String, Object> json = MAPPER.readValue(data, Map.class);
                            if (modelId.get() == null) {
                                Object mid = json.get("model");
                                if (mid != null) modelId.set(String.valueOf(mid));
                            }
                            ChatResponse cr = parseStreamingChunk(json, modelId.get());
                            if (cr != null) sink.next(cr);
                        } catch (Exception e) {
                            // 单个 chunk 解析失败不阻塞流
                            logger.warn("[external] 解析 SSE chunk 失败: {}", e.getMessage());
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(new RuntimeException("ExternalChatModel.stream failed: " + e.getMessage(), e));
            }
        });
    }

    /** 解析单个 SSE chunk。返回 null 表示该 chunk 没有可消费内容。 */
    @SuppressWarnings("unchecked")
    private ChatResponse parseStreamingChunk(Map<String, Object> json, String modelId) {
        Object choicesObj = json.get("choices");
        if (!(choicesObj instanceof List)) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;
        if (choices.isEmpty()) return null;

        List<Generation> generations = new ArrayList<>();
        for (Map<String, Object> choice : choices) {
            Object deltaObj = choice.get("delta");
            String content = "";
            if (deltaObj instanceof Map) {
                Object c = ((Map<String, Object>) deltaObj).get("content");
                if (c != null) content = String.valueOf(c);
            }
            // 也有部分 provider 在 message 里给内容
            if (content.isEmpty()) {
                Object m = choice.get("message");
                if (m instanceof Map) {
                    Object c = ((Map<String, Object>) m).get("content");
                    if (c != null) content = String.valueOf(c);
                }
            }
            AssistantMessage am = new AssistantMessage(content);
            Object finishObj = choice.get("finish_reason");
            String finishReason = finishObj == null ? null : String.valueOf(finishObj);
            ChatGenerationMetadata gm = ChatGenerationMetadata.builder()
                    .finishReason(finishReason).build();
            generations.add(new Generation(am, gm));
        }

        ChatResponseMetadata.Builder mb = ChatResponseMetadata.builder()
                .model(modelId);
        Object u = json.get("usage");
        if (u instanceof Map) mb.usage(extractUsage(json));
        Object id = json.get("id");
        if (id != null) mb.id(String.valueOf(id));
        return new ChatResponse(generations, mb.build());
    }

    // ----------------- 私有 -----------------

    private HttpResponse<String> doPost(String body) throws Exception {
        URI endpoint = URI.create(baseUrl + (baseUrl.endsWith("/chat/completions") ? "" : "/chat/completions"));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 渲染 OpenAI-兼容请求体（仅 text，不带 tools，不解析 tool_calls） */
    private String renderRequest(Prompt prompt, boolean stream) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", modelName);
        if (temperature != null) root.put("temperature", temperature);
        if (maxTokens != null && maxTokens > 0) root.put("max_tokens", maxTokens);
        if (topP != null) root.put("top_p", topP);
        root.put("stream", stream);

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Message m : prompt.getInstructions()) {
            Map<String, Object> om = new LinkedHashMap<>();
            String role;
            switch (m.getMessageType()) {
                case SYSTEM: role = "system"; break;
                case USER: role = "user"; break;
                case ASSISTANT: role = "assistant"; break;
                case TOOL: role = "tool"; break;
                default: continue;
            }
            om.put("role", role);
            om.put("content", m.getText() == null ? "" : m.getText());
            msgs.add(om);
        }
        root.put("messages", msgs);
        return MAPPER.writeValueAsString(root);
    }

    /** 解析 OpenAI-兼容响应（仅取 content，不解析 tool_calls） */
    @SuppressWarnings("unchecked")
    private ChatResponse parseResponse(Map<String, Object> json) {
        List<Generation> generations = new ArrayList<>();
        Object choicesObj = json.get("choices");
        if (choicesObj instanceof List) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;
            for (Map<String, Object> choice : choices) {
                Object messageObj = choice.get("message");
                if (!(messageObj instanceof Map)) continue;
                Map<String, Object> message = (Map<String, Object>) messageObj;
                String content = stringOrEmpty(message.get("content"));
                AssistantMessage am = new AssistantMessage(content);
                Object finishObj = choice.get("finish_reason");
                String finishReason = finishObj == null ? null : String.valueOf(finishObj);
                ChatGenerationMetadata meta = ChatGenerationMetadata.builder()
                        .finishReason(finishReason).build();
                generations.add(new Generation(am, meta));
            }
        }
        ChatResponseMetadata resp = ChatResponseMetadata.builder()
                .id(stringOrNull(json.get("id")))
                .model(stringOrNull(json.get("model")))
                .usage(extractUsage(json))
                .build();
        return new ChatResponse(generations, resp);
    }

    private Usage extractUsage(Map<String, Object> json) {
        Object u = json.get("usage");
        if (!(u instanceof Map)) return new DefaultUsage(null, null, null, null);
        Map<?, ?> um = (Map<?, ?>) u;
        Integer pt = asInt(um.get("prompt_tokens"));
        Integer ct = asInt(um.get("completion_tokens"));
        Integer tt = asInt(um.get("total_tokens"));
        return new DefaultUsage(pt, ct, tt, null);
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }
    private static String stringOrEmpty(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String stringOrNull(Object o) { return o == null ? null : String.valueOf(o); }
    private static String trim(String s, int max) { if (s == null) return ""; return s.length() > max ? s.substring(0, max) + "..." : s; }
    private static String stripSlash(String s) { if (s == null) return ""; return s.replaceAll("/+$", ""); }

    // ----------------- Builder -----------------

    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private int timeoutSec = 120;

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder modelName(String v) { this.modelName = v; return this; }
        public Builder temperature(Double v) { this.temperature = v; return this; }
        public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
        public Builder topP(Double v) { this.topP = v; return this; }
        public Builder timeoutSec(int v) { this.timeoutSec = v; return this; }

        public ExternalChatModel build() { return new ExternalChatModel(this); }
    }
}
