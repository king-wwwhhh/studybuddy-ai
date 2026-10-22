package org.example.service;

/**
 * 「移出窗口的消息」压缩策略（纯逻辑）。
 *
 * <p>原实现：窗口 12 条，超窗消息先进 `pendingCompress`，**攒够 8 条**才做一次摘要压缩。
 * 于是存在一个"灰区"：消息已经不在窗口里、也还没进摘要，模型完全读不到（用户问"刚才那个约束"会得到"没提过"）。</p>
 *
 * <p>本策略把灰区压到最小：**移出即压缩（trigger=2）**；同时给暂存区一个上限，
 * 摘要连续失败时不至于无限增长（超过上限丢最老的，并应打日志）。</p>
 */
public final class PendingCompressPolicy {

    /** 触发压缩的暂存条数：2 条（一对）即触发，灰区最多 1 条 */
    public static final int TRIGGER = 2;

    /** 暂存上限：摘要连续失败时的保护，超过则丢弃最老的消息 */
    public static final int MAX_PENDING = 20;

    private PendingCompressPolicy() {
    }

    /** 是否应该立即压缩（默认阈值） */
    public static boolean shouldCompress(int pendingSize) {
        return shouldCompress(pendingSize, TRIGGER);
    }

    public static boolean shouldCompress(int pendingSize, int trigger) {
        return pendingSize >= Math.max(1, trigger);
    }

    /** 超过上限的条数（0 表示无需裁剪） */
    public static int overflow(int pendingSize, int maxPending) {
        int max = Math.max(1, maxPending);
        return Math.max(0, pendingSize - max);
    }

    /** 日志用文案 */
    public static String describe(int pendingSize) {
        return "暂存待压缩消息 " + pendingSize + " 条（阈值 " + TRIGGER + "，上限 " + MAX_PENDING + "）";
    }
}
