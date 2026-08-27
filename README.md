# 📚 期末速成助手（StudyBuddy AI）

> 基于 **Spring AI Alibaba** 的多 Agent 学习 AI 助手：上传教材/讲义/课件 → 自动构建个人专属可检索知识库 → 一个对话入口由多个专业 Agent 协作，完成 **RAG 知识问答 · AI 按天拆解学习计划 · 每日打卡监督 · 自动重排 · 智能测验** 的考前全流程闭环。

[![Java](https://img.shields.io/badge/Java-17-blue)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.0.0--RC2-orange)](https://github.com/alibaba/spring-ai-alibaba)
[![License](https://img.shields.io/badge/License-Apache--2.0-lightgrey)](LICENSE)

---

## ✨ 功能特性

- **📄 RAG 知识库**：支持 15 种文件格式（PDF / Office / 表格 / 网页 / Markdown…），自动解析 → 分片 → 向量化 → 检索 → 重排 → 增强回答；同文件 sha256 判重，重复上传零重复嵌入。
- **🧠 多 Agent 协作**：Supervisor 意图路由 + 5 个专业 ReAct Agent（闲聊 / 答疑 / 测验 / 计划 / 教练），每个 Agent 绑定专属工具集。
- **📅 AI 学习计划**：按考试日期自动拆解为按天任务（计划 → 任务 → 打卡状态），支持手动/自动重排。
- **✅ 每日打卡监督**：进度看板、连续打卡、任务状态跟踪；**检测到连续 N 天无打卡自动重排**剩余任务（保留已完成）。
- **📝 智能测验**：自动出题、批改、薄弱点追踪与回练。
- **💬 多轮对话**：SSE 流式输出；用户 + 会话双维度隔离，对话历史持久化 + 自动摘要压缩，长对话不爆上下文。
- **🐳 可容器化**：Docker 多阶段构建，环境变量驱动，缺 key 自动降级启动。

## 🏗️ 架构总览

```
┌─────────────────────────────── 对话入口 ───────────────────────────────┐
│  POST /api/chat_stream (SSE)                                        │
│        │                                                            │
│        ▼                                                            │
│  SupervisorAgent（关键词启发式优先 + LLM 意图分类兜底）                │
│   ┌─────────┬─────────┬─────────┬─────────┬─────────┐              │
│   ▼         ▼         ▼         ▼         ▼         │              │
│  chat     study     quiz    planner    coach        │              │
│ 闲聊    知识答疑    测验      计划       教练        │              │
│   └─────────┴────┬────┴─────────┴────┬────┴─────────┘              │
│                  ▼                    ▼                             │
│           RAG 检索工具          计划/打卡/重排工具                     │
└──────────────────────────────────────────────────────────────────────┘
```

### RAG 流水线

```
上传文件 → TextExtractor（PDFBox / Apache POI，15 种扩展名）
        → DocumentChunkService（UTF-8 字节感知分片，8000B 硬上限，句末/空格断点）
        → text-embedding-v4 向量化（1024 维）
        → Milvus / Zilliz Cloud Serverless 入库（biz 集合，IVF_FLAT/L2，自动创建）
        → 向量召回（top-k=3 + L2 距离阈值过滤）
        → gte-rerank-v2 重排精排
        → LLM 增强回答
```

- 分片按 **UTF-8 字节**计数（中文 1 字 ≈ 3 字节），多字节字符绝不切坏，长中文文档 0 失败入库。
- 向量库本地 / 云端双通道：`MILVUS_URI + MILVUS_TOKEN`（Zilliz Cloud Serverless）优先，否则回落 `MILVUS_HOST:PORT` 本地；连接失败自动降级（RAG 关闭、其余功能正常）。
- 检索失败自动降级：rerank 不可用直接返回召回结果，不阻塞主链路。

### 计划闭环（Plan → 打卡 → 自动重排）

```
PlannerAgent 生成按天计划 → 学员每日打卡 / 更新任务状态 → 系统监督进度
        └────────────── 连续 N 天无打卡 + 存在逾期任务 + 过冷却期
                                 │
                                 ▼
                    自动触发 replan（保留已完成任务，
                    按剩余时间重写未完成 → LLM 只给 day 序号，后端按今天归一化日期）
```

参数在 `application.yml`：

```yaml
plan:
  auto-replan:
    enabled: true      # 关闭则仅保留手动/教练对话触发重排
    no-checkin-days: 3 # 连续 N 天无打卡即判定计划停滞
    cooldown-days: 7   # 距上次重排至少 N 天才允许再次自动重排
```

## 🛠️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | Agent / 模型 / 工具框架 |
| DashScope SDK | 2.17.0 | 阿里云百炼模型（qwen3-max / text-embedding-v4 / gte-rerank-v2） |
| Milvus | 2.5.10（本地 compose）/ SDK 2.6.10 | 向量数据库 |
| SQLite | 3.46.0.0 | 计划 / 打卡 / 记忆持久化 |
| Apache PDFBox | 2.0.31 | PDF 正文抽取 |
| Apache POI | 4.1.2 | Office（doc/docx/ppt/pptx/xls/xlsx）抽取 |

## 🧭 Agent 体系

| Agent | 类型 | 职责 | 工具 |
|-------|------|------|------|
| Supervisor | 路由器（非 ReAct） | 关键词启发式 + LLM 兜底识别意图 | — |
| chat | ReAct | 日常闲聊 | — |
| study | ReAct | 基于知识库答疑 | `knowledgeSearch` |
| quiz | ReAct | 出题 / 批改 / 薄弱点追踪 | `QuizTool`, `knowledgeSearch` |
| planner | ReAct | 生成按天学习计划 | `PlanTool` |
| coach | ReAct | 打卡监督 / 鼓励 / 重排 | `PlanTool`, `CheckinTool`, `ProfileTool`, `DateTimeTools` |

> Agent 注册是自动发现的：实现 `Agent` 接口并标注 `@Component` 即自动进入路由表（见 `AgentRegistry`）。

## 🚀 快速开始

### 前置要求

- JDK 17、Maven 3.8+
- **DashScope 百炼 API Key**（[阿里云百炼控制台](https://bailian.console.aliyun.com/) 获取）——RAG / 对话 / 计划等 AI 功能必需
- （可选）Docker —— 本地跑 Milvus；或注册 [Zilliz Cloud](https://cloud.zilliz.com) Serverless 免费 tier

### 1️⃣ 配置环境变量

```bash
export DASHSCOPE_API_KEY=sk-xxx                      # 必填
# 可选：本地 Milvus
docker compose -f vector-database.yml up -d          # 启动 Milvus + Attu
# 可选：Zilliz Cloud Serverless（设置后优先）
export MILVUS_URI=https://xxx.api.zilliz.com.cn
export MILVUS_TOKEN=xxx
# 可选：其他存储路径 / 端口见 .env.example
```

### 2️⃣ 启动应用

```bash
mvn spring-boot:run
# 或打包运行
mvn clean package -DskipTests
java -jar target/super-biz-agent-1.0-SNAPSHOT.jar
```

访问 **http://localhost:9900**（Web 界面）。

> **缺 DASHSCOPE_API_KEY 也能启动**：约 3 秒降级启动（AI 功能不可用，计划/打卡/测验等其余功能正常）。

### 3️⃣ 验证

```bash
# 健康检查
curl http://localhost:9900/milvus/health

# 上传一份文档（自动解析 + 向量化入库）
curl -X POST http://localhost:9900/api/upload -F "file=@教材.pdf"

# 知识库问答（流式）
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"什么是向量数据库？"}'
```

## ⚙️ 环境变量一览

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `DASHSCOPE_API_KEY` | ✅ | — | DashScope 百炼 API Key；缺省降级启动 |
| `DASHSCOPE_BASE_URL` | | `https://dashscope.aliyuncs.com` | 模型网关地址（兼容 OpenAI 格式） |
| `MILVUS_URI` / `MILVUS_TOKEN` | | — | Zilliz Cloud Serverless 接入（优先于本地） |
| `MILVUS_HOST` / `MILVUS_PORT` | | `localhost:19530` | 本地 Milvus |
| `SPRING_DATASOURCE_URL` | | `jdbc:sqlite:...` | SQLite 数据库地址 |
| `FILE_UPLOAD_PATH` | | `.../uploads` | 上传文件目录 |
| `APP_DATA_DIR` | | `...` | 应用数据目录 |
| `APP_MASTER_KEY` | | `study-assistant-default-key` | 主密钥（生产环境务必修改） |
| `GITHUB_REPO_URL` / `GITHUB_PUSH_ENABLED` | | 空 / `true` | 知识库 GitHub 真源（可选，需本机 SSH 可访问仓库） |
| `SERVER_PORT` | | `9900` | 服务端口 |

> 完整示例见 `.env.example`；`.env` 已被 `.gitignore` 忽略，不会提交。

## 📡 REST API

**对话 / Agent**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat_stream` | 流式对话（SSE），请求体 `{"Id":"会话ID","Question":"问题","Agent":"可选指定agent"}` |
| POST | `/api/chat` | 非流式对话 |
| POST | `/api/chat/clear` | 清空会话历史 |
| GET | `/api/chat/session/{sessionId}` | 会话信息 |
| GET | `/api/agents` | Agent 列表 |
| GET | `/api/models` | 可用模型 |

**知识库**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload` | 上传文件并自动向量化（multipart，上限 100MB） |
| POST | `/api/knowledge/upload` | 知识库上传 |
| POST | `/api/knowledge/sync` | 同步知识库 |
| GET | `/api/knowledge/list` | 知识库文件列表 |
| DELETE | `/api/knowledge/{id}` | 删除知识文件 |
| GET | `/milvus/health` | Milvus 健康检查 |

**学习计划**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / POST | `/api/plans` | 计划列表 / 新建 |
| POST | `/api/plans/create` | 新建（完整表单） |
| POST | `/api/plans/ai-detailed` | AI 按天拆解任务 |
| POST | `/api/plans/generate` | 自动生成计划 |
| GET / DELETE | `/api/plans/{id}` | 计划详情 / 删除 |
| PUT | `/api/plans/{id}/tasks/{taskId}` | 更新任务状态 |
| POST | `/api/plans/{id}/checkin` | 每日打卡 |
| POST | `/api/plans/{id}/replan` | 重排计划（教练对话触发） |
| GET | `/api/plans/{id}/dashboard` | 进度看板（含自动重排触发点） |
| GET | `/api/plans/{id}/weakness` | 薄弱点 |
| GET/POST/DELETE | `/api/plans/{id}/chapters` | 章节管理 |

**测验 / 记忆**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/quiz/generate` | 生成测验 |
| POST | `/api/quiz/grade` | 批改 + 记录薄弱点 |
| GET / PUT | `/api/profile` | 学习者画像（长期记忆） |
| GET | `/api/memory/sessions` | 会话历史列表 |
| GET | `/api/memory/sessions/{id}` | 会话详情 |

## 🐳 Docker 部署

```bash
# 构建
docker build -t studybuddy-ai .

# 运行（数据落持久卷 /data；缺 key 自动降级启动）
docker run --rm -p 9900:9900 \
  -e DASHSCOPE_API_KEY=sk-xxx \
  -e MILVUS_URI=https://xxx.api.zilliz.com.cn \
  -e MILVUS_TOKEN=xxx \
  -v studybuddy-data:/data \
  studybuddy-ai
```

- 容器默认数据目录 `/data`（SQLite / 上传 / 缓存），用 volume 持久化。
- 完整部署说明见 [`DEPLOY_POCKETBAY.md`](DEPLOY_POCKETBAY.md)。

## 📁 目录结构

```
src/main/java/org/example/
├── agent/               # Agent 定义（AgentRegistry 自动发现）+ Supervisor 路由
│   └── tool/            # 工具集（PlanTool / CheckinTool / QuizTool / KnowledgeSearchTool / ProfileTool / DateTimeTools / InternalDocsTools）
├── service/             # RAG 管线 + 业务
│   ├── TextExtractor / DocumentChunkService / VectorEmbeddingService
│   ├── VectorSearchService / RerankService / VectorIndexService
│   └── PlanService / CheckinService / QuizService / MemoryService / GithubService
├── controller/          # REST 接口（Chat / Plan / Quiz / Knowledge / Memory / Agents / Models / MilvusCheck）
├── config/              # SchemaMigrator（幂等迁移）/ MilvusConfig / ChatModelFactory / DashScopeConfig
├── client/              # MilvusClientFactory（Zilliz Serverless 数据库路由探测）
└── entity/ dto/ util/   # 实体 / DTO / 工具

src/main/resources/
├── static/              # 前端单页应用（index.html + app.js + styles.css）
├── application.yml      # 配置（全部环境变量占位符）
└── schema.sql           # SQLite 建表（SchemaMigrator 增量迁移兜底）
```

## 🔒 安全与开源说明

- **密钥不入库**：所有 API Key / Token 均通过环境变量注入，`application.yml` 只含 `${ENV:占位符}`；`.env` 已被 `.gitignore` 忽略，仓库内只有 `.env.example` 模板。
- **内部信息不留存**：仓库不含私有网关地址、内部 MCP 端点。
- ⚠️ **推送到公开仓库前**：请确认 git 历史中无敏感信息（可考虑以全新仓库 / 压缩历史的方式发布）。

## 📄 许可证

[Apache License 2.0](LICENSE)
