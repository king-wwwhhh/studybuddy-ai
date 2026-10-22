package org.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 读回校验的重试策略（纯逻辑）。
 *
 * <p>问题：第 11/12 轮的读回校验是"查一次就下结论"。但 Milvus 的可见性存在瞬时窗口
 * （写入刚成功、删除的墓碑尚未 compact），一次没查到既可能是"真的失败"，也可能只是"还没可见"。
 * 直接判失败会让上层放弃清理旧版本 / 误报；直接放宽又抓不到真问题。</p>
 *
 * <p>做法：有限次延迟复查。第 1 次立即查，之后按 `base, 2×base, 4×base…` 退避（有上限），
 * 任意一次通过即成功；次数用尽仍未通过才判失败。</p>
 */
public final class VerifyRetryPolicy {

    /** 默认总尝试次数（含第 1 次立即尝试） */
    public static final int DEFAULT_ATTEMPTS = 3;

    /** 默认退避基数（毫秒） */
    public static final long DEFAULT_BASE_MILLIS = 200L;

    /** 默认退避上限（毫秒），避免配置过大把请求线程卡住 */
    public static final long DEFAULT_MAX_MILLIS = 2000L;

    private VerifyRetryPolicy() {
    }

    /**
     * 生成每次尝试**之前**的等待时间序列（长度 = attempts，首元素恒为 0）。
     *
     * @param attempts  总尝试次数（&lt;1 时按 1）
     * @param baseMillis 退避基数（&le;0 时按 1ms）
     * @param maxMillis  单次等待上限（&le;0 时按基数）
     */
    public static List<Long> delaySchedule(int attempts, long baseMillis, long maxMillis) {
        int n = Math.max(1, attempts);
        long base = baseMillis > 0 ? baseMillis : 1L;
        long max = maxMillis > 0 ? maxMillis : base;
        List<Long> delays = new ArrayList<>(n);
        long delay = 0L;
        for (int i = 0; i < n; i++) {
            delays.add(Math.min(delay, max));
            delay = Math.min(delay == 0 ? base : delay * 2, max); // 指数退避，封顶
        }
        return delays;
    }

    /**
     * 是否还要再试一次。
     *
     * @param attempt   已完成的尝试次数（1-based）
     * @param satisfied 该次尝试是否通过
     * @param attempts  总尝试次数
     */
    public static boolean shouldRetry(int attempt, boolean satisfied, int attempts) {
        return !satisfied && attempt < Math.max(1, attempts);
    }

    /** 最终判定文案：通过 / 重试用尽 */
    public static String describe(String stage, int attemptsUsed, boolean satisfied) {
        if (satisfied) {
            return stage + " 校验通过（第 " + attemptsUsed + " 次尝试）";
        }
        return stage + " 校验失败：已重试 " + attemptsUsed + " 次仍未通过（瞬时不可见或数据确实有问题）";
    }
}
