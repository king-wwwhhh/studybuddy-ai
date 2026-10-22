package org.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.example.entity.ChatMessage;
import org.example.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史召回工具：把「本次会话更早的消息」变成模型可以主动调用的能力。
 *
 * <p>为什么需要它：上下文窗口只有最近 6 对，窗口外的消息可能还在"待压缩灰区"或已被有损摘要吞掉。
 * 这时用户若提到"刚才/之前/我们说过"的细节，模型只靠 prompt 是**读不到**的，容易答"没提过"或干脆编造。
 * 有了这个工具，模型可以按关键词回查 SQLite 里的全量历史，然后如实回答或引用。</p>
 *
 * <p>安全边界：只查**当前会话**（sessionId 由系统提示给出），不跨会话、不返回别人的数据。</p>
 */
@Component
public class HistoryRecallTool {

    private static final Logger logger = LoggerFactory.getLogger(HistoryRecallTool.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MemoryService memoryService;

    /** 单次返回条数上限（防止一次把历史灌满上下文） */
    @Value("${memory.recall.limit:5}")
    private int recallLimit = 5;

    @Autowired
    public HistoryRecallTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = """
            Recall earlier messages of THIS conversation from durable storage (SQLite).
            Call it whenever the user refers to something you cannot see in the current context —
            e.g. "刚才/之前/我们说过/那个约束/你还记得吗" — or when you suspect the history was truncated/summarized.
            Returns JSON: {"sessionId":..., "matches":[{role,content,createdAt}], "hint":...}.
            If matches is empty, tell the user honestly that you cannot see that part; do NOT invent it.""")
    public String recallHistory(
            @ToolParam(description = "Session id exactly as given in the system prompt's 【会话记忆】 block")
            String sessionId,
            @ToolParam(description = "Keywords or a short phrase to search in this session's history, e.g. 内网模型")
            String query) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return "{\"status\":\"error\",\"message\":\"缺少 sessionId，无法召回历史\"}";
            }
            int limit = recallLimit > 0 ? Math.min(recallLimit, 20) : 5;
            List<ChatMessage> hits = memoryService.searchMessages(sessionId, query, limit);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            List<Map<String, Object>> matches = new ArrayList<>();
            for (ChatMessage m : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", m.getRole());
                item.put("content", m.getContent());
                item.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
                matches.add(item);
            }
            payload.put("matches", matches);
            payload.put("hint", matches.isEmpty()
                    ? "该会话历史里没有找到相关内容：请如实告诉用户你看不到更早的对话，不要编造。"
                    : "这些是本次会话更早的真实消息，引用时可以说「你之前提到过…」。");
            logger.info("历史召回: session={} query={} 命中={}", sessionId, query, matches.size());
            return GSON.toJson(payload);
        } catch (Exception e) {
            logger.error("历史召回失败: session={} query={}", sessionId, query, e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
