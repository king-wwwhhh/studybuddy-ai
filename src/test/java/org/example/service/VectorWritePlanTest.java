package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VectorWritePlan 单测：主键版本化、旧版本精确清理、写入完整性判定。 */
class VectorWritePlanTest {

    private static final String SHA_A = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    private static final String SHA_B = "ffffffff1234567890abcdef1234567890abcdef1234567890abcdef1234567890";

    @Test
    @DisplayName("chunkId：同内容同分片 → 同一主键（幂等）；内容变或分片变 → 不同主键")
    void chunkIdDeterministic() {
        String a0 = VectorWritePlan.chunkId("笔记/ch1.md", SHA_A, 0);
        assertEquals(a0, VectorWritePlan.chunkId("笔记/ch1.md", SHA_A, 0), "同内容同分片必须稳定");
        assertNotEquals(a0, VectorWritePlan.chunkId("笔记/ch1.md", SHA_A, 1), "分片号不同应换主键");
        assertNotEquals(a0, VectorWritePlan.chunkId("笔记/ch1.md", SHA_B, 0), "内容版本不同应换主键");
        assertNotEquals(a0, VectorWritePlan.chunkId("笔记/ch2.md", SHA_A, 0), "source 不同应换主键");
        assertEquals(36, a0.length(), "UUID 字符串固定 36 字符（远小于 Milvus id 上限 256）");
    }

    @Test
    @DisplayName("versionOf：取 sha 前 8 位；空值退化到 0")
    void version() {
        assertEquals("abcdef12", VectorWritePlan.versionOf(SHA_A));
        assertEquals("abc", VectorWritePlan.versionOf("abc"));
        assertEquals("0", VectorWritePlan.versionOf(null));
        assertEquals("0", VectorWritePlan.versionOf("   "));
    }

    @Test
    @DisplayName("previousIds：覆盖历史方案与版本化方案，共 2×分片数，且不含新版本主键")
    void previousIds() {
        List<String> ids = VectorWritePlan.previousIds("笔记/ch1.md", SHA_A, 3);
        assertEquals(6, ids.size(), "两个方案 × 3 个分片");
        assertTrue(ids.contains(VectorWritePlan.legacyChunkId("笔记/ch1.md", 0)));
        assertTrue(ids.contains(VectorWritePlan.chunkId("笔记/ch1.md", SHA_A, 2)));
        // 新版本（内容已变）的主键不应出现在清理列表里
        assertFalse(ids.contains(VectorWritePlan.chunkId("笔记/ch1.md", SHA_B, 0)));
    }

    @Test
    @DisplayName("previousIds：没有旧分片 / 没有旧 sha 时不产生清理项")
    void previousIdsEdge() {
        assertTrue(VectorWritePlan.previousIds("a.md", SHA_A, 0).isEmpty());
        assertTrue(VectorWritePlan.previousIds(null, SHA_A, 5).isEmpty());
        List<String> noSha = VectorWritePlan.previousIds("a.md", null, 2);
        assertEquals(2, noSha.size(), "缺旧 sha 时只清理历史方案主键");
    }

    @Test
    @DisplayName("deleteExprByIds：生成 id in [...] ；空集合返回 null")
    void deleteExpr() {
        assertNull(VectorWritePlan.deleteExprByIds(List.of()));
        assertNull(VectorWritePlan.deleteExprByIds(null));
        assertEquals("id in [\"a\",\"b\"]", VectorWritePlan.deleteExprByIds(List.of("a", "b")));
        // 引号与反斜杠要转义，避免拼出非法表达式
        assertEquals("id in [\"a\\\"b\",\"c\\\\d\"]", VectorWritePlan.deleteExprByIds(List.of("a\"b", "c\\d")));
    }

    @Test
    @DisplayName("writeComplete：条数吻合才算完整；服务端不回传条数时按每批成功判定")
    void writeComplete() {
        assertTrue(VectorWritePlan.writeComplete(List.of(10L, 10L), 20));
        assertFalse(VectorWritePlan.writeComplete(List.of(10L), 20), "少一批应判失败");
        assertFalse(VectorWritePlan.writeComplete(List.of(10L, 5L), 20), "条数不足应判失败");
        assertTrue(VectorWritePlan.writeComplete(List.of(0L, 0L), 20), "服务端不回传条数时视为成功");
        assertFalse(VectorWritePlan.writeComplete(List.of(), 20), "没有任何批次应判失败");
        assertFalse(VectorWritePlan.writeComplete(List.of(1L), 0), "期望 0 条本身是异常输入");
        assertFalse(VectorWritePlan.writeComplete(null, 20));
    }

    @Test
    @DisplayName("batchIds：按上限切批、尾部保留零头；批大小非法时退化为 1（防死循环）")
    void batchIds() {
        assertTrue(VectorWritePlan.batchIds(null, 200).isEmpty());
        assertTrue(VectorWritePlan.batchIds(List.of(), 200).isEmpty());
        assertEquals(1, VectorWritePlan.batchIds(List.of("a"), 200).size());

        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 450; i++) {
            ids.add("id" + i);
        }
        List<List<String>> batches = VectorWritePlan.batchIds(ids, 200);
        assertEquals(3, batches.size());
        assertEquals(200, batches.get(0).size());
        assertEquals(200, batches.get(1).size());
        assertEquals(50, batches.get(2).size());
        assertEquals("id0", batches.get(0).get(0));
        assertEquals("id449", batches.get(2).get(49));

        assertEquals(450, VectorWritePlan.batchIds(ids, 0).size(), "batchSize<=0 应退化为 1");
        assertEquals(450, VectorWritePlan.batchIds(ids, -5).size());
        assertEquals(200, VectorWritePlan.DEFAULT_DELETE_BATCH);
    }
}
