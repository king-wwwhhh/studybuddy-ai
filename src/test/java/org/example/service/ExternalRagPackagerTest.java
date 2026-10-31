package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 外部模型 RAG 预检索注入单测：空问题/无命中/超长截断/检索异常降级。 */
class ExternalRagPackagerTest {

    /** 可控的假检索服务 */
    private static VectorSearchService fake(List<VectorSearchService.SearchResult> hits, boolean throwEx) {
        return new VectorSearchService() {
            @Override
            public List<SearchResult> searchSimilarDocuments(String query, int topK) {
                if (throwEx) throw new RuntimeException("milvus down");
                return hits;
            }
        };
    }

    private static VectorSearchService.SearchResult hit(String content, String heading) {
        VectorSearchService.SearchResult r = new VectorSearchService.SearchResult();
        try {
            var f = r.getClass().getDeclaredField("content"); f.setAccessible(true); f.set(r, content);
            if (heading != null) { var g = r.getClass().getDeclaredField("headingPath"); g.setAccessible(true); g.set(r, heading); }
        } catch (Exception e) { throw new RuntimeException(e); }
        return r;
    }

    private static ExternalRagPackager of(VectorSearchService svc, int topK, int maxChars) {
        ExternalRagPackager p = new ExternalRagPackager(svc, null);
        set(p, "topK", topK); set(p, "maxChars", maxChars); set(p, "enabled", true);
        return p;
    }

    private static void set(Object o, String f, Object v) {
        try { var x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("命中时生成带来源标注的上下文")
    void buildsContext() {
        var svc = fake(List.of(hit("向量检索用 L2 距离", "第一章 > 1.2"), hit("HNSW 是多层图", null)), false);
        String ctx = of(svc, 5, 4000).buildContext("向量检索是什么");
        assertNotNull(ctx);
        assertTrue(ctx.contains("【资料1】"));
        assertTrue(ctx.contains("第一章 > 1.2"), "应带上章节路径");
        assertTrue(ctx.contains("【资料2】"));
        assertTrue(ctx.contains("HNSW"));
    }

    @Test
    @DisplayName("无命中返回 null（由上层降级为纯对话）")
    void noHits() {
        assertNull(of(fake(List.of(), false), 5, 4000).buildContext("无关问题"));
        assertNull(of(fake(null, false), 5, 4000).buildContext("无关问题"));
    }

    @Test
    @DisplayName("空问题不检索")
    void blankQuestion() {
        assertNull(of(fake(List.of(hit("x", null)), false), 5, 4000).buildContext(""));
        assertNull(of(fake(List.of(hit("x", null)), false), 5, 4000).buildContext("   "));
        assertNull(of(fake(List.of(hit("x", null)), false), 5, 4000).buildContext(null));
    }

    @Test
    @DisplayName("超出 maxChars 时截断，不无限膨胀")
    void truncates() {
        String longText = "A".repeat(1000);
        var svc = fake(List.of(hit(longText, null), hit(longText, null), hit(longText, null)), false);
        String ctx = of(svc, 5, 2500).buildContext("问题");
        assertNotNull(ctx);
        assertTrue(ctx.length() <= 2600, "实际长度 " + ctx.length() + " 应受 maxChars 约束");
    }

    @Test
    @DisplayName("检索异常时降级返回 null，不抛出（对话不能被检索拖垮）")
    void degradesOnError() {
        assertNull(of(fake(null, true), 5, 4000).buildContext("问题"));
    }

    @Test
    @DisplayName("关闭开关时不检索")
    void disabled() {
        ExternalRagPackager p = of(fake(List.of(hit("x", null)), false), 5, 4000);
        set(p, "enabled", false);
        assertNull(p.buildContext("问题"));
    }
}
