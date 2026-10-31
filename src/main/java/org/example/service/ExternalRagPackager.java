package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 外部模型的 RAG 支持（预检索注入模式）。
 *
 * <p><b>问题</b>：外部 OpenAI 兼容模型不一定支持 function calling，
 * 原先的做法是「ext 模型一律不注入工具」，代价是**完全用不了知识库**。</p>
 *
 * <p><b>方案</b>：不依赖模型自己调工具，改由后端**先把检索结果查出来**，
 * 拼成"参考资料"段落注入 system prompt（即 RAG 的 stuff 模式）。
 * 这样任何兼容 OpenAI 协议、甚至不支持工具调用的模型都能基于知识库回答。</p>
 *
 * <p><b>取舍</b>：模型失去"多次检索、按需换关键词"的能力（只有一次检索机会），
 * 换来了通用性。对学习问答这个场景足够用，且明显优于完全禁用 RAG。</p>
 */
@Service
public class ExternalRagPackager {

    private static final Logger logger = LoggerFactory.getLogger(ExternalRagPackager.class);

    private final VectorSearchService vectorSearchService;
    private final QueryExpansionService queryExpansion;

    @Value("${rag.external.top-k:5}")
    private int topK;

    @Value("${rag.external.max-chars:4000}")
    private int maxChars;

    @Value("${rag.external.enabled:true}")
    private boolean enabled;

    public ExternalRagPackager(VectorSearchService vectorSearchService,
                               QueryExpansionService queryExpansion) {
        this.vectorSearchService = vectorSearchService;
        this.queryExpansion = queryExpansion;
    }

    /**
     * 为外部模型构建知识库上下文。
     *
     * @param question 用户问题
     * @return 可直接拼进 system prompt 的文本；无检索结果时返回 null
     */
    public String buildContext(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return null;
        }
        try {
            // 与内置模型一致：先扩展查询词，短问题才可能匹配到具体资料
            String expanded = queryExpansion != null ? queryExpansion.expand(question, null) : question;
            List<VectorSearchService.SearchResult> hits = vectorSearchService.searchWithKeywordFallback(expanded, topK);
            if ((hits == null || hits.isEmpty()) && !expanded.equals(question)) {
                hits = vectorSearchService.searchWithKeywordFallback(question, topK);
            }
            if (hits == null || hits.isEmpty()) {
                logger.info("外部模型预检索：未命中知识库，按纯对话回答");
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int used = 0;
            int idx = 1;
            for (VectorSearchService.SearchResult h : hits) {
                String content = h == null ? null : h.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String trimmed = content.trim();
                if (used + trimmed.length() > maxChars) {
                    trimmed = trimmed.substring(0, Math.max(0, maxChars - used));
                }
                if (trimmed.isEmpty()) {
                    break;
                }
                sb.append("【资料").append(idx++).append("】");
                String source = h.getHeadingPath();
                if (source != null && !source.isBlank()) {
                    sb.append("（").append(source).append("）");
                }
                sb.append("\n").append(trimmed).append("\n\n");
                used += trimmed.length();
                if (used >= maxChars) {
                    break;
                }
            }
            if (sb.length() == 0) {
                return null;
            }
            logger.info("外部模型预检索：命中 {} 条，注入 {} 字符", idx - 1, used);
            return sb.toString();
        } catch (Exception e) {
            // 检索失败不能影响对话：降级为纯对话
            logger.warn("外部模型预检索失败（降级为纯对话）: {}", e.getMessage());
            return null;
        }
    }
}
