package org.example.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 写入 / 删除后的读回校验：判定「真的写进去了」「真的删掉了」。
 *
 * <p>背景：第 3 轮只校验了**插入 RPC 返回的条数**。但"RPC 说成功了"不等于"数据现在查得到"：
 * Milvus 的写入要等 segment 落盘/可见，删除是墓碑标记，短暂窗口里可能仍可见。没有读回校验，
 * "写入成功"只是一个中间信号。</p>
 *
 * <p>本类只做纯逻辑：采样、判定、日志描述。真正发 query 的部分在 `FileIngestService`
 * （不可用时跳过校验，绝不因为"校验做不了"而阻塞写入）。</p>
 */
public final class WriteVerification {

    /** 默认抽样条数：全量读回在大文件上代价高，抽样足够发现"整批不可见"这类问题 */
    public static final int DEFAULT_SAMPLE = 20;

    private WriteVerification() {
    }

    /**
     * 均匀采样：**包含首尾**并尽量等距，避免只校验前 N 条（
     * 分片按顺序写入，若尾部写入失败，只看前 N 条会漏判）。
     */
    public static List<String> sample(List<String> ids, int sampleSize) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        int size = Math.max(1, Math.min(sampleSize, ids.size()));
        if (size >= ids.size()) {
            return new ArrayList<>(ids);
        }
        Set<String> picked = new LinkedHashSet<>();
        if (size == 1) {
            picked.add(ids.get(0));
        } else {
            for (int k = 0; k < size; k++) {
                int idx = (int) Math.round((double) k * (ids.size() - 1) / (size - 1));
                picked.add(ids.get(Math.max(0, Math.min(idx, ids.size() - 1))));
            }
        }
        return new ArrayList<>(picked);
    }

    /**
     * 读回判定：返回条数必须等于期望条数。
     * 期望 &lt;= 0 视为无效校验（没有可校验的样本），返回 false 让调用方显式处理。
     */
    public static boolean verified(int expected, int actual) {
        return expected > 0 && expected == actual;
    }

    /** 日志描述：`阶段 期望/实际 条数` */
    public static String describe(String stage, int expected, int actual) {
        return stage + " 读回校验: 期望 " + expected + " 条, 实际 " + actual + " 条";
    }

    /**
     * 删除后判定：抽样主键**一条都不应再被查到**。
     *
     * <p>注意墓碑语义：Milvus 删除是打墓碑，物理回收要等 compact；但带 Strong 一致性的查询
     * 不应再返回已删除实体。所以"仍能查到"是**异常信号**（要么删除没生效，要么该主键被重新写入），
     * 值得告警，但不应回滚——删除无法撤销，只能靠后续重建收敛。</p>
     */
    public static boolean deletionVerified(int remaining) {
        return remaining == 0;
    }

    /** 删除校验的日志描述 */
    public static String describeDeletion(String stage, int sampled, int remaining) {
        return stage + " 删除校验: 抽样 " + sampled + " 条, 仍可见 " + remaining + " 条"
                + (remaining == 0 ? "" : "（可能是墓碑尚未 compact，或该主键被重新写入）");
    }
}
