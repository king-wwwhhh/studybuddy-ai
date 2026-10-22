package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WriteVerification 单测：采样覆盖首尾、判定语义、日志描述。 */
class WriteVerificationTest {

    private static List<String> ids(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "id" + i).toList();
    }

    @Test
    @DisplayName("采样少于总量时包含首尾、条数正确、无重复、保持原顺序")
    void sampleIncludesHeadAndTail() {
        List<String> all = ids(100);

        List<String> sampled = WriteVerification.sample(all, 5);
        assertEquals(5, sampled.size());
        assertEquals("id0", sampled.get(0), "必须包含第一条");
        assertEquals("id99", sampled.get(sampled.size() - 1), "必须包含最后一条");
        assertEquals(sampled.size(), new java.util.LinkedHashSet<>(sampled).size(), "不能有重复");
        assertEquals(sampled.stream().sorted(java.util.Comparator.comparingInt(
                        s -> Integer.parseInt(s.substring(2)))).toList(),
                sampled, "应保持原顺序");
    }

    @Test
    @DisplayName("采样数 >= 总量时返回全量副本")
    void sampleAllWhenSmall() {
        List<String> all = ids(3);
        assertEquals(all, WriteVerification.sample(all, 20));
        assertEquals(all, WriteVerification.sample(all, 3));
    }

    @Test
    @DisplayName("采样数 = 1 时取第一条；空输入 / null 返回空")
    void sampleEdges() {
        assertEquals(List.of("id0"), WriteVerification.sample(ids(10), 1));
        assertEquals(List.of("id0"), WriteVerification.sample(ids(10), 0), "非法采样数按 1 处理");
        assertTrue(WriteVerification.sample(List.of(), 5).isEmpty());
        assertTrue(WriteVerification.sample(null, 5).isEmpty());
    }

    @Test
    @DisplayName("判定：条数完全一致才算通过；期望<=0 视为无效校验")
    void verifiedSemantics() {
        assertTrue(WriteVerification.verified(20, 20));
        assertFalse(WriteVerification.verified(20, 19), "少一条也不能算通过");
        assertFalse(WriteVerification.verified(20, 21), "多一条说明有残留（例如旧版本没删干净）");
        assertFalse(WriteVerification.verified(0, 0), "没有样本时不能判定为通过");
        assertFalse(WriteVerification.verified(-1, 0));
    }

    @Test
    @DisplayName("describe 输出包含阶段与期望/实际条数，便于日志检索")
    void describeText() {
        String text = WriteVerification.describe("写入", 20, 18);
        assertTrue(text.contains("写入"), text);
        assertTrue(text.contains("20"), text);
        assertTrue(text.contains("18"), text);
    }

    @Test
    @DisplayName("删除判定：仍可见 0 条才算通过；>0 视为异常（墓碑未 compact 或主键被重写）")
    void deletionSemantics() {
        assertTrue(WriteVerification.deletionVerified(0));
        assertFalse(WriteVerification.deletionVerified(1));
        assertFalse(WriteVerification.deletionVerified(20));

        String ok = WriteVerification.describeDeletion("旧版本清理", 20, 0);
        assertTrue(ok.contains("旧版本清理") && ok.contains("仍可见 0 条"), ok);

        String bad = WriteVerification.describeDeletion("旧版本清理", 20, 3);
        assertTrue(bad.contains("仍可见 3 条"), bad);
        assertTrue(bad.contains("compact"), "异常时应给出墓碑/compact 提示: " + bad);
    }
}
