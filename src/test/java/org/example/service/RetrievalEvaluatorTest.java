package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RetrievalEvaluator 单测：指标口径、阈值过滤、阈值反推、P95 标定。 */
class RetrievalEvaluatorTest {

    private static RetrievalEvaluator.Sample sample(String q, List<String> relevant,
                                                   RetrievalEvaluator.Candidate... cands) {
        return RetrievalEvaluator.Sample.of(q, relevant, List.of(cands));
    }

    private static RetrievalEvaluator.Candidate c(String id, float score) {
        return new RetrievalEvaluator.Candidate(id, score);
    }

    /** 样本 A：相关片段排第 1；样本 B：相关片段排第 2 */
    private static List<RetrievalEvaluator.Sample> twoSamples() {
        return List.of(
                sample("q1", List.of("a1"), c("a1", 0.2f), c("a9", 0.5f)),
                sample("q2", List.of("b2"), c("b9", 0.3f), c("b2", 0.6f)));
    }

    @Test
    @DisplayName("recall/MRR：TopK 越大命中越多，MRR 反映首个相关片段的排名")
    void basicMetrics() {
        List<RetrievalEvaluator.Sample> samples = twoSamples();

        RetrievalEvaluator.Report at1 = RetrievalEvaluator.evaluate(samples, 1, Float.MAX_VALUE);
        assertEquals(0.5, at1.recallAtK, 1e-9);                 // 只有 q1 命中
        assertEquals(0.5, at1.mrr, 1e-9);                       // q1: 1/1，q2 Top1 是噪声 → 0
        assertEquals(1.0, at1.avgKept, 1e-9);

        RetrievalEvaluator.Report at2 = RetrievalEvaluator.evaluate(samples, 2, Float.MAX_VALUE);
        assertEquals(1.0, at2.recallAtK, 1e-9);                 // 两条都命中
        assertEquals((1.0 + 0.5) / 2, at2.mrr, 1e-9);           // (1/1 + 1/2)/2
        assertEquals(0.5, at2.precision, 1e-9);                 // 命中 2 / 保留 4（噪声也被保留）
    }

    @Test
    @DisplayName("阈值过滤：阈值比相关片段更严时，相关片段被丢掉（召回归零）—— 这正是 1.2 拍值的风险")
    void thresholdCanDropRelevant() {
        // 相关片段 0.5，噪声 0.3；阈值 0.4 → 只留噪声
        List<RetrievalEvaluator.Sample> samples = List.of(
                sample("q", List.of("rel"), c("rel", 0.5f), c("noise", 0.3f)));

        RetrievalEvaluator.Report tight = RetrievalEvaluator.evaluate(samples, 3, 0.4f);
        assertEquals(0.0, tight.recallAtK, 1e-9);
        assertEquals(0.0, tight.precision, 1e-9);
        assertEquals(1.0, tight.avgKept, 1e-9);

        RetrievalEvaluator.Report loose = RetrievalEvaluator.evaluate(samples, 3, 1.2f);
        assertEquals(1.0, loose.recallAtK, 1e-9);
        assertEquals(0.5, loose.precision, 1e-9);               // 相关 1 条 / 保留 2 条
        assertEquals(2.0, loose.avgKept, 1e-9);
    }

    @Test
    @DisplayName("calibrate：返回满足目标召回的「最严」阈值")
    void calibratePicksStrictest() {
        List<RetrievalEvaluator.Sample> samples = List.of(
                sample("q", List.of("rel"), c("rel", 0.5f), c("noise", 1.0f)));

        // 网格 0.4（丢相关）/ 0.6（留相关）/ 1.2（留全部）
        float[] grid = {1.2f, 0.4f, 0.6f};
        assertEquals(0.6f, RetrievalEvaluator.calibrate(samples, 3, 1.0, grid), 1e-6);
        // 只要召回 ≥ 0 的宽松目标 → 最严的 0.4 就满足
        assertEquals(0.4f, RetrievalEvaluator.calibrate(samples, 3, 0.0, grid), 1e-6);
    }

    @Test
    @DisplayName("calibrate：网格内达不到目标召回时返回最宽松阈值（提示调用方放宽网格）")
    void calibrateFallsBackToLoosest() {
        List<RetrievalEvaluator.Sample> samples = List.of(
                sample("q", List.of("rel"), c("rel", 5.0f)));
        assertEquals(0.3f, RetrievalEvaluator.calibrate(samples, 3, 1.0, new float[]{0.3f}), 1e-6);
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalEvaluator.calibrate(samples, 3, 1.0, new float[]{}));
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalEvaluator.calibrate(samples, 3, 1.0, null));
    }

    @Test
    @DisplayName("p95OfRelevant：无标注也可标定，取相关片段分数的 P95")
    void p95() {
        List<RetrievalEvaluator.Sample> samples = List.of(
                sample("q", List.of("r1", "r2", "r3"), c("r1", 0.1f), c("r2", 0.5f), c("r3", 0.9f),
                        c("noise", 9.9f)));
        assertEquals(0.9f, RetrievalEvaluator.p95OfRelevant(samples), 1e-6);
        assertEquals(0f, RetrievalEvaluator.p95OfRelevant(List.of()), 1e-6);
        assertEquals(0f, RetrievalEvaluator.p95OfRelevant(null), 1e-6);
    }

    @Test
    @DisplayName("边界：空样本 / 空候选 / k<=0 都不抛异常")
    void edges() {
        RetrievalEvaluator.Report empty = RetrievalEvaluator.evaluate(null, 3, 1.2f);
        assertEquals(0, empty.samples);
        assertEquals(0.0, empty.recallAtK, 1e-9);

        RetrievalEvaluator.Report noCands = RetrievalEvaluator.evaluate(
                List.of(sample("q", List.of("x"))), 3, 1.2f);
        assertEquals(0.0, noCands.recallAtK, 1e-9);
        assertEquals(0.0, noCands.avgKept, 1e-9);

        // k<=0 归一化为 1，不抛异常
        RetrievalEvaluator.Report zeroK = RetrievalEvaluator.evaluate(twoSamples(), 0, Float.MAX_VALUE);
        assertEquals(1, zeroK.k);
        assertTrue(zeroK.recallAtK > 0);
    }
}
