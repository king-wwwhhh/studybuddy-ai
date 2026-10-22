package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ChapterDiversitySelector 单测：同章节限流、跨章节覆盖、回补不缩水、无章节独立。 */
class ChapterDiversitySelectorTest {

    /** 测试用条目：id + 章节 */
    private record Item(String id, String chapter) {
    }

    private static final java.util.function.Function<Item, String> CHAPTER = Item::chapter;

    private static Item item(String id, String chapter) {
        return new Item(id, chapter);
    }

    @Test
    @DisplayName("同章节超限时跳过，把名额让给其他章节")
    void limitsPerChapter() {
        List<Item> ranked = List.of(
                item("a1", "第一章"),
                item("a2", "第一章"),
                item("a3", "第一章"),
                item("b1", "第二章"),
                item("c1", "第三章"));

        List<Item> picked = ChapterDiversitySelector.select(ranked, CHAPTER, 3, 1);
        assertEquals(List.of("a1", "b1", "c1"), ids(picked), "每章最多 1 条时应收敛为跨章覆盖");
    }

    @Test
    @DisplayName("上限=2 时同章节最多两条，其余名额给别的章节")
    void maxPerChapterTwo() {
        List<Item> ranked = List.of(
                item("a1", "A"), item("a2", "A"), item("a3", "A"), item("b1", "B"), item("b2", "B"));

        List<Item> picked = ChapterDiversitySelector.select(ranked, CHAPTER, 4, 2);
        assertEquals(List.of("a1", "a2", "b1", "b2"), ids(picked));
    }

    @Test
    @DisplayName("多样性限制导致凑不满 → 回补，返回条数不缩水")
    void backfillsWhenNotEnoughChapters() {
        List<Item> ranked = List.of(item("a1", "A"), item("a2", "A"), item("a3", "A"));

        List<Item> picked = ChapterDiversitySelector.select(ranked, CHAPTER, 3, 1);
        assertEquals(List.of("a1", "a2", "a3"), ids(picked), "只有一章时必须回补，不能只返回 1 条");
    }

    @Test
    @DisplayName("无章节信息的条目互不挤占")
    void unknownChaptersAreIndependent() {
        List<Item> ranked = List.of(item("u1", null), item("u2", ""), item("u3", "  "), item("a1", "A"));

        List<Item> picked = ChapterDiversitySelector.select(ranked, CHAPTER, 3, 1);
        assertEquals(List.of("u1", "u2", "u3"), ids(picked), "无章节条目不应被当成同一章压掉");
    }

    @Test
    @DisplayName("顺序保持：选出的条目仍按原来的相关度顺序")
    void keepsOrder() {
        List<Item> ranked = List.of(
                item("a1", "A"), item("b1", "B"), item("a2", "A"), item("c1", "C"), item("b2", "B"));

        List<Item> picked = ChapterDiversitySelector.select(ranked, CHAPTER, 4, 1);
        assertEquals(List.of("a1", "b1", "c1", "a2"), ids(picked));
    }

    @Test
    @DisplayName("边界：空输入 / limit<=0 / maxPerChapter<=0 / chapterOf 为 null")
    void edges() {
        assertTrue(ChapterDiversitySelector.select(List.of(), CHAPTER, 3, 2).isEmpty());
        assertTrue(ChapterDiversitySelector.select(null, CHAPTER, 3, 2).isEmpty());
        assertTrue(ChapterDiversitySelector.select(List.of(item("a", "A")), CHAPTER, 0, 2).isEmpty());

        // maxPerChapter=0 → 按 1 处理
        List<Item> picked = ChapterDiversitySelector.select(
                List.of(item("a1", "A"), item("a2", "A"), item("b1", "B")), CHAPTER, 3, 0);
        assertEquals(List.of("a1", "b1", "a2"), ids(picked));

        // chapterOf 为 null → 全部视为无章节，互不挤占
        List<Item> noFunc = ChapterDiversitySelector.select(
                List.of(item("a1", "A"), item("a2", "A")), null, 2, 1);
        assertEquals(List.of("a1", "a2"), ids(noFunc));
    }

    private static List<String> ids(List<Item> items) {
        return items.stream().map(Item::id).toList();
    }
}
