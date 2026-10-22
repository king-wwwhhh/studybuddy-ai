package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.client.IndexPlanner;
import org.example.client.MilvusClientFactory;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired(required = false)  // Milvus 不可用时为 null，searchSimilarDocuments 内部已降级
    private MilvusServiceClient milvusClient;

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private RerankService rerankService;

    /**
     * L2 平方距离阈值：归一化向量下 L2² = 2 − 2·cos，1.2 等价 cos≈0.4（偏松）。
     * 必须按模型重新标定，故配置化而不是写死。
     */
    @Value("${rag.score-threshold:1.2}")
    private float scoreThreshold;

    /** rerank 之后真正返回给调用方的片段数：召回宽度(topK) → 精排 → 取 Top rerankTopN */
    @Value("${rag.rerank-top-n:3}")
    private int rerankTopN;

    /** 同一章节最多保留几条：其余名额让给其他章节，避免同一章的相邻段落占满上下文 */
    @Value("${rag.max-per-chapter:2}")
    private int maxPerChapter;

    /** IVF 探测桶数（仅 IVF_FLAT 索引生效） */
    @Value("${milvus.index.nprobe:10}")
    private int nprobe;

    /** HNSW 检索候选宽度（仅 HNSW 索引生效，运行时自动抬到 ≥ topK） */
    @Value("${milvus.index.ef:64}")
    private int ef;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        // 降级模式：Milvus 不可用时返回空结果，调用方（RAG 检索）应据空结果走「无检索」兜底
        if (milvusClient == null) {
            logger.warn("Milvus 客户端不可用（降级模式），跳过向量检索，返回空结果。query={}", query);
            return Collections.emptyList();
        }
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            // 0. 确保 collection 已加载：本地 Milvus 常驻加载无影响；
            //    云端 Zilliz Serverless 集合默认 release 态，检索前必须显式 load，否则报 collection not found
            loadCollection();

            // 1. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数（显式带真实数据库名：Zilliz Serverless 为生成名，否则 collection not found）
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    // 检索参数必须与 collection 实际索引类型匹配：IVF 传 nprobe、HNSW 传 ef、FLAT 不传
                    .withParams(IndexPlanner.buildSearchParams(
                            milvusClientFactory.getIndexType(), nprobe, ef, topK))
                    .build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();
            List<Float> rawScores = new ArrayList<>();
            // 供「日志 → 评测输入」使用：记录 id + score，否则事后无法还原成评测样本
            List<RetrievalEvaluator.Candidate> candidatesForLog = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                rawScores.add(result.getScore()); // 先记原始分，便于校准阈值
                candidatesForLog.add(new RetrievalEvaluator.Candidate(result.getId(), result.getScore()));

                // L2 平方距离阈值过滤（归一化向量下 L2²=2−2cos，阈值按 cos 折算后配置）
                if (result.getScore() > scoreThreshold) {
                    logger.debug("跳过低相关结果: score={}, threshold={}, id={}",
                            result.getScore(), scoreThreshold, result.getId());
                    continue;
                }

                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                    // 章节路径同时抽成独立字段：工具返回给模型时可直接引用「出自哪一章」
                    result.setHeadingPath(extractHeadingPath(result.getMetadata()));
                }

                results.add(result);
            }

            // 4.1 分布可见性：打出候选的原始 score 区间，供按 P95 分位数/标注集校准阈值
            if (!rawScores.isEmpty()) {
                List<Float> sorted = new ArrayList<>(rawScores);
                Collections.sort(sorted);
                logger.info("检索候选 {} 条, score 区间 [{}, {}], 阈值 {}, 通过 {} 条",
                        rawScores.size(), sorted.get(0), sorted.get(sorted.size() - 1),
                        scoreThreshold, results.size());
                logger.debug("检索明细: {}", RetrievalLogParser.toLogPayload(query, candidatesForLog));
            }

            // 5. 精排：召回宽度(topK) → rerank → 截断到 rerank-top-n（否则 rerank 没有挑选空间）
            int recalled = results.size();
            if (!results.isEmpty() && rerankService.isEnabled()) {
                results = rerankService.rerank(query, results);
            }
            // 6. 章节多样性：同一章节最多 maxPerChapter 条（不够则按原顺序回补），
            //    避免几条上下文名额被同一章的相邻段落占满，让跨章节的相关内容也能进来
            results = ChapterDiversitySelector.select(
                    results, SearchResult::getHeadingPath, rerankTopN, maxPerChapter);

            logger.info("搜索完成: 召回 {} 条 → 入上下文 {} 条 (rerank={})",
                    recalled, results.size(), rerankService.isEnabled());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /** 确保 collection 已加载：Zilliz Serverless 检索前必须 load，否则报 collection not found */
    private void loadCollection() {
        if (milvusClient == null) return;
        R<RpcStatus> r = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME).build());
        if (r.getStatus() != 0 && r.getStatus() != 65535) {
            throw new RuntimeException("加载 collection 失败: " + r.getMessage());
        }
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        /** 章节路径（如「第一章 > 1.2 向量检索」），来自 metadata.heading_path；缺失为 null */
        private String headingPath;

    }

    /** 从 metadata JSON 里取章节路径：解析失败返回 null，绝不影响检索主链路 */
    static String extractHeadingPath(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(metadataJson)
                    .getAsJsonObject().get("heading_path");
            return el == null || el.isJsonNull() ? null : el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }
}
