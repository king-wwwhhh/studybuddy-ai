package org.example.service;

import org.example.entity.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HistoryRecallMatcher 单测：拆词、打分、同分取更新、条数上限。 */
class HistoryRecallMatcherTest {

    private static ChatMessage m(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    @DisplayName("拆词：英文按词、中文按 2-gram，去重")
    void tokenize() {
        List<String> tokens = HistoryRecallMatcher.tokenize("内网模型 gpt-4 怎么配");
        assertTrue(tokens.contains("gpt"), tokens.toString());
        assertTrue(tokens.contains("内网"), tokens.toString());
        assertTrue(tokens.contains("网模"), tokens.toString());
        assertTrue(tokens.contains("模型"), tokens.toString());
        assertEquals(tokens.size(), tokens.stream().distinct().count(), "不应有重复 token");

        assertEquals(List.of("好"), HistoryRecallMatcher.tokenize("好"), "单字成词时保留单字");
        assertTrue(HistoryRecallMatcher.tokenize("   ").isEmpty());
        assertTrue(HistoryRecallMatcher.tokenize(null).isEmpty());
    }

    @Test
    @DisplayName("打分：命中次数累加，单 token 封顶 3 次")
    void scoreCaps() {
        List<String> tokens = List.of("模型");
        assertEquals(0, HistoryRecallMatcher.score("和这个话题无关", tokens));
        assertEquals(2, HistoryRecallMatcher.score("模型 模型", tokens));
        assertEquals(3, HistoryRecallMatcher.score("模型模型模型模型模型", tokens), "单 token 最多计 3 次");
        assertEquals(0, HistoryRecallMatcher.score(null, tokens));
        assertEquals(0, HistoryRecallMatcher.score("模型", List.of()));
    }

    @Test
    @DisplayName("召回：相关内容排前面，无关内容被过滤")
    void topMatchesRanking() {
        List<ChatMessage> messages = List.of(
                m("user", "帮我看看这个表格怎么切"),
                m("assistant", "表格按行切，每片注入表头"),
                m("user", "记住约束：只能用内网模型"),
                m("assistant", "好的，之后都用内网模型"));

        List<ChatMessage> hits = HistoryRecallMatcher.topMatches(messages, "之前说的内网模型是什么", 2);

        assertEquals(2, hits.size());
        assertTrue(hits.get(0).getContent().contains("内网模型"), "最相关的应排第一: " + hits.get(0).getContent());
        assertTrue(hits.stream().noneMatch(h -> h.getContent().contains("表格")), "无关内容不应返回");
    }

    @Test
    @DisplayName("召回：同分时取更新的那条（更可能是用户指的那句）")
    void tiesPreferNewer() {
        List<ChatMessage> messages = List.of(
                m("user", "内网模型 A"),
                m("user", "内网模型 B"));

        List<ChatMessage> hits = HistoryRecallMatcher.topMatches(messages, "内网模型", 1);
        assertEquals("内网模型 B", hits.get(0).getContent(), "同分应优先较新的消息");
    }

    @Test
    @DisplayName("边界：空历史 / 空 query / limit<=0 用默认值")
    void edges() {
        assertTrue(HistoryRecallMatcher.topMatches(List.of(), "内网模型", 5).isEmpty());
        assertTrue(HistoryRecallMatcher.topMatches(List.of(m("user", "内网模型")), null, 5).isEmpty());
        assertTrue(HistoryRecallMatcher.topMatches(List.of(m("user", "内网模型")), "  ", 5).isEmpty());

        List<ChatMessage> many = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> m("user", "内网模型 " + i)).toList();
        assertEquals(HistoryRecallMatcher.DEFAULT_LIMIT,
                HistoryRecallMatcher.topMatches(many, "内网模型", 0).size(), "limit<=0 应用默认条数");
    }
}
