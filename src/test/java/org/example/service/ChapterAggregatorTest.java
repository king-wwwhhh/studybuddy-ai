package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ChapterAggregator 单测：同章合并、跨章不合并、无章节不合并、去重、预算截断、JSON 溯源。 */
class ChapterAggregatorTest {

    private static VectorSearchService.SearchResult r(String id, String chapter, String content, float score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setHeadingPath(chapter);
        result.setContent(content);
        result.setScore(score);
        return result;
    }

    @Test
    @DisplayName("相邻同章节 → 合并成一组，文本顺序拼接，来源都保留")
    void mergesAdjacentSameChapter() {
        List<VectorSearchService.SearchResult> results = List.of(
                r("a1", "第一章 > 1.1", "第一段内容", 0.3f),
                r("a2", "第一章 > 1.1", "第二段内容", 0.5f));

        List<ChapterAggregator.Group> groups = ChapterAggregator.group(results, 2000);

        assertEquals(1, groups.size());
        assertEquals("第一章 > 1.1", groups.get(0).chapter);
        assertEquals(2, groups.get(0).size());
        assertEquals("第一段内容\n第二段内容", groups.get(0).text);
    }

    @Test
    @DisplayName("跨章节（即使章节名相同但不相邻）→ 不合并")
    void doesNotMergeAcrossChapters() {
        List<VectorSearchService.SearchResult> results = List.of(
                r("a1", "第一章", "甲内容", 0.2f),
                r("b1", "第二章", "乙内容", 0.4f),
                r("a2", "第一章", "丙内容", 0.6f));

        List<ChapterAggregator.Group> groups = ChapterAggregator.group(results, 2000);

        assertEquals(3, groups.size(), "中间隔了别的章节就应断开");
        assertEquals("甲内容", groups.get(0).text);
        assertEquals("乙内容", groups.get(1).text);
        assertEquals("丙内容", groups.get(2).text);
    }

    @Test
    @DisplayName("没有章节信息 → 各自成组，绝不合并")
    void blankChapterNeverMerges() {
        List<VectorSearchService.SearchResult> results = List.of(
                r("x1", null, "内容一", 0.1f),
                r("x2", "   ", "内容二", 0.2f));

        List<ChapterAggregator.Group> groups = ChapterAggregator.group(results, 2000);

        assertEquals(2, groups.size());
        assertFalse(groups.get(0).text.contains("内容二"));
    }

    @Test
    @DisplayName("重叠片段去重：后段已包含在前段里就跳过，不重复拼")
    void dedupesOverlappingContent() {
        List<VectorSearchService.SearchResult> results = List.of(
                r("a1", "第一章", "向量检索的原理是计算相似度。", 0.2f),
                r("a2", "第一章", "计算相似度。", 0.3f)); // 重叠窗口造成的重复片段

        List<ChapterAggregator.Group> groups = ChapterAggregator.group(results, 2000);

        assertEquals(1, groups.size());
        assertEquals("向量检索的原理是计算相似度。", groups.get(0).text, "重复片段不应再拼一次");
        assertEquals(2, groups.get(0).size(), "来源仍保留两条，便于溯源");
    }

    @Test
    @DisplayName("超字符预算 → 停止合并，剩余条目各自成组（不丢内容）")
    void respectsCharBudget() {
        List<VectorSearchService.SearchResult> results = List.of(
                r("a1", "第一章", "A".repeat(60), 0.2f),
                r("a2", "第一章", "B".repeat(60), 0.3f),
                r("a3", "第一章", "C".repeat(60), 0.4f));

        List<ChapterAggregator.Group> groups = ChapterAggregator.group(results, 100);

        assertEquals(3, groups.size(), "每组只装得下 1 条，应各成一组");
        int total = groups.stream().mapToInt(g -> g.charCount()).sum();
        assertEquals(180, total, "内容不能丢");
    }

    @Test
    @DisplayName("JSON 渲染包含 chapter / content / sources(id,score)，可直接给模型")
    void renderJsonHasTraceability() {
        List<ChapterAggregator.Group> groups = ChapterAggregator.group(List.of(
                r("a1", "第一章 > 1.1", "正文一", 0.31f),
                r("a2", "第一章 > 1.1", "正文二", 0.62f)), 2000);

        String json = ChapterAggregator.renderJson(groups);

        assertTrue(json.contains("\"chapter\":\"第一章 > 1.1\""), json);
        assertTrue(json.contains("正文一\\n正文二") || json.contains("正文一\n正文二"), json);
        assertTrue(json.contains("\"id\":\"a1\""), json);
        assertTrue(json.contains("\"id\":\"a2\""), json);
        assertTrue(json.contains("0.31"), json);
    }

    @Test
    @DisplayName("边界：空列表/null → 空组；renderJson(null) → 空数组")
    void edges() {
        assertTrue(ChapterAggregator.group(List.of(), 2000).isEmpty());
        assertTrue(ChapterAggregator.group(null, 2000).isEmpty());
        assertEquals("[]", ChapterAggregator.renderJson(null));
        assertEquals("[]", ChapterAggregator.renderJson(List.of()));
    }
}
