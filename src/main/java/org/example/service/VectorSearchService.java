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
        return searchSimilarDocuments(query, topK, this.scoreThreshold);
    }

    /**
     * 带显式阈值的检索。
     *
     * <p>为什么单独一个参数：早期"兜底放宽阈值"通过临时改共享字段实现，
     * 并发请求会互相覆盖（A 放宽时 B 也在放宽，读到的阈值不确定）。
     * 阈值必须是**局部变量**。</p>
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK, float threshold) {
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
                    // 多用户隔离（R5）：只检索"本人 + 系统公共 + 旧数据"的向量，防止跨用户泄露
                    .withExpr(VectorScope.searchExprForCurrentUser())
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
                // 临时诊断：打印每条候选的 score + 来源，定位"知识库有却检索不到"的干扰源
                Object dbgMeta = wrapper.getFieldData("metadata", 0).get(i);
                String dbgSrc = "?";
                if (dbgMeta != null) {
                    java.util.regex.Matcher mm = java.util.regex.Pattern
                            .compile("\"_source\":\"([^\"]*)\"|\"user_id\":([0-9]+)").matcher(dbgMeta.toString());
                    StringBuilder sb2 = new StringBuilder();
                    while (mm.find()) sb2.append(mm.group(1) != null ? mm.group(1) : ("uid=" + mm.group(2))).append(' ');
                    dbgSrc = sb2.toString().trim();
                }
                logger.info("【诊断】候选来源 score={} src={}", String.format("%.4f", result.getScore()), dbgSrc);
                candidatesForLog.add(new RetrievalEvaluator.Candidate(result.getId(), result.getScore()));

                // L2 平方距离阈值过滤（归一化向量下 L2²=2−2cos，阈值按 cos 折算后配置）
                if (result.getScore() > threshold) {
                    logger.debug("跳过低相关结果: score={}, threshold={}, id={}",
                            result.getScore(), threshold, result.getId());
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
                        threshold, results.size());
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

    /**
     * 带回退的检索：向量召回为空时，放宽阈值二次召回 + 关键词字面匹配。
     *
     * <p><b>为什么需要</b>：用户会按"自己的说法"提问（如「agent最新pdf」），
     * 而文档实际叫《王浩杰-AI应用与智能体开发.pdf》——
     * 两者语义距离明显（实测 L2² 1.12~1.51，全部超过阈值 1.0），
     * 纯向量检索必然召回 0 条。此时应该用**字面关键词**兜底，
     * 而不是让用户去猜文件全名。</p>
     *
     * <p>匹配策略（命中任一即算）：</p>
     * <ol>
     *   <li>文档内容包含查询里的某个关键词；</li>
     *   <li>文件名包含查询里的某个关键词。</li>
     * </ol>
     * 关键词来自原始查询的分词（按空白/标点切，长度 ≥ 2 的片段）。
     */
    public List<SearchResult> searchWithKeywordFallback(String query, int topK) {
        List<SearchResult> direct = searchSimilarDocuments(query, topK);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        if (query == null || query.isBlank()) {
            return direct == null ? Collections.emptyList() : direct;
        }
        try {
            // 1) 放宽阈值再召回一次：拿到"语义最近但没达阈值"的分片
            List<SearchResult> relaxed = searchSimilarDocuments(query, topK, Float.MAX_VALUE);
            if (relaxed.isEmpty()) {
                return relaxed;
            }
            // 2) 关键词字面过滤：查询切出的片段在 content 或 _source 里出现
            List<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                // 没有可用关键词：接受放宽后的 Top1，避免完全无结果
                logger.info("关键词兜底：无有效关键词，返回放宽阈值的 Top1");
                return relaxed.subList(0, Math.min(1, relaxed.size()));
            }
            List<SearchResult> matched = new ArrayList<>();
            for (SearchResult r : relaxed) {
                String content = r.getContent() == null ? "" : r.getContent();
                String meta = r.getMetadata() == null ? "" : r.getMetadata();
                for (String kw : keywords) {
                    if (content.contains(kw) || meta.contains(kw)) {
                        matched.add(r);
                        logger.info("关键词兜底命中: kw={} score={} source={}", kw,
                                String.format("%.4f", r.getScore()), shortSource(meta));
                        break;
                    }
                }
            }
            if (!matched.isEmpty()) {
                logger.info("关键词兜底：放宽召回 {} 条 → 字面命中 {} 条", relaxed.size(), matched.size());
                return matched.subList(0, Math.min(topK, matched.size()));
            }
            // 3) 字面也没命中：退回放宽后的 Top1（总比"知识库没有"好，且模型会看到真实内容）
            logger.info("关键词兜底：字面未命中，返回放宽阈值 Top1");
            return relaxed.subList(0, Math.min(1, relaxed.size()));
        } catch (Exception e) {
            logger.warn("关键词兜底失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 从查询里切出用于字面匹配的关键词（长度 ≥ 2，去重，最多 8 个） */
    static List<String> extractKeywords(String query) {
        if (query == null) return Collections.emptyList();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : query.split("[\\s,，。、;；:：!！?？()（）\\[\\]/\\\\|\"']+")) {
            String t = part.trim();
            if (t.length() >= 2) {
                out.add(t);
            }
            if (out.size() >= 8) break;
        }
        return new ArrayList<>(out);
    }

    private static String shortSource(String meta) {
        if (meta == null) return "?";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"_source\":\"([^\"]*)\\\"").matcher(meta);
        return m.find() ? m.group(1) : "?";
    }

}