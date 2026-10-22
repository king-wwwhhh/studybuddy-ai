package org.example.agent.tool;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ChapterAggregator;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具（RAG Function Calling）。
 * StudyAgent / PlannerAgent 用来从「已上传的私有资料」中检索相关内容。
 */
@Component
public class KnowledgeSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSearchTool.class);
    public static final String TOOL_NAME = "knowledgeSearch";

    private final VectorSearchService vectorSearchService;
    private final ObjectMapper mapper = Mappers.JSON;

    @Value("${rag.top-k:10}")
    private int topK;

    @Autowired
    public KnowledgeSearchTool(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Tool(description = "Search the personal knowledge base (uploaded notes, textbooks, past papers) for relevant passages to answer study questions. "
            + "Returns a JSON list of {chapter, content, sources:[{id, score}]}: passages already merged by chapter, so one item may come from multiple chunks; "
            + "cite the chapter when answering.")
    public String knowledgeSearch(
            @ToolParam(description = "Search query describing what you want to find") String query) {
        try {
            List<VectorSearchService.SearchResult> results =
                    vectorSearchService.searchSimilarDocuments(query, topK);
            if (results.isEmpty()) {
                return "{\"status\":\"no_results\",\"message\":\"知识库暂无相关内容，建议先上传资料。\"}";
            }
            // 同一章节的连续命中合并成一段：模型不必自己把碎片拼起来，sources 保留溯源
            List<ChapterAggregator.Group> groups =
                    ChapterAggregator.group(results, ChapterAggregator.DEFAULT_MAX_CHARS);
            return ChapterAggregator.renderJson(groups);
        } catch (Exception e) {
            logger.error("知识检索工具失败", e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
