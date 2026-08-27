package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.service.ChatService;
import org.example.service.MemoryService;
import org.example.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private MemoryService memoryService;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 6;

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 获取或创建会话
            SessionInfo session = getOrCreateSession(request.getId());

            // 获取历史消息
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            // 多 Agent 路由：显式指定或主管自动决策
            String agentType = chatService.resolveAgentType(request.getAgent(), request.getQuestion());

            // 解析模型规格：内嵌外部模型字段时合成 "ext:baseUrl|apiKey|modelId"
            String modelSpec = resolveModelSpec(request);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("多Agent路由 -> {} ; 开始对话", agentType);

            // 构建并执行所选 Agent 的 ReactAgent（携带用户指定模型）
            ChatService.RouteResult result = chatService.answer(request.getQuestion(), agentType, history, modelSpec);
            String fullAnswer = result.answer;

            // 更新会话历史
            session.addMessage(request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                request.getId(), session.getMessagePairCount());

            // 持久化短期记忆
            chatService.persistTurn(request.getId(), result.agentType, request.getQuestion(), fullAnswer);

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer, result.agentType)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionInfo session = sessions.get(request.getId());
            if (session != null) {
                session.clearHistory();
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                SessionInfo session = getOrCreateSession(request.getId());
                
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 多 Agent 路由（流式）：显式指定或主管自动决策
                String agentType = chatService.resolveAgentType(request.getAgent(), request.getQuestion());

                // 解析模型规格
                String modelSpec = resolveModelSpec(request);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("多Agent路由(流式) -> {} ; 开始对话", agentType);

                // 构建所选 Agent 的 ReactAgent（携带专属工具、提示词与用户指定模型）
                ReactAgent agent = chatService.buildAgent(agentType, history, modelSpec);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            session.addMessage(request.getQuestion(), fullAnswer);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                                request.getId(), session.getMessagePairCount());

                            // 持久化短期记忆
                            chatService.persistTurn(request.getId(), agentType, request.getQuestion(), fullAnswer);

                            // 发送完成标记（携带最终 Agent 类型）
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(agentType), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = sessions.get(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析模型规格。
     * 优先级：外部模型字段（baseUrl + apiKey + modelName）> 用户显式 Model（已带 ext: 前缀或 dashscope 名）> null（默认模型）。
     * 返回 "ext:baseUrl|apiKey|modelId" 或 dashscope 模型名或 null。
     */
    private String resolveModelSpec(ChatRequest request) {
        String baseUrl = trim(request.getExternalBaseUrl());
        String modelName = trim(request.getExternalModelName());
        String apiKey = request.getExternalApiKey() == null ? "" : request.getExternalApiKey();
        if (!baseUrl.isEmpty() && !modelName.isEmpty()) {
            return "ext:" + baseUrl + "|" + apiKey + "|" + modelName;
        }
        return request.getModel();
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        SessionInfo session = sessions.computeIfAbsent(sessionId, id -> new SessionInfo(id, memoryService));
        // 短期记忆恢复：若内存无记录但 SQLite 中已有历史，则载入最近窗口（超长时自动摘要压缩）
        if (session.isEmpty() && memoryService.getSession(sessionId) != null) {
            session.bulkLoadSummarized(memoryService.loadSummarized(sessionId, MAX_WINDOW_SIZE));
        }
        return session;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息
     * 管理单个会话的历史消息，支持自动清理和线程安全
     */
    private static class SessionInfo {
        /** 攒够这么多条旧消息就触发一次 LLM 摘要压缩（memoryService.summarize 需 ≥6 条才有输出） */
        private static final int COMPRESS_TRIGGER = 8;
        private final String sessionId;
        private final MemoryService memoryService;
        // 存储历史消息对：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
        private final List<Map<String, String>> messageHistory;
        // 超窗被移出的旧消息暂存于此，攒够一批后压缩成 [历史摘要]，而不是直接丢弃
        private final List<Map<String, String>> pendingCompress;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId, MemoryService memoryService) {
            this.sessionId = sessionId;
            this.memoryService = memoryService;
            this.messageHistory = new ArrayList<>();
            this.pendingCompress = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         * 自动管理历史消息窗口大小
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                // 自动清理 + 对话中压缩：超窗的旧消息先暂存，攒够一批再用 LLM 压缩成摘要，而不是直接丢弃
                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    // 跳过最前可能的 [历史摘要] system 消息，避免把它也移出
                    int idx = (!messageHistory.isEmpty() && "system".equals(messageHistory.get(0).get("role"))) ? 1 : 0;
                    if (idx >= messageHistory.size()) break;
                    pendingCompress.add(messageHistory.remove(idx));
                    if (idx < messageHistory.size()) {
                        pendingCompress.add(messageHistory.remove(idx));
                    }
                    if (pendingCompress.size() >= COMPRESS_TRIGGER) {
                        compressPending();
                    }
                }

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                    sessionId, messageHistory.size() / 2);

            } finally {
                lock.unlock();
            }
        }

        /**
         * 把暂存的旧消息用 LLM 压成一条 [历史摘要]，合并到历史最前。
         * 若已有摘要则连同它一起重新压缩，保证只保留一条有界的摘要（不无限累积）。
         * 压缩失败时退化为丢弃（pendingCompress 清空），不影响主链路。
         */
        private void compressPending() {
            if (pendingCompress.isEmpty()) return;
            try {
                List<ChatMessage> toCompress = new ArrayList<>();
                boolean hasSystem = !messageHistory.isEmpty() && "system".equals(messageHistory.get(0).get("role"));
                if (hasSystem) {
                    ChatMessage sys = new ChatMessage();
                    sys.setRole("system");
                    sys.setContent(messageHistory.get(0).get("content"));
                    toCompress.add(sys);
                }
                for (Map<String, String> m : pendingCompress) {
                    ChatMessage cm = new ChatMessage();
                    cm.setRole(m.get("role"));
                    cm.setContent(m.get("content"));
                    toCompress.add(cm);
                }
                String summary = memoryService.summarize(toCompress);
                if (summary != null && !summary.isBlank()) {
                    Map<String, String> sysMsg = new HashMap<>();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", "[历史摘要] " + summary);
                    if (hasSystem) {
                        messageHistory.set(0, sysMsg);
                    } else {
                        messageHistory.add(0, sysMsg);
                    }
                    logger.info("会话 {} 对话中压缩完成，历史摘要已更新", sessionId);
                }
            } catch (Exception e) {
                logger.warn("会话 {} 对话中压缩失败: {}", sessionId, e.getMessage());
            } finally {
                pendingCompress.clear();
            }
        }

        /**
         * 获取历史消息（线程安全）
         * 返回副本以避免并发修改
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                pendingCompress.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            lock.lock();
            try {
                return messageHistory.isEmpty();
            } finally {
                lock.unlock();
            }
        }

        /** 从 SQLite 短期记忆批量载入历史（仅保留最近一个窗口） */
        public void bulkLoad(List<ChatMessage> messages) {
            lock.lock();
            try {
                for (ChatMessage m : messages) {
                    Map<String, String> map = new HashMap<>();
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    messageHistory.add(map);
                }
                int max = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > max) {
                    messageHistory.remove(0);
                }
            } finally {
                lock.unlock();
            }
        }

        /** 从「可能含历史摘要」的上下文列表载入（首条 system 摘要会保留到历史最前面） */
        public void bulkLoadSummarized(List<Map<String, String>> entries) {
            lock.lock();
            try {
                boolean hasSummary = !entries.isEmpty() && "system".equals(entries.get(0).get("role"));
                for (Map<String, String> entry : entries) {
                    Map<String, String> map = new HashMap<>();
                    map.put("role", entry.get("role"));
                    map.put("content", entry.get("content"));
                    messageHistory.add(map);
                }
                // 若含摘要，不截断摘要（保留 system 开头）；否则截断到窗口
                int max = MAX_WINDOW_SIZE * 2;
                if (!hasSummary) {
                    while (messageHistory.size() > max) {
                        messageHistory.remove(0);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

        /** 显式指定 Agent 类型：chat / study / quiz / planner / coach；为空则由主管自动路由 */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Agent")
        @com.fasterxml.jackson.annotation.JsonAlias({"agent", "AGENT", "agentType"})
        private String Agent;

        /** 显式指定对话模型（如 qwen3-turbo）；为空则使用后端默认模型 */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Model")
        @com.fasterxml.jackson.annotation.JsonAlias({"model", "MODEL"})
        private String Model;

        /** 外部 OpenAI 兼容模型：BaseUrl（必填） */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "ExternalBaseUrl")
        @com.fasterxml.jackson.annotation.JsonAlias({"externalBaseUrl", "external_base_url"})
        private String ExternalBaseUrl;

        /** 外部 OpenAI 兼容模型：API Key */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "ExternalApiKey")
        @com.fasterxml.jackson.annotation.JsonAlias({"externalApiKey", "external_api_key"})
        private String ExternalApiKey;

        /** 外部 OpenAI 兼容模型：模型 ID（如 deepseek-chat / gpt-4o-mini） */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "ExternalModelName")
        @com.fasterxml.jackson.annotation.JsonAlias({"externalModelName", "external_model_name"})
        private String ExternalModelName;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;
        /** 本次应答命中的 Agent 类型（chat/study/quiz/planner/coach） */
        private String activeAgent;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse success(String answer, String activeAgent) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            response.setActiveAgent(activeAgent);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;
        /** 完成标记携带最终命中的 Agent 类型 */
        private String agent;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }

        public static SseMessage done(String agentType) {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            message.setAgent(agentType);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
