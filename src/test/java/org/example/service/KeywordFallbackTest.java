package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键词兜底：向量召回为空时，用字面匹配把"用户自己的说法"锚定到实际文档。
 *
 * <p>覆盖踩过的坑：用户问「知识库里面的agent最新pdf主要内容是什么」，
 * 文档实际叫《王浩杰-AI应用与智能体开发.pdf》，语义距离 1.27~1.64 全部超阈值，
 * 纯向量检索必然召回 0 条。</p>
 */
class KeywordFallbackTest {

    @Test
    @DisplayName("关键词切分：去掉标点，保留长度≥2 的片段")
    void extractsKeywords() {
        List<String> kw = VectorSearchService.extractKeywords("agent最新pdf主要内容");
        assertTrue(kw.contains("agent最新pdf主要内容"), "无分隔时整体保留: " + kw);

        List<String> kw2 = VectorSearchService.extractKeywords("知识库里面的 agent 最新 pdf 讲什么");
        assertTrue(kw2.contains("知识库里面的"), "应保留中文片段: " + kw2);
        assertTrue(kw2.contains("agent"), "应保留英文片段: " + kw2);
        assertTrue(kw2.contains("pdf"), "应保留小写 pdf: " + kw2);
    }

    @Test
    @DisplayName("单字符被丢弃，避免噪声")
    void dropsSingleChars() {
        List<String> kw = VectorSearchService.extractKeywords("a b c 好 的 pdf");
        assertFalse(kw.contains("a"), "单字符应被丢弃: " + kw);
        assertFalse(kw.contains("好"), "单字应被丢弃: " + kw);
        assertTrue(kw.contains("pdf"));
    }

    @Test
    @DisplayName("标点全被当作分隔符")
    void splitsOnPunctuation() {
        List<String> kw = VectorSearchService.extractKeywords("简历，教育背景。实习经历！");
        assertTrue(kw.contains("简历"));
        assertTrue(kw.contains("教育背景"));
        assertTrue(kw.contains("实习经历"));
    }

    @Test
    @DisplayName("关键词数量上限 8 个")
    void capsAt8() {
        List<String> kw = VectorSearchService.extractKeywords(
                "aa bb cc dd ee ff gg hh ii jj kk ll mm");
        assertTrue(kw.size() <= 8, "不应超过 8 个: " + kw.size());
    }

    @Test
    @DisplayName("空查询返回空列表")
    void emptyQuery() {
        assertTrue(VectorSearchService.extractKeywords(null).isEmpty());
        assertTrue(VectorSearchService.extractKeywords("").isEmpty());
    }
}
