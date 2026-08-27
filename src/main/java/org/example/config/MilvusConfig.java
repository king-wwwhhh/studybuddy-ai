package org.example.config;

import io.milvus.client.MilvusServiceClient;
import org.example.client.MilvusClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Milvus 配置类
 * 负责创建和管理 MilvusServiceClient Bean
 */
@Configuration
public class MilvusConfig {

    private static final Logger logger = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    private MilvusServiceClient milvusClient;

    /**
     * 创建 MilvusServiceClient Bean
     * 
     * 容错策略：若 Milvus 不可达（本地未启动 Docker、或云实例不可用），
     * 不再中断整个应用启动，而是返回 null，由 VectorIndexService /
     * VectorSearchService 在 client 为 null 时降级处理，保证其余功能
     * （计划、打卡、测验、记忆、GitHub 知识库同步）仍可正常工作。
     * 
     * @return MilvusServiceClient 实例，不可用时为 null
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        try {
            logger.info("正在初始化 Milvus 客户端...");
            milvusClient = milvusClientFactory.createClient();
            logger.info("Milvus 客户端初始化完成");
            return milvusClient;
        } catch (Exception e) {
            logger.warn("⚠️ Milvus 客户端初始化失败，应用将以「向量检索降级模式」启动："
                    + "知识库向量检索/嵌入不可用，其余功能正常。原因: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            logger.info("正在关闭 Milvus 客户端连接...");
            milvusClient.close();
            logger.info("Milvus 客户端连接已关闭");
        }
    }
}
