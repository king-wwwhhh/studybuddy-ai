package org.example.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 离线检索评估器：在「已有候选打分」上算 recall@k / precision / MRR，并反推阈值。
 *
 * <p>为什么需要它：`rag.score-threshold: 1.2` 是拍出来的经验值（等价 cos≈0.4，偏松），
 * 换 embedding 模型就失效。但没有评测手段时，"调阈值"只能靠感觉。本类把这件事变成可重复的计算：
 * 输入是日志/dump 出来的「query → 候选(id, L2²) + 标注的相关 id」，输出是可比较的指标与推荐阈值。</p>
 *
 * <p>纯逻辑：不连 Milvus、不调 embedding、不做 IO，因此可以放进单测、进 CI、做回归。</p>
 *
 * <p>口径说明：L2² 越小越相关；`recallAtK` 是**query 级命中率**（TopK 里至少有一个相关片段即算命中），
 * `precision` 是 micro 口径（命中片段数 / 保留片段数），`mrr` 是首个相关片段排名倒数的均值。</p>
 */
public final class RetrievalEvaluator {

    private RetrievalEvaluator() {
    }

    /** 一条候选：分片 id + L2² 距离（越小越相关） */
    public static final class Candidate {

        public final String id;
        public final float score;

        public Candidate(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    /** 一条评测样本：query + 相关分片集合 + 该 query 的候选（顺序不限，评估时按 score 升序） */
    public static final class Sample {

        public final String query;
        public final Set<String> relevant;
        public final List<Candidate> candidates;

        public Sample(String query, Set<String> relevant, List<Candidate> candidates) {
            this.query = query;
            this.relevant = relevant == null ? Set.of() : relevant;
            this.candidates = candidates == null ? List.of() : candidates;
        }

        public static Sample of(String query, List<String> relevant, List<Candidate> candidates) {
            return new Sample(query, new java.util.LinkedHashSet<>(relevant == null ? List.of() : relevant), candidates);
        }
    }

    /** 评估结果 */
    public static final class Report {

        public final int samples;
        public final int k;
        public final float threshold;
        /** query 级命中率：TopK 里至少有一个相关片段 */
        public final double recallAtK;
        /** micro 精确率：命中的相关片段数 / 保留片段数 */
        public final double precision;
        /** 首个相关片段排名倒数的均值 */
        public final double mrr;
        /** 每条 query 平均保留多少片段（阈值越松越大，直接影响上下文成本） */
        public final double avgKept;

        Report(int samples, int k, float threshold, double recallAtK, double precision,
               double mrr, double avgKept) {
            this.samples = samples;
            this.k = k;
            this.threshold = threshold;
            this.recallAtK = recallAtK;
            this.precision = precision;
            this.mrr = mrr;
            this.avgKept = avgKept;
        }
    }

    /**
     * 在给定阈值下评估：先按 `score ≤ threshold` 过滤，再按 score 升序取前 k 条。
     *
     * @param k         取前 k 条（必须 > 0，否则按 1 处理）
     * @param threshold L2² 阈值；`Float.MAX_VALUE` 表示不过滤
     */
    public static Report evaluate(List<Sample> samples, int k, float threshold) {
        int topK = Math.max(1, k);
        if (samples == null || samples.isEmpty()) {
            return new Report(0, topK, threshold, 0, 0, 0, 0);
        }
        int hitQueries = 0;
        int relevantKept = 0;
        int keptTotal = 0;
        double reciprocalRankSum = 0;
        for (Sample s : samples) {
            List<Candidate> kept = filter(s.candidates, threshold, topK);
            keptTotal += kept.size();

            int hits = 0;
            int firstRelevantRank = 0;
            for (int i = 0; i < kept.size(); i++) {
                if (s.relevant.contains(kept.get(i).id)) {
                    hits++;
                    if (firstRelevantRank == 0) {
                        firstRelevantRank = i + 1;
                    }
                }
            }
            relevantKept += hits;
            if (hits > 0) {
                hitQueries++;
                reciprocalRankSum += 1.0 / firstRelevantRank;
            }
        }
        int n = samples.size();
        double precision = keptTotal == 0 ? 0 : (double) relevantKept / keptTotal;
        return new Report(n, topK, threshold,
                (double) hitQueries / n, precision, reciprocalRankSum / n, (double) keptTotal / n);
    }

    /**
     * 在候选阈值网格里挑「满足目标召回的最严阈值」（越小越严 → 噪声越少）。
     *
     * @param targetRecall 目标 query 级召回，例如 0.9
     * @param grid         候选阈值，方法内部会升序排序
     * @return 推荐的阈值；网格内都达不到目标召回时返回最宽松的那个（并应由调用方放宽网格）
     */
    public static float calibrate(List<Sample> samples, int k, double targetRecall, float[] grid) {
        if (grid == null || grid.length == 0) {
            throw new IllegalArgumentException("阈值网格不能为空");
        }
        float[] sorted = grid.clone();
        java.util.Arrays.sort(sorted);
        for (float t : sorted) {
            if (evaluate(samples, k, t).recallAtK >= targetRecall) {
                return t;
            }
        }
        return sorted[sorted.length - 1];
    }

    /**
     * 相关片段 score 的 P95 —— 没有人工标注时的快速标定法：
     * 把阈值定在"95% 的相关片段都能通过"的位置，等价于丢掉最相关的 5% 之外的长尾。
     */
    public static float p95OfRelevant(List<Sample> samples) {
        List<Float> scores = new ArrayList<>();
        if (samples != null) {
            for (Sample s : samples) {
                for (Candidate c : s.candidates) {
                    if (s.relevant.contains(c.id)) {
                        scores.add(c.score);
                    }
                }
            }
        }
        if (scores.isEmpty()) {
            return 0f;
        }
        scores.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(0.95 * scores.size()) - 1;
        return scores.get(Math.max(0, Math.min(idx, scores.size() - 1)));
    }

    private static List<Candidate> filter(List<Candidate> candidates, float threshold, int topK) {
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.score <= threshold) {
                kept.add(c);
            }
        }
        kept.sort(Comparator.comparingDouble(c -> c.score));
        return kept.size() > topK ? kept.subList(0, topK) : kept;
    }
}
