package org.example.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 章节多样性选择：给「同一章节最多保留 N 条」加约束，把上下文名额让给其他章节。
 *
 * <p>问题：检索返回的 TopK 是按相似度排序的前 N 条，**很可能全部来自同一章节的相邻段落** ——
 * 三片讲的是同一件事，等于白白占掉上下文预算，而其他章节的相关内容一条都进不来。长文档里这种
 * "同章节挤压"非常常见，直接表现就是"答案只覆盖了一部分，另一部分资料明明也在库里"。</p>
 *
 * <p>策略：按原顺序扫一遍，同章节超过 {@code maxPerChapter} 的跳过；如果因为限制没凑满
 * {@code limit}，再按原顺序把跳过的补回来 —— <b>多样性优先，但绝不因此减少返回条数</b>。
 * 没有章节信息的条目（`headingPath` 为空）互不挤占，各自算一条。</p>
 *
 * <p>纯逻辑、无 Spring 依赖，可直接单测。</p>
 */
public final class ChapterDiversitySelector {

    private ChapterDiversitySelector() {
    }

    /**
     * @param ranked       已按相关度排好序的候选
     * @param chapterOf    取章节键的函数（返回 null/空白表示"无章节"）
     * @param limit        最终保留条数
     * @param maxPerChapter 单章节上限（&lt;1 时按 1 处理）
     */
    public static <T> List<T> select(List<T> ranked, Function<T, String> chapterOf,
                                     int limit, int maxPerChapter) {
        if (ranked == null || ranked.isEmpty() || limit <= 0) {
            return new ArrayList<>();
        }
        int cap = Math.max(1, maxPerChapter);
        List<T> picked = new ArrayList<>();
        List<T> skipped = new ArrayList<>();
        Map<String, Integer> perChapter = new HashMap<>();
        int unknownSeq = 0;

        for (T item : ranked) {
            if (picked.size() >= limit) {
                break;
            }
            String chapter = chapterOf == null ? null : chapterOf.apply(item);
            // 无章节信息的条目各自独立，避免它们互相挤占
            String key = (chapter == null || chapter.isBlank()) ? "__unknown_" + (unknownSeq++) : chapter;
            int used = perChapter.getOrDefault(key, 0);
            if (used >= cap) {
                skipped.add(item);
                continue;
            }
            perChapter.put(key, used + 1);
            picked.add(item);
        }

        // 多样性限制导致没凑满 → 按原顺序回补，保证返回条数不缩水
        for (T item : skipped) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(item);
        }
        return picked;
    }
}
