package org.example.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
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
     * 为 collection 创建索引
     */
    private void createIndexes(MilvusServiceClient client) {
        // 为 vector 字段创建索引（FloatVector 使用 IVF_FLAT 和 L2 距离）
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)  // L2 距离（欧氏距离）
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + response.getMessage());
        }
        
        logger.info("成功为 vector 字段创建索引");
    }
}
