package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检索后重排（Rerank）：用 DashScope 专用 rerank 模型对向量召回结果精排。
 * 与 Milvus 的 L2 距离（召回）互补：召回负责"别漏"，重排负责"把最相关的排到前面"。
 * 失败时降级返回原列表，不影响主检索链路。
 *
 * 注意：rerank 走独立的公网 base-url（默认 dashscope.aliyuncs.com），
 * 不依赖 spring.ai.dashscope.base-url（那个是企业/专属 MaaS 网关，未必支持 text-rerank 端点）。
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${rerank.enabled:true}")
    private boolean enabled;

    @Value("${rerank.base-url:https://dashscope.aliyuncs.com}")
    private String baseUrl;

    @Value("${rerank.model:gte-rerank-v2}")
    private String model;

    private volatile DashScopeRerankModel rerankModel; // 懒加载 + 缓存

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 对候选结果按相关度重排。返回顺序即模型认为的相关度降序。
     * candidates 为空 / 重排未开启 / 调用失败时原样返回。
     */
    public List<VectorSearchService.SearchResult> rerank(String query, List<VectorSearchService.SearchResult> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        try {
            List<Document> documents = new ArrayList<>(candidates.size());
            for (VectorSearchService.SearchResult r : candidates) {
                documents.add(new Document(r.getId(), r.getContent(), new HashMap<>()));
            }
            DashScopeRerankOptions options = DashScopeRerankOptions.builder()
                    .withModel(model)
                    .withTopN(candidates.size())
                    .withReturnDocuments(false)
                    .build();
            com.alibaba.cloud.ai.model.RerankResponse response = getModel()
                    .call(new RerankRequest(query, documents, options));

            // response.getResults() 已按相关度降序；把原 SearchResult 按该顺序重排
            List<VectorSearchService.SearchResult> ranked = new ArrayList<>(candidates.size());
            Set<String> rankedIds = new HashSet<>();
            for (DocumentWithScore d : response.getResults()) {
                String id = d.getOutput() == null ? null : d.getOutput().getId();
                if (id == null) continue;
                candidates.stream()
                        .filter(r -> Objects.equals(r.getId(), id))
                        .findFirst()
                        .ifPresent(r -> { ranked.add(r); rankedIds.add(id); });
            }
            // 个别 id 未命中（模型可能丢弃）时，把剩余的补到尾部，保证不丢召回结果
            for (VectorSearchService.SearchResult r : candidates) {
                if (!rankedIds.contains(r.getId())) {
                    ranked.add(r);
                }
            }
            logger.info("Rerank 完成: {} 条 -> {} 条（{}）", candidates.size(), ranked.size(), model);
            return ranked;
        } catch (Exception e) {
            logger.warn("Rerank 失败，降级返回原召回列表: {}", e.getMessage());
            return candidates;
        }
    }

    private DashScopeRerankModel getModel() {
        DashScopeRerankModel m = rerankModel;
        if (m == null) {
            synchronized (this) {
                if (rerankModel == null) {
                    DashScopeApi api = DashScopeApi.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .build();
                    rerankModel = new DashScopeRerankModel(api);
                }
                m = rerankModel;
            }
        }
        return m;
    }
}
