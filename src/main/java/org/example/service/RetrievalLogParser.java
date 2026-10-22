package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检索日志解析器：把线上日志里的「候选明细」还原成 {@link RetrievalEvaluator.Sample}，
 * 让第 5 轮的评估器能直接吃到真实流量。
 *
 * <p>为什么需要它：第 5 轮做了评估器，但输入要人工整理；而日志里原本**只有 score、没有 id**，
 * 根本还原不出「query → (分片 id, score)」的配对。本类与 {@link VectorSearchService} 共用
 * {@link #toLogPayload} 这一份格式定义，保证「写出去的日志」与「解析回来的样本」严格对称。</p>
 *
 * <p>标注（哪些分片算相关）没法从日志里得到，由调用方用 {@link #parseLines} 的 labels 参数补，
 * 没有标注时样本的相关集合为空 —— 此时仍可用 {@link RetrievalEvaluator#p95OfRelevant} 之外的方式
 * 观察分布，但要算 recall 必须有标注。</p>
 *
 * <p>纯逻辑（只依赖 Gson），可直接单测。</p>
 */
public final class RetrievalLogParser {

    /** 日志行里用于定位 payload 的标记，须与 {@link VectorSearchService} 的 debug 日志一致 */
    public static final String PREFIX = "检索明细: ";

    private static final Gson GSON = new Gson();

    private RetrievalLogParser() {
    }

    /** 一条 query 的解析结果 */
    public static final class ParsedQuery {

        public final String query;
        public final List<RetrievalEvaluator.Candidate> candidates;

        ParsedQuery(String query, List<RetrievalEvaluator.Candidate> candidates) {
            this.query = query;
            this.candidates = candidates;
        }
    }

    /**
     * 生成可解析的候选明细 payload（JSON）。
     * 用 JSON 而不是自定义分隔符，避免 query 里出现逗号 / 竖线 / 引号时解析错位。
     */
    public static String toLogPayload(String query, List<RetrievalEvaluator.Candidate> candidates) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("query", query == null ? "" : query);
        List<Map<String, Object>> list = new ArrayList<>();
        if (candidates != null) {
            for (RetrievalEvaluator.Candidate c : candidates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.id);
                item.put("score", c.score);
                list.add(item);
            }
        }
        root.put("candidates", list);
        return GSON.toJson(root);
    }

    /** 解析单行日志；不含标记或解析失败返回 empty（日志格式变化时不会把流程搞崩） */
    public static Optional<ParsedQuery> parseLine(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String json;
        int idx = line.indexOf(PREFIX);
        if (idx >= 0) {
            json = line.substring(idx + PREFIX.length()).trim();
        } else {
            // 也允许直接喂裸 payload（例如从日志里 grep 出 JSON 段）
            int brace = line.indexOf('{');
            if (brace < 0) {
                return Optional.empty();
            }
            json = line.substring(brace).trim();
        }
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            // 只认"候选明细"结构，避免把其它 JSON 日志误吞成样本
            if (!root.has("candidates")) {
                return Optional.empty();
            }
            String query = root.has("query") && !root.get("query").isJsonNull()
                    ? root.get("query").getAsString() : "";
            List<RetrievalEvaluator.Candidate> candidates = new ArrayList<>();
            if (root.has("candidates") && root.get("candidates").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("candidates")) {
                    JsonObject item = el.getAsJsonObject();
                    String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : null;
                    if (id == null) {
                        continue;
                    }
                    float score = item.has("score") && !item.get("score").isJsonNull()
                            ? item.get("score").getAsFloat() : Float.MAX_VALUE;
                    candidates.add(new RetrievalEvaluator.Candidate(id, score));
                }
            }
            return Optional.of(new ParsedQuery(query, candidates));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 批量解析日志行，并按 query 合并人工标注。
     *
     * @param lines  日志内容（可包含任意其他行，非明细行会被忽略）
     * @param labels query → 相关分片 id；为 null 时所有样本的相关集合为空
     */
    public static List<RetrievalEvaluator.Sample> parseLines(List<String> lines,
                                                            Map<String, List<String>> labels) {
        List<RetrievalEvaluator.Sample> samples = new ArrayList<>();
        if (lines == null) {
            return samples;
        }
        for (String line : lines) {
            parseLine(line).ifPresent(parsed -> {
                List<String> relevant = labels == null
                        ? List.of()
                        : labels.getOrDefault(parsed.query, List.of());
                samples.add(RetrievalEvaluator.Sample.of(parsed.query, relevant, parsed.candidates));
            });
        }
        return samples;
    }
}
