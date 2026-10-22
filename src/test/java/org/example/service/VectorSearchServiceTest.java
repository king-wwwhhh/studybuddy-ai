package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 检索侧 metadata 解析：heading_path 抽取必须容错，解析失败不能影响检索主链路。 */
class VectorSearchServiceTest {

    @Test
    @DisplayName("metadata 里带 heading_path → 抽成独立字段")
    void extractsHeadingPath() {
        String metadata = "{\"heading_path\":\"第一章 > 1.2 检索\",\"chunkIndex\":3,\"file_sha\":\"abc\"}";
        assertEquals("第一章 > 1.2 检索", VectorSearchService.extractHeadingPath(metadata));
    }

    @Test
    @DisplayName("缺字段 / 空值 / 非法 JSON / null → 返回 null，不抛异常")
    void missingOrBrokenMetadata() {
        assertNull(VectorSearchService.extractHeadingPath("{\"chunkIndex\":0}"));
        assertNull(VectorSearchService.extractHeadingPath("{\"heading_path\":null}"));
        assertNull(VectorSearchService.extractHeadingPath("not a json"));
        assertNull(VectorSearchService.extractHeadingPath(""));
        assertNull(VectorSearchService.extractHeadingPath(null));
    }
}
