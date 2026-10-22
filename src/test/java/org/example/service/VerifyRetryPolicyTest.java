package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VerifyRetryPolicy 单测：退避序列、封顶、重试判定、文案。 */
class VerifyRetryPolicyTest {

    @Test
    @DisplayName("退避序列：首次立即，之后 base、2×base…；指数增长且封顶")
    void scheduleGrowth() {
        assertEquals(List.of(0L, 200L, 400L), VerifyRetryPolicy.delaySchedule(3, 200, 2000));
        assertEquals(List.of(0L, 200L, 400L, 800L), VerifyRetryPolicy.delaySchedule(4, 200, 2000));
        // 封顶：第 4 次本该 1600，第 5 次本该 3200 → 被压到 1000
        assertEquals(List.of(0L, 200L, 400L, 800L, 1000L), VerifyRetryPolicy.delaySchedule(5, 200, 1000));
    }

    @Test
    @DisplayName("参数退化：attempts<1 按 1；base<=0 按 1ms；max<=0 按 base")
    void scheduleDegenerate() {
        assertEquals(List.of(0L), VerifyRetryPolicy.delaySchedule(0, 200, 2000));
        assertEquals(List.of(0L, 1L), VerifyRetryPolicy.delaySchedule(2, 0, 0));
        assertEquals(1, VerifyRetryPolicy.delaySchedule(-5, 200, 2000).size(), "attempts<1 归一为 1");
    }

    @Test
    @DisplayName("重试判定：通过即停；次数用尽即停")
    void shouldRetrySemantics() {
        assertTrue(VerifyRetryPolicy.shouldRetry(1, false, 3));
        assertTrue(VerifyRetryPolicy.shouldRetry(2, false, 3));
        assertFalse(VerifyRetryPolicy.shouldRetry(3, false, 3), "最后一次失败后不应再重试");
        assertFalse(VerifyRetryPolicy.shouldRetry(1, true, 3), "已通过就不重试");
        assertFalse(VerifyRetryPolicy.shouldRetry(1, false, 1), "只允许 1 次时没有重试");
    }

    @Test
    @DisplayName("默认值：3 次尝试 / 200ms 基数 / 2s 上限")
    void defaults() {
        assertEquals(3, VerifyRetryPolicy.DEFAULT_ATTEMPTS);
        assertEquals(200L, VerifyRetryPolicy.DEFAULT_BASE_MILLIS);
        assertEquals(2000L, VerifyRetryPolicy.DEFAULT_MAX_MILLIS);
        assertEquals(List.of(0L, 200L, 400L), VerifyRetryPolicy.delaySchedule(
                VerifyRetryPolicy.DEFAULT_ATTEMPTS,
                VerifyRetryPolicy.DEFAULT_BASE_MILLIS,
                VerifyRetryPolicy.DEFAULT_MAX_MILLIS));
    }

    @Test
    @DisplayName("文案区分「第几次通过」与「重试用尽」")
    void describeText() {
        assertTrue(VerifyRetryPolicy.describe("写入", 2, true).contains("第 2 次尝试"));
        String failed = VerifyRetryPolicy.describe("旧版本清理", 3, false);
        assertTrue(failed.contains("重试 3 次"), failed);
        assertTrue(failed.contains("仍未通过"), failed);
    }
}
