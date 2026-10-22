package org.example.service;

import org.example.entity.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史召回打分（纯逻辑）：当用户提到"刚才/之前/我们说过"却不在上下文里时，从 SQLite 全量历史里找回相关片段。
 *
 * <p>打分口径刻意简单可解释：query 拆成 token（英文按词、中文按 2-gram），
 * 每个 token 在消息里出现就加分（单 token 封顶 3 次，避免某个高频词主导）；
 * 同分时**取更新的消息**（更可能是用户指的那句）。</p>
 *
 * <p>纯逻辑、无 DB 依赖，可直接单测；真正取数据在 {@link MemoryService#searchMessages}。</p>
 */
public final class HistoryRecallMatcher {

    /** 默认返回条数 */
    public static final int DEFAULT_LIMIT = 5;

    /** 单 token 计分上限，避免高频词霸榜 */
    private static final int MAX_HITS_PER_TOKEN = 3;

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_]{2,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private HistoryRecallMatcher() {
    }

    /**
     * 召回最相关的历史消息。
     *
     * @param messages 该会话的全部消息（按时间正序）
     * @param query    用户原话（可为 null）
     * @param limit    返回条数（&le;0 时用 {@link #DEFAULT_LIMIT}）
     */
    public static List<ChatMessage> topMatches(List<ChatMessage> messages, String query, int limit) {
        List<ChatMessage> hits = new ArrayList<>();
        if (messages == null || messages.isEmpty() || query == null || query.isBlank()) {
            return hits;
        }
        int max = limit > 0 ? limit : DEFAULT_LIMIT;
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return hits;
        }

        List<int[]> scored = new ArrayList<>(); // [index, score]
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            int score = score(m.getContent(), tokens);
            if (score > 0) {
                scored.add(new int[]{i, score});
            }
        }
        // 分数高的在前；同分取更新的（index 大的）
        scored.sort((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(b[0], a[0]));
        for (int i = 0; i < scored.size() && hits.size() < max; i++) {
            hits.add(messages.get(scored.get(i)[0]));
        }
        return hits;
    }

    /** 单条消息打分：所有 token 命中次数之和（单 token 封顶） */
    public static int score(String content, List<String> tokens) {
        if (content == null || content.isEmpty() || tokens == null || tokens.isEmpty()) {
            return 0;
        }
        String text = content.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String token : tokens) {
            int hits = 0;
            int from = 0;
            while (hits < MAX_HITS_PER_TOKEN) {
                int idx = text.indexOf(token, from);
                if (idx < 0) {
                    break;
                }
                hits++;
                from = idx + token.length();
            }
            total += hits;
        }
        return total;
    }

    /**
     * 拆词：英文/数字按 ≥2 字符的词；中文按 2-gram（单字成词时保留单字）。
     * 例「内网模型 gpt-4」→ [gpt, 内网, 网模, 模型]
     */
    public static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        String text = query.toLowerCase(Locale.ROOT);
        Matcher words = ASCII_WORD.matcher(text);
        while (words.find()) {
            tokens.add(words.group());
        }
        Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                tokens.add(run);
                continue;
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens.stream().distinct().toList();
    }
}
