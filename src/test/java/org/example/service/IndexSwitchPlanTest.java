package org.example.service;

import org.example.client.IndexPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IndexSwitchPlan 单测：默认不动手（MANUAL）、同名同型 NOOP、拿到索引名且显式允许才 REBUILD。 */
class IndexSwitchPlanTest {

    private static IndexSwitchPlan decide(String current, String indexName, String desired, boolean auto) {
        String extra = IndexPlanner.buildExtraParams(desired, 10_000, 0, 16, 200);
        return IndexSwitchPlan.decide(current, indexName, desired, extra, auto);
    }

    @Test
    @DisplayName("类型一致 → NOOP，不产生任何步骤")
    void sameTypeNoop() {
        IndexSwitchPlan plan = decide("IVF_FLAT", "vector_idx", "ivf_flat", false);
        assertEquals(IndexSwitchPlan.Action.NOOP, plan.getAction());
        assertTrue(plan.steps().isEmpty());
        assertTrue(plan.getReason().contains("一致"), plan.getReason());
    }

    @Test
    @DisplayName("类型变了但没开自动重建 → MANUAL，给出操作指引")
    void changeWithoutAutoIsManual() {
        IndexSwitchPlan plan = decide("IVF_FLAT", "vector_idx", "HNSW", false);
        assertEquals(IndexSwitchPlan.Action.MANUAL, plan.getAction());
        assertTrue(plan.steps().isEmpty(), "MANUAL 不应有可执行步骤");
        assertTrue(plan.getReason().contains("auto-rebuild"), plan.getReason());
        assertTrue(plan.getReason().contains("dropIndex"), plan.getReason());
    }

    @Test
    @DisplayName("开了自动重建且拿得到索引名 → REBUILD，步骤为 dropIndex + createIndex")
    void rebuildWhenAllowed() {
        IndexSwitchPlan plan = decide("IVF_FLAT", "vector_idx", "HNSW", true);
        assertEquals(IndexSwitchPlan.Action.REBUILD, plan.getAction());
        assertEquals(List.of(
                "dropIndex(vector_idx)",
                "createIndex(HNSW, {\"M\":16,\"efConstruction\":200})"), plan.steps());
    }

    @Test
    @DisplayName("开了自动重建但拿不到索引名 → MANUAL（2.2.12 只能按名字 drop）")
    void missingIndexNameIsManual() {
        IndexSwitchPlan plan = decide("IVF_FLAT", "  ", "FLAT", true);
        assertEquals(IndexSwitchPlan.Action.MANUAL, plan.getAction());
        assertTrue(plan.getReason().contains("indexName"), plan.getReason());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    @DisplayName("现有类型未知 → MANUAL，绝不猜测")
    void unknownCurrentIsManual() {
        assertEquals(IndexSwitchPlan.Action.MANUAL, decide(null, "idx", "FLAT", true).getAction());
        assertEquals(IndexSwitchPlan.Action.MANUAL, decide("", "idx", "FLAT", true).getAction());
    }

    @Test
    @DisplayName("目标类型的建索引参数会进入步骤（FLAT 为空、IVF 带 nlist）")
    void stepsCarryExtraParams() {
        assertEquals(List.of("dropIndex(idx)", "createIndex(FLAT, {})"),
                decide("HNSW", "idx", "FLAT", true).steps());
        assertEquals(List.of("dropIndex(idx)", "createIndex(IVF_FLAT, {\"nlist\":256})"),
                decide("HNSW", "idx", "IVF_FLAT", true).steps());
    }
}
