package org.example.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章节聚合：把**连续命中同一章节**的分片合并成一段连贯文本。
 *
 * <p>问题：分片是 800 字符窗口切出来的，同一小节常常被切成 2~3 片并同时被召回 ——
 * 模型看到的是三段互不衔接的碎片，还得自己拼。合并成一段能显著降低理解成本，
 * 同时保留每个来源分片的 id/score，溯源不丢。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只有**相邻**且 `headingPath` 相同（且非空）才合并 —— 跨章不合并，即使章节名相同；</li>
 *   <li>合并时按原顺序拼接，若后段文本已被前段完整包含则跳过（避免重叠窗口造成的重复）；</li>
 *   <li>超过字符预算就停止合并，剩余条目各自成组 —— <b>宁可多一组，也不丢内容</b>；</li>
 *   <li>`headingPath` 为空的条目永不合并。</li>
 * </ul>
 *
 * <p>纯逻辑（只依赖 Gson 做渲染），可直接单测。</p>
 */
public final class ChapterAggregator {

    /**
     * 关闭 HTML 转义：章节路径的分隔符就是 ` > `，默认转义会把每段 chapter 变成 `\u003e`，
     * 人类与模型都更难读（JSON 合法，但没必要）。
     */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 默认合并后单组字符上限：约为 2~3 个分片的正文量 */
    public static final int DEFAULT_MAX_CHARS = 2000;

    private ChapterAggregator() {
    }

    /** 一组：同一章节的连续命中 + 合并后的文本 */
    public static final class Group {

        public final String chapter;
        public final List<VectorSearchService.SearchResult> sources;
        public final String text;

        Group(String chapter, List<VectorSearchService.SearchResult> sources, String text) {
            this.chapter = chapter;
            this.sources = sources;
            this.text = text;
        }

        public int size() {
            return sources.size();
        }

        public int charCount() {
            return text == null ? 0 : text.length();
        }
    }

    /**
     * 按章节聚合。
     *
     * @param results  已按相关度排序的检索结果
     * @param maxChars 单组合并后字符上限（&le;0 时用 {@link #DEFAULT_MAX_CHARS}）
     */
    public static List<Group> group(List<VectorSearchService.SearchResult> results, int maxChars) {
        List<Group> groups = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            return groups;
        }
        int budget = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;

        List<VectorSearchService.SearchResult> current = new ArrayList<>();
        String currentChapter = null;
        StringBuilder currentText = new StringBuilder();

        for (VectorSearchService.SearchResult r : results) {
            String chapter = r.getHeadingPath();
            String content = r.getContent() == null ? "" : r.getContent().trim();
            boolean mergeable = currentChapter != null && !isBlank(chapter) && chapter.equals(currentChapter);
            boolean fits = currentText.length() + content.length() + 1 <= budget;

            if (!current.isEmpty() && mergeable && fits) {
                appendUnique(currentText, content);
                current.add(r);
                continue;
            }
            if (!current.isEmpty()) {
                groups.add(new Group(currentChapter, new ArrayList<>(current), currentText.toString()));
            }
            current = new ArrayList<>();
            currentChapter = isBlank(chapter) ? null : chapter;
            currentText = new StringBuilder(content);
            current.add(r);
        }
        if (!current.isEmpty()) {
            groups.add(new Group(currentChapter, new ArrayList<>(current), currentText.toString()));
        }
        return groups;
    }

    /** 渲染成工具返回的 JSON：`[{chapter, content, sources:[{id,score}]}]` */
    public static String renderJson(List<Group> groups) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (groups != null) {
            for (Group g : groups) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chapter", g.chapter == null ? "" : g.chapter);
                item.put("content", g.text);
                List<Map<String, Object>> sources = new ArrayList<>();
                for (VectorSearchService.SearchResult r : g.sources) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", r.getId());
                    s.put("score", r.getScore());
                    sources.add(s);
                }
                item.put("sources", sources);
                payload.add(item);
            }
        }
        return GSON.toJson(payload);
    }

    /** 追加内容，跳过"已被包含"的重复（重叠窗口常见） */
    private static void appendUnique(StringBuilder sb, String content) {
        if (content.isEmpty()) {
            return;
        }
        if (sb.indexOf(content) >= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(content);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
