package org.example.service;

import java.util.List;

/**
 * 索引类型变更的判定与执行步骤（纯逻辑）。
 *
 * <p>问题：索引类型只在**建 collection 时**决定。之后改 `milvus.index.type` 不会生效；
 * 手工改又很容易踩两个坑 —— Milvus 要求先 `dropIndex` 才能建新索引（且 2.2.12 的
 * `DropIndexParam` 只认 `indexName`，不认字段名），而且重建期间检索会短暂不可用。</p>
 *
 * <p>所以把"要不要重建 / 怎么重建"变成显式判定：默认**不自动动手**（返回 MANUAL + 操作指引），
 * 只有显式开启 `milvus.index.auto-rebuild` 且拿到现有索引名时才允许 REBUILD。</p>
 */
public final class IndexSwitchPlan {

    /** NOOP = 类型一致；REBUILD = 可以安全重建；MANUAL = 需要人工介入 */
    public enum Action {
        NOOP, REBUILD, MANUAL
    }

    private final Action action;
    private final String currentType;
    private final String desiredType;
    private final String currentIndexName;
    private final String extraParams;
    private final String reason;

    private IndexSwitchPlan(Action action, String currentType, String desiredType,
                            String currentIndexName, String extraParams, String reason) {
        this.action = action;
        this.currentType = currentType;
        this.desiredType = desiredType;
        this.currentIndexName = currentIndexName;
        this.extraParams = extraParams;
        this.reason = reason;
    }

    /**
     * @param currentType      现有索引类型（来自 describeIndex，可能为 null/空 = 未知）
     * @param currentIndexName 现有索引名（2.2.12 只能按名字 drop）
     * @param desiredType      目标索引类型（FLAT / HNSW / IVF_FLAT）
     * @param extraParams      目标类型的建索引参数（由 {@link IndexPlanner#buildExtraParams} 生成）
     * @param autoRebuild      是否允许自动重建
     */
    public static IndexSwitchPlan decide(String currentType, String currentIndexName,
                                         String desiredType, String extraParams, boolean autoRebuild) {
        String cur = normalize(currentType);
        String des = normalize(desiredType);

        if (cur.isEmpty()) {
            return new IndexSwitchPlan(Action.MANUAL, cur, des, currentIndexName, extraParams,
                    "无法识别现有索引类型（describeIndex 未返回），不做任何索引变更");
        }
        if (cur.equals(des)) {
            return new IndexSwitchPlan(Action.NOOP, cur, des, currentIndexName, extraParams,
                    "索引类型一致，无需变更");
        }
        if (!autoRebuild) {
            return new IndexSwitchPlan(Action.MANUAL, cur, des, currentIndexName, extraParams,
                    "索引类型从 " + cur + " 变更为 " + des + "：默认不自动重建（重建期间检索会短暂不可用）。"
                            + "确认后设 milvus.index.auto-rebuild=true 重启，或手工 dropIndex 后重启");
        }
        if (currentIndexName == null || currentIndexName.isBlank()) {
            return new IndexSwitchPlan(Action.MANUAL, cur, des, currentIndexName, extraParams,
                    "拿不到现有索引名，无法安全 dropIndex（Milvus 2.2.12 的 DropIndexParam 只接受 indexName）");
        }
        return new IndexSwitchPlan(Action.REBUILD, cur, des, currentIndexName, extraParams,
                "索引类型从 " + cur + " 变更为 " + des + "：先 dropIndex 再按目标类型重建");
    }

    /** 执行步骤（仅 REBUILD 有内容），便于日志与测试断言 */
    public List<String> steps() {
        if (action != Action.REBUILD) {
            return List.of();
        }
        return List.of(
                "dropIndex(" + currentIndexName + ")",
                "createIndex(" + desiredType + ", " + extraParams + ")");
    }

    public Action getAction() {
        return action;
    }

    public String getCurrentType() {
        return currentType;
    }

    public String getDesiredType() {
        return desiredType;
    }

    public String getCurrentIndexName() {
        return currentIndexName;
    }

    public String getExtraParams() {
        return extraParams;
    }

    public String getReason() {
        return reason;
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toUpperCase();
    }
}
