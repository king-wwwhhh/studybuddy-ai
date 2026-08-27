# 部署指南：期末速成助手 → PocketBay

本项目是一个 **单容器** Spring Boot 应用（项目根目录自带 `Dockerfile`），可直接部署到 PocketBay。

- **完整 RAG（知识库问答）可选**：接入 Zilliz Cloud 免费向量库即可；不接入也能用（自动降级，见文末）。
- **GitHub 备份在容器内默认关闭**（`GITHUB_PUSH_ENABLED=false`），数据只落本地 `/data`。

---

## 1. 部署前准备

| 需要的东西 | 必须？ | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | **必须** | 阿里云百炼 / DashScope 的 API Key（LLM + 向量化 + 重排都用它）。缺了它应用**能启动**（约 3s）但 AI 功能不可用，日志会提示 `DASHSCOPE_API_KEY 未正确配置`。 |
| Zilliz Cloud 免费 tier | 可选 | 想用完整「上传文档 → 知识库问答」就必须接。接入步骤见第 3 节。 |

> 关于模型地址（`DASHSCOPE_BASE_URL`）：
> - 默认走 **标准 DashScope 公网端点** `https://dashscope.aliyuncs.com`（docker 镜像内已内置，**不填即可**）。
> - 如果你使用私有 / 企业网关，请显式填：`DASHSCOPE_BASE_URL=https://你的网关地址`。

---

## 2. 环境变量清单（在 PocketBay 部署页配置）

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | ✅ | — | 模型 / 向量 / 重排 API Key |
| `DASHSCOPE_BASE_URL` | 否 | `https://dashscope.aliyuncs.com` | 标准 DashScope 公网端点；使用私有网关时覆盖 |
| `MILVUS_URI` | 可选 | 空 | Zilliz Cloud 公网 URI（填了才启用云端向量库） |
| `MILVUS_TOKEN` | 可选 | 空 | Zilliz Cloud API Key |
| `APP_MASTER_KEY` | 可选 | `study-assistant-default-key` | 建议改成你自己的随机字符串 |
| `SERVER_PORT` | 否 | `9900` | 容器内端口（已由 Dockerfile 内置，一般不用动） |

> 其余变量（`GITHUB_PUSH_ENABLED`、数据目录等）镜像内已按「容器安全默认」配好，不需要在 PocketBay 里填。

---

## 3. 接入 Zilliz Cloud（可选，要完整 RAG 才做）

### 3.1 选平台

| | 全球版 `cloud.zilliz.com` | 中国区 `cloud.zilliz.com.cn` |
|---|---|---|
| 免费 Serverless | ✅（1 个组织限 1 个免费集群） | ✅（地域 `ali-cn-hangzhou`） |
| 注册 | 邮箱 / Google / GitHub | 手机号 / 邮箱（可能要求实名） |
| 免费集群地域 | `gcp-us-west1` | `ali-cn-hangzhou` |

> 快速试通用**全球版**最省事。下面步骤以全球版为例，中国区只是域名和地域名不同。
> **不用手动建任何表**：应用首次连接会自动创建 `biz` 集合 + 索引。

### 3.2 创建免费 Serverless 集群

1. 打开 <https://cloud.zilliz.com/signup> 注册并登录。
2. 左侧 **Clusters** → 点 **Create Free Cluster**（或 Create Cluster → 类型选 **Serverless**）。
3. 填 **Cluster Name**（如 `studybuddy`）→ Region 选 `gcp-us-west1`（全球版唯一免费地域）。
4. 点 **Create**。页面弹出的**管理员账号密码只显示一次，先存好**（但应用连接**不用**它）。
5. 等状态变 **Running**（几分钟）。

### 3.3 拿 Public Endpoint（URI）和 API Key（Token）

**① URI**：点进集群 → **Connection** 页 → 复制 **Public Endpoint / URI**，形如：
```
https://in03-xxxx.serverless.gcp-us-west1.cloud.zilliz.com
```
→ 这就是 **`MILVUS_URI`**。

**② Token**：控制台 → **Project Settings → API Keys** → **Create API Key**（起名如 `studybuddy-key`）→ 复制生成的 key（只显示一次，一长串）→ 这就是 **`MILVUS_TOKEN`**。

> 注意：不要用创建集群时给的「用户名/密码」，那是管理台用的；SDK 连接用的是 **Public Endpoint + API Key**。

### 3.4 填入 PocketBay 并 Redeploy

