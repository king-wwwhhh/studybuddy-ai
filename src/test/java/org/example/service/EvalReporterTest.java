package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EvalReporter 单测：报告内容、无标注提示、标注解析容错。 */
class EvalReporterTest {

    private static RetrievalEvaluator.Candidate c(String id, float score) {
        return new RetrievalEvaluator.Candidate(id, score);
    }

    private static List<RetrievalEvaluator.Sample> twoSamples() {
        return List.of(
                RetrievalEvaluator.Sample.of("q1", List.of("hit"), List.of(c("hit", 0.2f), c("noise", 0.9f))),
                RetrievalEvaluator.Sample.of("q2", List.of("miss"), List.of(c("a", 0.3f), c("b", 0.4f))));
    }

    @Test
    @DisplayName("报告包含样本数、有标注数、按 k 的指标、阈值扫描与推荐阈值")
    void renderContainsKeySections() {
        String report = EvalReporter.render(twoSamples(), new int[]{1, 3},
                new float[]{0.5f, 1.2f}, 0.9);

        assertTrue(report.contains("样本数: 2"), report);
        assertTrue(report.contains("有标注样本: 2 / 2"), report);
        assertTrue(report.contains("k=1"), report);
        assertTrue(report.contains("k=3"), report);
        assertTrue(report.contains("recall=0.500"), "k=1 时只有 q1 命中: " + report);
        assertTrue(report.contains("t=0.50"), report);
        assertTrue(report.contains("推荐阈值"), report);
        assertTrue(report.contains("换 embedding 模型必须重新标定"), report);
    }

    @Test
    @DisplayName("没有标注时明确提示 recall/MRR 恒为 0（避免把 0 当成检索坏了）")
    void renderWithoutLabels() {
        List<RetrievalEvaluator.Sample> samples = List.of(
                RetrievalEvaluator.Sample.of("q", List.of(), List.of(c("x", 0.3f))));

        String report = EvalReporter.render(samples, null, null, 0.9);
        assertTrue(report.contains("有标注样本: 0 / 1"), report);
        assertTrue(report.contains("没有标注"), report);
        assertTrue(report.contains("recall=0.000"), report);
    }

    @Test
    @DisplayName("空样本给出可操作的提示，而不是空报告")
    void renderEmpty() {
        String report = EvalReporter.render(List.of(), null, null, 0.9);
        assertTrue(report.contains("样本数: 0"), report);
        assertTrue(report.contains("没有可用样本"), report);
        assertTrue(report.contains("检索明细"), report);
    }

    @Test
    @DisplayName("parseLabels：正常解析；坏 JSON / 空输入 / 非数组值都退化为空表或空列表")
    void parseLabels() {
        Map<String, List<String>> labels = EvalReporter.parseLabels(
                "{\"什么是HNSW\":[\"a\",\"b\"],\"表格怎么切\":[\"c\"]}");
        assertEquals(2, labels.size());
        assertEquals(List.of("a", "b"), labels.get("什么是HNSW"));
        assertEquals(List.of("c"), labels.get("表格怎么切"));

        assertTrue(EvalReporter.parseLabels("not-json").isEmpty());
        assertTrue(EvalReporter.parseLabels("").isEmpty());
        assertTrue(EvalReporter.parseLabels(null).isEmpty());

        Map<String, List<String>> odd = EvalReporter.parseLabels("{\"q\":\"不是数组\"}");
        assertEquals(1, odd.size());
        assertTrue(odd.get("q").isEmpty(), "非数组值应解析为空列表");
    }

    @Test
    @DisplayName("CLI 帮助文本包含用法与标注格式，便于直接照抄")
    void cliUsage() {
        String usage = org.example.cli.RagEvalCli.usage();
        assertTrue(usage.contains("用法: RagEvalCli"), usage);
        assertTrue(usage.contains("检索明细"), usage);
        assertTrue(usage.contains("标注格式"), usage);
    }
}
