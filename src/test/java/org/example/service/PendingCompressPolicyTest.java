package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PendingCompressPolicy 单测：移出即压缩 + 暂存上限。 */
class PendingCompressPolicyTest {

    @Test
    @DisplayName("默认阈值 = 2：一对消息移出窗口就触发压缩（灰区最多 1 条）")
    void triggerIsPair() {
        assertEquals(2, PendingCompressPolicy.TRIGGER);
        assertFalse(PendingCompressPolicy.shouldCompress(0));
        assertFalse(PendingCompressPolicy.shouldCompress(1));
        assertTrue(PendingCompressPolicy.shouldCompress(2));
        assertTrue(PendingCompressPolicy.shouldCompress(9));
    }

    @Test
    @DisplayName("自定义阈值：<=0 时归一为 1，避免永不触发")
    void customTrigger() {
        assertTrue(PendingCompressPolicy.shouldCompress(1, 0));
        assertTrue(PendingCompressPolicy.shouldCompress(1, -3));
        assertFalse(PendingCompressPolicy.shouldCompress(4, 5));
        assertTrue(PendingCompressPolicy.shouldCompress(5, 5));
    }

    @Test
    @DisplayName("暂存上限：超出部分条数（摘要连续失败时的保护）")
    void overflow() {
        assertEquals(20, PendingCompressPolicy.MAX_PENDING);
        assertEquals(0, PendingCompressPolicy.overflow(0, 20));
        assertEquals(0, PendingCompressPolicy.overflow(20, 20));
        assertEquals(5, PendingCompressPolicy.overflow(25, 20));
        assertEquals(2, PendingCompressPolicy.overflow(3, 0), "上限<=0 归一为 1");
    }

    @Test
    @DisplayName("文案包含当前条数、阈值与上限，便于日志排查")
    void describeText() {
        String text = PendingCompressPolicy.describe(7);
        assertTrue(text.contains("7"), text);
        assertTrue(text.contains("2"), text);
        assertTrue(text.contains("20"), text);
    }
}
