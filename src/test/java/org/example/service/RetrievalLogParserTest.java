package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RetrievalLogParser 单测：日志 payload 与解析严格对照（round-trip），并容忍脏日志。 */
class RetrievalLogParserTest {

    private static RetrievalEvaluator.Candidate c(String id, float score) {
        return new RetrievalEvaluator.Candidate(id, score);
    }

    @Test
    @DisplayName("round-trip：写出去的 payload 能原样解析回来（含中文、引号、逗号的 query）")
    void roundTrip() {
        String query = "「向量检索」怎么做？包含,逗号\"引号\"和|竖线";
        List<RetrievalEvaluator.Candidate> candidates = List.of(c("id-a", 0.31f), c("id-b", 1.05f));

        String payload = RetrievalLogParser.toLogPayload(query, candidates);
        String logLine = "2026-10-22 10:00:00.123 DEBUG 123 --- [chat-sse-1] c.e.s.VectorSearchService : "
                + RetrievalLogParser.PREFIX + payload;

        Optional<RetrievalLogParser.ParsedQuery> parsed = RetrievalLogParser.parseLine(logLine);
        assertTrue(parsed.isPresent(), "带时间戳前缀的日志行也应能解析");
        assertEquals(query, parsed.get().query);
        assertEquals(2, parsed.get().candidates.size());
        assertEquals("id-a", parsed.get().candidates.get(0).id);
        assertEquals(0.31f, parsed.get().candidates.get(0).score, 1e-6);
        assertEquals("id-b", parsed.get().candidates.get(1).id);
        assertEquals(1.05f, parsed.get().candidates.get(1).score, 1e-6);
    }

    @Test
    @DisplayName("无关行 / 空行 / 坏 JSON 都返回 empty，不抛异常")
    void toleratesNoise() {
        assertFalse(RetrievalLogParser.parseLine(null).isPresent());
        assertFalse(RetrievalLogParser.parseLine("").isPresent());
        assertFalse(RetrievalLogParser.parseLine("普通业务日志，没有候选明细").isPresent());
        assertFalse(RetrievalLogParser.parseLine(RetrievalLogParser.PREFIX + "not-json").isPresent());
        assertFalse(RetrievalLogParser.parseLine(RetrievalLogParser.PREFIX + "{\"query\":\"q\"").isPresent(),
                "截断的 JSON 应被忽略");
    }

    @Test
    @DisplayName("parseLines：忽略噪声行，按 query 合并标注；没标注的样本相关集合为空")
    void parseLinesWithLabels() {
        String q1 = "什么是 HNSW";
        String q2 = "表格怎么切";
        List<String> lines = List.of(
                "无关日志一行",
                RetrievalLogParser.PREFIX + RetrievalLogParser.toLogPayload(q1,
                        List.of(c("x1", 0.2f), c("x2", 0.9f))),
                RetrievalLogParser.PREFIX + RetrievalLogParser.toLogPayload(q2,
                        List.of(c("y1", 0.4f))),
                RetrievalLogParser.PREFIX + "broken");

        List<RetrievalEvaluator.Sample> samples = RetrievalLogParser.parseLines(lines,
                Map.of(q1, List.of("x2")));

        assertEquals(2, samples.size());
        assertEquals(q1, samples.get(0).query);
        assertEquals(1, samples.get(0).relevant.size());
        assertTrue(samples.get(0).relevant.contains("x2"));
        assertTrue(samples.get(1).relevant.isEmpty(), "没有标注的 query 相关集合为空");
    }

    @Test
    @DisplayName("解析出来的样本可直接喂给评估器算指标")
    void feedsEvaluator() {
        List<String> lines = List.of(
                RetrievalLogParser.PREFIX + RetrievalLogParser.toLogPayload("q",
                        List.of(c("hit", 0.3f), c("noise", 0.8f))));

        List<RetrievalEvaluator.Sample> samples = RetrievalLogParser.parseLines(lines, Map.of("q", List.of("hit")));
        RetrievalEvaluator.Report report = RetrievalEvaluator.evaluate(samples, 1, 1.2f);

        assertEquals(1.0, report.recallAtK, 1e-9);
        assertEquals(1.0, report.mrr, 1e-9);
        assertEquals(1.0, report.avgKept, 1e-9);
    }

    @Test
    @DisplayName("边界：null / 空列表 / 空候选 / 缺字段")
    void edges() {
        assertTrue(RetrievalLogParser.parseLines(null, null).isEmpty());
        assertTrue(RetrievalLogParser.parseLines(List.of(), null).isEmpty());

        String payload = RetrievalLogParser.toLogPayload("q", List.of());
        Optional<RetrievalLogParser.ParsedQuery> parsed = RetrievalLogParser.parseLine(payload);
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().candidates.isEmpty());

        // 缺 score → 用 Float.MAX_VALUE（等价"不过滤时最差"），仍能解析
        Optional<RetrievalLogParser.ParsedQuery> noScore =
                RetrievalLogParser.parseLine("检索明细: {\"query\":\"q\",\"candidates\":[{\"id\":\"only-id\"}]}");
        assertTrue(noScore.isPresent());
        assertEquals(Float.MAX_VALUE, noScore.get().candidates.get(0).score, 1e-6);

        // 缺 id 的候选被跳过
        Optional<RetrievalLogParser.ParsedQuery> noId =
                RetrievalLogParser.parseLine("检索明细: {\"query\":\"q\",\"candidates\":[{\"score\":0.5}]}");
        assertTrue(noId.isPresent());
        assertTrue(noId.get().candidates.isEmpty());
    }
}