| 变量 | 填什么 |
|---|---|
| `MILVUS_URI` | 完整的 Public Endpoint，如 `https://in03-xxxx.serverless.gcp-us-west1.cloud.zilliz.com` |
| `MILVUS_TOKEN` | API Key 一长串 |

保存后 **Redeploy / 重启**。

### 3.5 验证是否接通（3 选 1）

1. **看日志**：出现 `成功连接到 Milvus` 和 `collection 'biz' 不存在，正在创建...`（首次自动建表）即成功。
2. **传文档**：上传 `.txt` 返回消息**不再带**「Milvus 降级」，而是「摄取成功」。
3. **看控制台**：Zilliz 集群页面出现 `biz` 集合，传文档后行数增长。

### 3.6 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 日志一直 `DEADLINE_EXCEEDED`（30s 超时） | URI 或 Token 填错 / 集群没 Running。核对 Endpoint 字符串、API Key 是否复制全 |
| `创建 collection 失败` | 免费集群权限限制 → 可在控制台手动建一个空 `biz` 集合兜底 |
| 上传仍提示「Milvus 降级」 | `MILVUS_URI`/`MILVUS_TOKEN` 未生效（没填对或没 Redeploy） |
| 免费额度用尽 | 免费 Serverless 有存储/实体上限；报配额错误时清理旧文档或升级 |

---

## 4. PocketBay 部署步骤

1. 打开 PocketBay，**新建应用**。
2. 关联/上传本项目（**根目录必须包含 `Dockerfile`**，平台会自动用它构建）；也可以关联你自己的 GitHub 仓库。
3. 在环境变量页按第 2 节表格填入（至少 `DASHSCOPE_API_KEY`；要 RAG 再加 `MILVUS_URI`/`MILVUS_TOKEN`）。
4. 点击 **Deploy**，等待构建完成（Maven 在线下载依赖 + 打包，首次约几分钟）。
5. 构建完成后平台会给一个 `https://<你的应用名>.pocketbay.app` 子域名。

> **如果部署报 `健康检查失败 [startup_timeout]` / `应用日志已停止输出`，两类已知根因均已修复：**
>
> 1. **缺 `DASHSCOPE_API_KEY` 启动崩溃**（`d4439e5`，2026-08-26）：旧代码在 key 缺失/为空时于启动期抛异常。已修复为缺 key 也 ~3s 启动、健康检查通过（AI 功能降级，其余正常），Milvus 超时 30s→10s。
> 2. **`/data/study.db: Permission denied`**（`docker-entrypoint.sh`，2026-08-26）：平台持久卷挂载 `/data` 后属主是 root，容器内非 root 用户写不进去导致数据库初始化崩溃。已改为 root 启动 → entrypoint chown `/data` 给 appuser → 降权运行应用，已在全新 root 属主卷上实测启动成功。
>
> 请确认仓库已拉到最新提交（平台重新读取仓库即可），再重试 Deploy；部署成功后补填环境变量并 Redeploy 恢复完整功能。

---

## 5. 部署后验证清单

- [ ] 打开 `https://<应用名>.pocketbay.app`，能看见前端主页。
- [ ] 聊天（非知识库问题）能正常回复 → 说明 `DASHSCOPE_API_KEY` 配置正确。
- [ ] **（若接了 Zilliz）** 知识库页上传一个 `.txt`，提示「摄取成功」；然后问一个文档里才有的问题，能检索到并引用。
- [ ] **（未接 Zilliz）** 上传文档会提示「Milvus 降级」，文件记录仍会落库，但知识库问答不可用。
- [ ] 计划 / 打卡 / 测验功能可正常使用。
- [ ] 重启应用（或重新部署）后数据仍在（SQLite / 上传文件在 `/data` volume，已持久化）。

---

## 6. 降级与边界说明

| 场景 | 行为 |
|---|---|
| 未填 `MILVUS_URI`/`MILVUS_TOKEN` | 应用尝试连本地 `localhost:19530` → 失败自动进入**降级模式**：知识库 RAG 关闭，其余功能（对话 / 计划 / 打卡 / 测验）正常 |
| Zilliz 免费额度用尽 | Milvus 请求报配额错误 → 上传/检索会失败，日志有明确报错；清理文档或升级即可恢复 |
| 容器内 GitHub 备份 | 默认关闭（本地落盘）。如需云端备份，需要单独配置专用 SSH key + `GITHUB_REPO_URL`，默认不做 |

> 安全提醒：`DASHSCOPE_API_KEY` 等密钥只填在 PocketBay 的环境变量里，**不要**写进代码或提交到仓库。
