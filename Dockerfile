# ============================================================
# 期末速成助手 (StudyBuddy AI) —— PocketBay 单容器部署镜像
# 多阶段构建：Maven 在线构建 → JRE 运行
#
# 基础镜像可用 --build-arg 覆盖（默认 Docker Hub 官方 tag）。
# 示例（境内网络，Docker Hub 直连被墙时用镜像站）：
#   docker build --build-arg BASE_BUILD=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
#                --build-arg BASE_RUN=docker.m.daocloud.io/library/eclipse-temurin:17-jre -t learnagent .
# ============================================================

# ---------- 阶段 1：构建 ----------
ARG BASE_BUILD=maven:3.9-eclipse-temurin-17
ARG BASE_RUN=eclipse-temurin:17-jre
FROM ${BASE_BUILD} AS build
WORKDIR /build

# 先拷 pom 预拉依赖（利用 Docker 层缓存：改源码不重拉依赖）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

# 拷源码并打包（跳过测试，spring-boot 插件 repackage 出可执行 fat jar）
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM ${BASE_RUN}
WORKDIR /app

# 非 root 用户 + 数据目录（SQLite/上传/GitHub 本地缓存都落 /data volume）
# 注意：这里不设 USER appuser —— 容器默认以 root 启动，由 docker-entrypoint.sh
# 在启动时把 /data 持久卷 chown 给 appuser 再降权运行（平台挂载的卷会覆盖镜像内权限，
# 若直接 USER appuser 会因卷属主 root 而 Permission denied）。
RUN groupadd --gid 1001 appuser \
    && useradd --uid 1001 --gid 1001 --create-home appuser \
    && mkdir -p /data/uploads /data/github_cache \
    && chown -R appuser:appuser /app /data

COPY --from=build /build/target/super-biz-agent-1.0-SNAPSHOT.jar /app/app.jar

# 入口脚本：root 启动 → chown /data → 降权 appuser → java
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# 容器内默认配置（全部可被 PocketBay 环境变量覆盖）
# - 数据全部落 /data（volume 持久化）
# - GITHUB_PUSH_ENABLED=false：容器内关闭 GitHub 备份推送（决策 2a），只本地落盘
# - Milvus 不设默认值：留空则尝试本地 host:port，失败自动降级；
#   完整 RAG 需在 PocketBay 注入 MILVUS_URI / MILVUS_TOKEN（Zilliz Cloud，决策 1b）
ENV SERVER_PORT=9900 \
    SPRING_DATASOURCE_URL=jdbc:sqlite:/data/study.db \
    FILE_UPLOAD_PATH=/data/uploads \
    APP_DATA_DIR=/data \
    GITHUB_CACHE_PATH=/data/github_cache \
    GITHUB_PUSH_ENABLED=false

EXPOSE 9900
VOLUME ["/data"]

# 容器友好堆内存（按容器内存上限百分比自适应），由入口脚本降权执行
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
