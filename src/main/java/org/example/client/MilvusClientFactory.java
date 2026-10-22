package org.example.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.param.index.DropIndexParam;
import io.milvus.response.DescIndexResponseWrapper;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.example.service.IndexSwitchPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 客户端工厂类
 * 负责创建和初始化 Milvus 客户端连接
 */
@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * Milvus 实际数据库名。
     * 本地 Milvus 恒为 "default"；Zilliz Serverless 是生成的库名（如 db_xxx），
     * search/delete/insert 等严格路由的操作必须显式带该库名，否则报 collection not found。
     */
    private volatile String resolvedDatabase = "default";

    /** 获取探测到的 Milvus 实际数据库名（连接成功后为真实名，否则 default） */
    public String getResolvedDatabase() {
        return resolvedDatabase;
    }

    /**
     * 当前 collection 实际使用的向量索引类型（FLAT / HNSW / IVF_FLAT）。
     * 检索侧据此决定传 nprobe 还是 ef —— 传错 Milvus 会忽略参数并静默降级质量。
     */
    private volatile String indexType = IndexPlanner.IVF_FLAT;

    /** 现有索引名：Milvus 2.2.12 的 DropIndexParam 只接受 indexName，重建时必须用它 */
    private volatile String currentIndexName;

    public String getIndexType() {
        return indexType;
    }

    /** 探测 Milvus 实际数据库名：listDatabases 取第一个库（本地返回 default，Zilliz Serverless 返回生成名） */
    private String resolveDatabase(MilvusServiceClient client) {
        try {
            R<ListDatabasesResponse> resp = client.listDatabases();
            if (resp.getStatus() == 0 && resp.getData() != null
                    && resp.getData().getDbNamesList() != null
                    && !resp.getData().getDbNamesList().isEmpty()) {
                String db = resp.getData().getDbNamesList().get(0);
                if (db != null && !db.isBlank()) {
                    logger.info("Milvus 实际数据库: {}（search/delete/insert 将显式使用该库）", db);
                    return db;
                }
            }
        } catch (Exception e) {
            logger.warn("探测 Milvus 数据库名失败，回退 default: {}", e.getMessage());
        }
        return "default";
    }

    /**
     * 创建并初始化 Milvus 客户端
     *
     * 简化版本：直接连接并创建 collection
     *
     * @return MilvusServiceClient 实例
     * @throws RuntimeException 如果连接或初始化失败
     */
    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;

        try {
            // 1. 连接到 Milvus
            logger.info("正在连接到 Milvus: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());
            client = connectToMilvus();
            logger.info("成功连接到 Milvus");
            // 1.5 探测实际数据库名（Zilliz Serverless 生成名，search/delete 需显式指定）
            resolvedDatabase = resolveDatabase(client);

            // 2. 检查并创建 biz collection（如果不存在）
            if (!collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME)) {
                logger.info("collection '{}' 不存在，正在创建...", MilvusConstants.MILVUS_COLLECTION_NAME);
                createBizCollection(client);
                logger.info("成功创建 collection '{}'", MilvusConstants.MILVUS_COLLECTION_NAME);
                
                // 创建索引
                createIndexes(client);
                logger.info("成功创建索引");
            } else {
                logger.info("collection '{}' 已存在", MilvusConstants.MILVUS_COLLECTION_NAME);
                resolveExistingIndexType(client);
                // 索引类型变更检查（默认只告警，不自动重建）
                applyIndexPlan(client);
            }

            return client;

        } catch (Exception e) {
            logger.error("创建 Milvus 客户端失败", e);
            if (client != null) {
                client.close();
            }
            throw new RuntimeException("创建 Milvus 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 连接到 Milvus
     */
    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withConnectTimeout(milvusProperties.getTimeout(), TimeUnit.MILLISECONDS);

        // 云端 Milvus/Zilliz Cloud：直接用 URI + Token
        if (milvusProperties.getUri() != null && !milvusProperties.getUri().isEmpty()) {
            builder.withUri(milvusProperties.getUri());
            if (milvusProperties.getToken() != null && !milvusProperties.getToken().isEmpty()) {
                builder.withToken(milvusProperties.getToken());
            }
        } else {
            // 本地 Milvus：用 host + port + 用户名/密码
            builder.withHost(milvusProperties.getHost())
                    .withPort(milvusProperties.getPort());
            if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
                builder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
            }
        }

        return new MilvusServiceClient(builder.build());
    }

    /**
     * 检查 collection 是否存在
     */
    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection 失败: " + response.getMessage());
        }

        return response.getData();
    }

    /**
     * 创建 biz collection
     */
    private void createBizCollection(MilvusServiceClient client) {
        // 定义字段
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)  // 改为 FloatVector
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        // 创建 collection schema
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        // 创建 collection
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDescription("Business knowledge collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 collection 创建索引。
     *
     * <p>索引类型不再写死：`milvus.index.type=auto` 时按 collection 行数选型
     * （≤5 万 FLAT / ≤500 万 HNSW / 更大 IVF_FLAT），也可显式指定；`nlist` 为 0 时按 4·√N 自动算。</p>
     */
    private void createIndexes(MilvusServiceClient client) {
        long rows = rowCount(client);
        MilvusProperties.Index cfg = milvusProperties.getIndex();
        String type = IndexPlanner.resolveType(cfg.getType(), rows);
        String extra = IndexPlanner.buildExtraParams(type, rows, cfg.getNlist(), cfg.getM(), cfg.getEfConstruction());
        logger.info("向量索引选型: type={} rows={} extra={} (配置 type={})", type, rows, extra, cfg.getType());

        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.valueOf(type))
                .withMetricType(MetricType.L2)  // 归一化向量下 L2²=2−2cos，与余弦同序
                .withExtraParam(extra)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + response.getMessage());
        }
        indexType = type;
        logger.info("成功为 vector 字段创建索引: {}", type);
    }

    /** 读取 collection 行数；失败按 0 处理（auto 会落到 FLAT，小数据下最安全） */
    private long rowCount(MilvusServiceClient client) {
        try {
            R<GetCollectionStatisticsResponse> r = client.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build());
            if (r.getStatus() == 0 && r.getData() != null) {
                for (KeyValuePair kv : r.getData().getStatsList()) {
                    if ("row_count".equals(kv.getKey())) {
                        return Long.parseLong(kv.getValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("读取 collection 行数失败（按 0 处理）: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 探测已存在 collection 的索引类型：检索侧必须知道该传 nprobe 还是 ef，
     * 传错 Milvus 会忽略参数、静默降低召回质量。探测失败保留默认 IVF_FLAT（与改造前一致）。
     */
    private void resolveExistingIndexType(MilvusServiceClient client) {
        try {
            R<DescribeIndexResponse> r = client.describeIndex(DescribeIndexParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFieldName("vector")
                    .withDatabaseName(resolvedDatabase)
                    .build());
            if (r.getStatus() == 0 && r.getData() != null) {
                DescIndexResponseWrapper wrapper = new DescIndexResponseWrapper(r.getData());
                DescIndexResponseWrapper.IndexDesc desc = wrapper.getIndexDescByFieldName("vector");
                if (desc != null && desc.getIndexType() != null) {
                    indexType = desc.getIndexType().name();
                    currentIndexName = desc.getIndexName();
                    logger.info("检测到已有向量索引: {}（name={}，检索参数将按该类型生成）",
                            indexType, currentIndexName);
                }
            }
        } catch (Exception e) {
            logger.warn("探测已有索引类型失败，检索按 {} 处理: {}", indexType, e.getMessage());
        }
    }

    /**
     * 索引类型变更检查：默认**不动手**（只告警 + 操作指引），
     * 只有显式开启 `milvus.index.auto-rebuild` 且拿得到现有索引名时才 drop + 重建。
     */
    private void applyIndexPlan(MilvusServiceClient client) {
        MilvusProperties.Index cfg = milvusProperties.getIndex();
        long rows = rowCount(client);
        String desired = IndexPlanner.resolveType(cfg.getType(), rows);
        String extra = IndexPlanner.buildExtraParams(desired, rows, cfg.getNlist(), cfg.getM(), cfg.getEfConstruction());
        IndexSwitchPlan plan = IndexSwitchPlan.decide(indexType, currentIndexName, desired, extra,
                Boolean.TRUE.equals(cfg.getAutoRebuildOnChange()));
        switch (plan.getAction()) {
            case NOOP -> logger.info("索引检查: {}", plan.getReason());
            case MANUAL -> logger.warn("索引检查: {} {}", plan.getReason(), plan.steps());
            case REBUILD -> rebuildIndex(client, plan);
        }
    }

    /** 按计划重建索引：drop 旧索引 → 按目标类型建新索引。失败只告警，旧索引仍可用 */
    private void rebuildIndex(MilvusServiceClient client, IndexSwitchPlan plan) {
        logger.warn("开始重建索引: {} | 步骤 {}", plan.getReason(), plan.steps());
        try {
            R<RpcStatus> dropResp = client.dropIndex(DropIndexParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withIndexName(plan.getCurrentIndexName())
                    .build());
            if (dropResp.getStatus() != 0) {
                logger.error("dropIndex 失败，放弃重建（检索仍用旧索引）: {}", dropResp.getMessage());
                return;
            }
            createIndexes(client);
            logger.info("索引重建完成: {} → {}", plan.getCurrentType(), plan.getDesiredType());
        } catch (Exception e) {
            logger.error("索引重建异常（重建期间检索可能不可用，重启会重试）: {}", e.getMessage(), e);
        }
    }
}
