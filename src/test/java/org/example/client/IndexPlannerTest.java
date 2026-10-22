package org.example.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IndexPlanner 单测：把「按规模选型」和「参数换算规则」钉死，避免以后改回去。
 */
class IndexPlannerTest {

    @Test
    @DisplayName("auto：≤5 万走 FLAT，5 万~500 万走 HNSW，更大走 IVF_FLAT")
    void autoTypeByRows() {
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("auto", 0));
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("auto", 1_000));
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("auto", IndexPlanner.FLAT_MAX_ROWS));
        assertEquals(IndexPlanner.HNSW, IndexPlanner.resolveType("auto", IndexPlanner.FLAT_MAX_ROWS + 1));
        assertEquals(IndexPlanner.HNSW, IndexPlanner.resolveType("auto", 1_000_000));
        assertEquals(IndexPlanner.IVF_FLAT, IndexPlanner.resolveType("auto", IndexPlanner.HNSW_MAX_ROWS + 1));
    }

    @Test
    @DisplayName("显式配置优先；大小写不敏感；未知取值退回 auto（配置拼错不该让应用起不来）")
    void explicitTypeWins() {
        assertEquals(IndexPlanner.HNSW, IndexPlanner.resolveType("hnsw", 10));
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("FLAT", 9_000_000));
        assertEquals(IndexPlanner.IVF_FLAT, IndexPlanner.resolveType(" ivf_flat ", 10));
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("HNSWW", 10)); // 拼错 → auto → FLAT
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType(null, 10));
        assertEquals(IndexPlanner.FLAT, IndexPlanner.resolveType("", 10));
    }

    @Test
    @DisplayName("nlist：4·√N 起步，夹在 [64,65536]，且满足 N ≥ 39·nlist")
    void nlistFormula() {
        assertEquals(64, IndexPlanner.nlistFor(0));
        assertEquals(64, IndexPlanner.nlistFor(100));          // 小数据：宁可退化
        // 1 万条：4·√10000=400，但 10000/39=256 → 守卫下调到 256（N ≥ 39·nlist）
        assertEquals(256, IndexPlanner.nlistFor(10_000));
        // 250 万条：4·√2500000≈6324.6 → 6325，守卫不触发（2.5e6/39=64102）
        assertEquals(6325, IndexPlanner.nlistFor(2_500_000));
        assertTrue(IndexPlanner.nlistFor(100_000_000L) <= 65536);

        // 任何规模都必须满足 N ≥ 39·nlist（否则 k-means 聚类不稳）
        long[] rows = {1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000L};
        for (long r : rows) {
            int n = IndexPlanner.nlistFor(r);
            assertTrue(r >= 39L * n || n == 64, "rows=" + r + " nlist=" + n + " 违反 N>=39·nlist");
        }
    }

    @Test
    @DisplayName("nlist 随行数单调不减")
    void nlistMonotonic() {
        int prev = 0;
        for (long r = 1_000; r <= 10_000_000; r *= 10) {
            int n = IndexPlanner.nlistFor(r);
            assertTrue(n >= prev, "nlist 在 rows=" + r + " 时回退: " + n + " < " + prev);
            prev = n;
        }
    }

    @Test
    @DisplayName("建索引参数：FLAT 无参数、HNSW 有 M/efConstruction、IVF 有 nlist")
    void extraParams() {
        assertEquals("{}", IndexPlanner.buildExtraParams(IndexPlanner.FLAT, 1_000, 0, 16, 200));
        assertEquals("{\"M\":16,\"efConstruction\":200}",
                IndexPlanner.buildExtraParams(IndexPlanner.HNSW, 1_000, 0, 16, 200));
        assertEquals("{\"nlist\":256}",                            // 1 万条被 39·nlist 守卫压到 256
                IndexPlanner.buildExtraParams(IndexPlanner.IVF_FLAT, 10_000, 0, 16, 200));
        assertEquals("{\"nlist\":128}",
                IndexPlanner.buildExtraParams(IndexPlanner.IVF_FLAT, 10_000, 128, 16, 200));
    }

    @Test
    @DisplayName("检索参数：HNSW 的 ef 必须 ≥ topK；IVF 用 nprobe；FLAT 为空")
    void searchParams() {
        assertEquals("{}", IndexPlanner.buildSearchParams(IndexPlanner.FLAT, 10, 64, 10));
        assertEquals("{\"nprobe\":10}", IndexPlanner.buildSearchParams(IndexPlanner.IVF_FLAT, 10, 64, 10));
        assertEquals("{\"nprobe\":1}", IndexPlanner.buildSearchParams(IndexPlanner.IVF_FLAT, 0, 64, 10));

        // ef=64 但 topK=200 → 必须抬到 200，否则 HNSW 只有 64 个候选，TopK 被截断
        assertEquals("{\"ef\":200}", IndexPlanner.buildSearchParams(IndexPlanner.HNSW, 10, 64, 200));
        assertEquals("{\"ef\":128}", IndexPlanner.buildSearchParams(IndexPlanner.HNSW, 10, 128, 10));
        assertEquals("{\"ef\":64}", IndexPlanner.buildSearchParams(IndexPlanner.HNSW, 10, 8, 10));
    }
}
