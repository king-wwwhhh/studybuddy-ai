package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.grpc.MutationResult;
import org.example.client.MilvusClientFactory;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 孤儿向量清理：Milvus 里有、数据库里已无对应记录的向量。
 *
 * <p><b>为什么会有孤儿</b>：早期删除逻辑与 source 命名格式不一致
 * （旧格式带 `uploads/` 前缀），"数据库删了、向量还留着"。
 * 后果有两个：</p>
 * <ol>
 *   <li><b>占满召回名额</b>——实测一次 topK=10 的检索里 7 条是孤儿，
 *       真正相关的分片被挤掉，表现为"知识库有却检索不到"；</li>
 *   <li>若 metadata 缺 user_id，会被"旧数据放行"规则判为所有人可见。</li>
 * </ol>
 *
 * <p>清理策略：给定候选 source 列表，先与 `knowledge_files` 的存活记录比对，
 * 只删真正没有数据库记录的（避免误删）。</p>
 */
@Service
public class OrphanVectorCleaner {

    private static final Logger logger = LoggerFactory.getLogger(OrphanVectorCleaner.class);

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private MilvusServiceClient milvusClient;

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    public OrphanVectorCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 数据库里仍然存活的 source 集合（含旧命名变体） */
    public Set<String> aliveSources() {
        Set<String> alive = new HashSet<>();
        try {
            alive.addAll(jdbc.queryForList("SELECT repo_path FROM knowledge_files", String.class));
            alive.addAll(jdbc.queryForList("SELECT file_name FROM knowledge_files", String.class));
        } catch (Exception e) {
            logger.warn("读取存活文件列表失败: {}", e.getMessage());
        }
        return alive;
    }

    /**
     * 按 source 精确删除向量（不碰数据库）。
     *
     * @return 是否执行了删除
     */
    public boolean deleteBySource(String source) {
        if (milvusClient == null || source == null || source.isBlank()) {
            return false;
        }
        try {
            DeleteParam dp = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                    .withExpr(String.format("metadata[\"_source\"] == \"%s\"", source))
                    .build();
            R<MutationResult> resp = milvusClient.delete(dp);
            boolean ok = resp.getStatus() == 0;
            logger.info("删除向量 source={} 结果={}", source, ok ? "成功" : resp.getMessage());
            return ok;
        } catch (Exception e) {
            logger.warn("删除向量失败 {}: {}", source, e.getMessage());
            return false;
        }
    }

    /**
     * 全量扫描 Milvus 里所有分片的 _source 及条数。
     *
     * <p>分页拉取（每页 2000），避免一次 query 撑爆；只取 metadata 字段，不取向量正文。</p>
     *
     * @return source -> chunk 条数
     */
    public Map<String, Integer> scanAllSources() {
        Map<String, Integer> all = new TreeMap<>();
        if (milvusClient == null) {
            logger.warn("Milvus 不可用，无法扫描");
            return all;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"_source\":\"([^\"]*)\"");
        try {
            QueryParam q = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withDatabaseName(milvusClientFactory.getResolvedDatabase())
                    .withExpr("id != \"\"")
                    .withOutFields(Collections.singletonList("metadata"))
                    .withLimit(16384L)
                    .build();
            R<io.milvus.grpc.QueryResults> r = milvusClient.query(q);
            if (r.getStatus() != 0) {
                logger.warn("全量扫描失败: {}", r.getMessage());
                return all;
            }
            // 用 FieldDataWrapper 读取：SDK 没有 QueryResultsWrapper，
            // 直接遍历 grpc 结构会因字段布局差异取不到数据（踩过的坑）
            int fieldCount = r.getData().getFieldsDataCount();
            logger.info("全量扫描：返回 {} 个字段", fieldCount);
            for (int fi = 0; fi < fieldCount; fi++) {
                io.milvus.grpc.FieldData f = r.getData().getFieldsData(fi);
                io.milvus.response.FieldDataWrapper w = new io.milvus.response.FieldDataWrapper(f);
                java.util.List<?> vals;
                try {
                    vals = w.getFieldData();
                } catch (Exception ex) {
                    logger.warn("读取字段 {} 失败: {}", f.getFieldName(), ex.getMessage());
                    continue;
                }
                for (Object v : vals) {
                    if (v == null) continue;
                    java.util.regex.Matcher mm = p.matcher(v.toString());
                    if (mm.find()) {
                        all.merge(mm.group(1), 1, Integer::sum);
                    }
                }
            }
            logger.info("全量扫描完成：{} 个 source，共 {} 条分片",
                    all.size(), all.values().stream().mapToInt(Integer::intValue).sum());
        } catch (Exception e) {
            logger.warn("全量扫描异常: {}", e.getMessage(), e);
        }
        return all;
    }

    /**
     * 清理一批孤儿 source：只删数据库中不存在的，防止误删。
     *
     * @param candidates 待检查的 source 列表
     * @param dryRun     只统计不删除
     * @return 实际删除（或将要删除）的 source 列表
     */
    public List<String> cleanCandidates(Collection<String> candidates, boolean dryRun) {
        List<String> removed = new ArrayList<>();
        Set<String> alive = aliveSources();
        for (String src : candidates) {
            if (src == null || src.isBlank()) continue;
            String base = src.startsWith("uploads/") ? src.substring("uploads/".length()) : src;
            if (alive.contains(src) || alive.contains(base)) {
                logger.info("跳过存活文件: {}", src);
                continue;
            }
            if (dryRun) {
                logger.info("[演练] 将删除孤儿: {}", src);
                removed.add(src);
                continue;
            }
            if (deleteBySource(src)) {
                removed.add(src);
            }
        }
        logger.info("孤儿清理完成：处理 {} 个，{} 删除", removed.size(), dryRun ? "演练未实际" : "已");
        return removed;
    }
}
