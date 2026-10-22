package org.example.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告渲染与标注解析（纯逻辑，便于单测与 CLI 复用）。
 *
 * <p>把第 5 轮的 {@link RetrievalEvaluator} 与第 10 轮的 {@link RetrievalLogParser} 串起来：
 * 输入是「日志解析出的样本」，输出是可直接贴到文档/工单里的文本报告（多 k 的召回、阈值扫描、推荐阈值）。</p>
 */
public final class EvalReporter {

    /** 默认评估的 k 值：1/3/5 覆盖"最靠前一条"到"上下文能放下的量" */
    public static final int[] DEFAULT_KS = {1, 3, 5};

    /** 默认阈值扫描网格：0.4≈cos0.8、0.5≈cos0.75、0.6≈cos0.7、0.8≈cos0.6、1.0≈cos0.5、1.2≈cos0.4（归一化 L2²） */
    public static final float[] DEFAULT_THRESHOLDS = {0.4f, 0.5f, 0.6f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f};

    private EvalReporter() {
    }

    /**
     * 渲染文本报告。
     *
     * @param samples      评测样本（相关集合为空时仍可看分布/阈值行为，但 recall 恒为 0）
     * @param ks           要评估的 k 列表
     * @param thresholds   阈值扫描网格
     * @param targetRecall 反推推荐阈值时的目标召回（如 0.9）
     */
    public static String render(List<RetrievalEvaluator.Sample> samples, int[] ks,
                                float[] thresholds, double targetRecall) {
        StringBuilder sb = new StringBuilder();
        int n = samples == null ? 0 : samples.size();
        sb.append("样本数: ").append(n).append('\n');
        if (n == 0) {
            sb.append("没有可用样本：请确认日志里包含 `检索明细`（DEBUG 级别）且标注文件 key 与 query 完全一致\n");
            return sb.toString();
        }

        long annotated = samples.stream().filter(s -> !s.relevant.isEmpty()).count();
        sb.append("有标注样本: ").append(annotated).append(" / ").append(n)
                .append(annotated == 0 ? "（没有标注 → recall/MRR 恒为 0，只能看阈值行为）" : "")
                .append('\n');

        sb.append("\n按 k 评估（阈值不限，Float.MAX_VALUE）:\n");
        for (int k : ks == null || ks.length == 0 ? DEFAULT_KS : ks) {
            RetrievalEvaluator.Report r = RetrievalEvaluator.evaluate(samples, k, Float.MAX_VALUE);
            sb.append(String.format("  k=%-2d recall=%.3f mrr=%.3f avgKept=%.2f%n",
                    r.k, r.recallAtK, r.mrr, r.avgKept));
        }

        float[] grid = thresholds == null || thresholds.length == 0 ? DEFAULT_THRESHOLDS : thresholds;
        sb.append("\n阈值扫描（k=3）:\n");
        for (float t : grid) {
            RetrievalEvaluator.Report r = RetrievalEvaluator.evaluate(samples, 3, t);
            sb.append(String.format("  t=%-4.2f recall=%.3f precision=%.3f avgKept=%.2f%n",
                    t, r.recallAtK, r.precision, r.avgKept));
        }

        float recommended = RetrievalEvaluator.calibrate(samples, 3, targetRecall, grid);
        sb.append(String.format("%n推荐阈值: %.2f（目标召回 %.2f，网格内最严）%n", recommended, targetRecall));
        sb.append("提示: 阈值是 L2²（归一化下 2−2cos），换 embedding 模型必须重新标定\n");
        return sb.toString();
    }

    /**
     * 解析标注文件：`{"query": ["id1","id2"]}`。
     * 解析失败返回空表（调用方会得到"有标注 0 / N"的提示，而不是崩掉）。
     */
    public static Map<String, List<String>> parseLabels(String json) {
        Map<String, List<String>> labels = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return labels;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                List<String> ids = new ArrayList<>();
                if (e.getValue().isJsonArray()) {
                    for (JsonElement id : e.getValue().getAsJsonArray()) {
                        if (!id.isJsonNull()) {
                            ids.add(id.getAsString());
                        }
                    }
                }
                labels.put(e.getKey(), ids);
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return labels;
    }
}
