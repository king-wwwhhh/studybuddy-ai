# StudyBuddy 学习助手 —— 部署与演示手册

> 本文件面向**答辩演示与部署验收**：怎么启动、怎么登录、怎么演示、出问题怎么查。
> 代码仓库：`workbuddy-ai`（remote `public` → `github.com/king-wwwhh/studybuddy-ai`）

---

## 一、环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 已用 17 验证 |
| Maven | 3.9+ | 离线仓库可用时加 `-o` |
| Node.js | 18+ | 仅前端构建需要（已用 22 验证） |
| SQLite | 内嵌 | 无需安装，数据落 `study.db` |
| Milvus | 可选 | 未启动时自动降级（RAG 关闭、其余功能正常） |

---

## 二、启动步骤

### 1. 后端

```bash
cd workbuddy魔改
mvn -o -B spring-boot:run
# 默认端口 9900
```

必须配置的环境变量（**生产**）：

```bash
# 数据库（可选，默认 C:/Users/<user>/study_agent_data/study.db）
SPRING_DATASOURCE_URL=jdbc:sqlite:/data/study.db

# JWT 密钥：至少 32 位随机串（不配置则每次重启 token 失效，仅开发可用）
AUTH_JWT_SECRET=<至少32位随机串>

# 大模型（不配置则 AI 对话返回明确的鉴权失败提示，其余功能正常）
DASHSCOPE_API_KEY=sk-xxxx
CHAT_MODEL=qwen3.7-max

# 邮件（QQ 邮箱：设置 → 账户 → 开启 SMTP → 生成**授权码**，不是登录密码）
MAIL_USERNAME=你的QQ号@qq.com
MAIL_PASSWORD=授权码

# Milvus（可选；不配置则向量检索降级）
MILVUS_URI=...
MILVUS_TOKEN=...
```

### 2. 前端

**开发模式**（热更新，代理 /api 到 9900）：

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

**生产构建**（产物进后端静态目录，由 Spring Boot 托管）：

```bash
cd frontend && npm run build
# 之后直接访问 http://localhost:9900 即可（静态资源已配置为读源码目录，刷新即生效）
```

---

## 三、首次使用（3 步）

1. 打开 `http://localhost:9900` → 自动跳登录页；
2. 切到「注册」→ 填邮箱 → 点「获取验证码」；
   - **未配置 SMTP 时**：验证码打印在后端日志（`target/app.log`），搜 `code=` 即可拿到；
   - 配置了 SMTP：查收邮件（含垃圾箱）；
3. 注册成功自动登录。**第一个注册的账号自动成为管理员**。

---

## 四、演示脚本（建议 5 分钟）

| 步骤 | 操作 | 展示点 |
|---|---|---|
| 1 | 未登录访问 → 被重定向到登录页 | 路由守卫 |
| 2 | 注册 A 账号（自动 ADMIN）→ 建 1 个计划 | 验证码注册、角色引导 |
| 3 | 注册 B 账号 → 看「学习计划」为空 | **多用户隔离**：B 看不到 A 的计划 |
| 4 | B 尝试直接访问 A 的计划 id | 返回 404（不泄露存在性） |
| 5 | A 登录 → 管理后台 | **ECharts 大屏**：用户数/趋势/知识库分布 |
| 6 | 上传一份 PDF/MD 到知识库 | 解析→切片→向量化（失败时有明确状态） |
| 7 | AI 对话问「帮我梳理向量检索原理」 | **SSE 流式**输出 + Agent 徽标 |
| 8 | 停掉 Milvus 或改错 API Key | **容错演示**：明确提示而非白屏 |

---

## 六、常见问题

## 五、健康检查

```bash
curl http://localhost:9900/api/health
# {"status":"UP","time":"...","db":"UP","users":6}
# 不要求登录，可直接用于容器探针 / 部署平台探活
```

| 现象 | 原因 | 处理 |
|---|---|---|
| AI 对话显示「大模型鉴权失败」 | 未配置 `DASHSCOPE_API_KEY` | 配置后重启 |
| 日志出现 `url error, please check url` | 该模型在此端点不可用（实测 `qwen3.5-flash` 会报） | 启动日志会列出「可用=[...]」，换用其中的模型；`chat.model` 留空则自动选 |
| `/api/milvus/health` 404 | 该端点实际路径是 `/milvus/health`（无 `/api` 前缀） | 用 `curl http://localhost:9900/milvus/health` |
| 上传成功但 `status: READY` 且 message 含「Milvus 降级」 | Milvus 未启动，降级模式只做解析不写向量 | 启动 Milvus 后重新上传，或接受降级（其余功能正常） |
| 启动日志 WARN「索引类型从 IVF_FLAT 变更为 FLAT：默认不自动重建」 | 已有 collection 用旧索引，配置期望按数据量选 FLAT | **行为正确**（检索会按实际 IVF_FLAT 传 nprobe）。要切换：`milvus.index.auto-rebuild=true` 重启，或手工 dropIndex |
| `docker compose up` 提示 `a network with name milvus exists` | 之前创建过同名网络 | 只是警告，容器照常启动；消除可加 `external: true` |
| 收不到验证码 | 未配 SMTP | 从 `target/app.log` 搜 `code=` |
| 重启后登录态失效 | 未配 `AUTH_JWT_SECRET`（用了随机密钥） | 配置固定密钥 |
| 知识库上传后检索不到 | Milvus 未启动 → 降级模式 | 启动 `docker compose -f vector-database.yml up -d` |
| 前端改动不生效 | 浏览器缓存 | 硬刷新；已配置 `cache.period: 0` |

---

## 七、实测验证记录（2026-10-31，本机）

| 项目 | 结果 |
|---|---|
| 服务启动 | `Tomcat started on port 9900`，`/api/health` → `{"status":"UP","db":"UP"}` |
| 模型探测 | 配 `DASHSCOPE_API_KEY` 后：`可用=[qwen3.7-max, qwen-max, qwen-plus, qwen-plus-latest, qwen-flash]` |
| 非流式对话 | `POST /api/chat` → 成功，Agent=`chat`，耗时 **7s** |
| **流式对话** | `POST /api/chat_stream` → **18 个 content 增量块 + 1 个 done**，耗时 4.6s，回答 98 字 |
| 前端聊天页 | Playwright 验证：回复渲染 + **「通用学伴」Agent 徽标** + 会话列表，0 控制台错误 |
| 文档上传 | `POST /api/knowledge/upload` → 解析出 **2 个分片**，状态 READY |
| Milvus | **已启动** → `/milvus/health` → `{"collections":["biz"],"message":"ok"}` |
| **RAG 全链路** | 上传 `_rag_doc.md` → **摄取成功**（2 分片写入 Milvus）→ 提问「IVF_FLAT 和 HNSW 区别」→ Agent 路由 **study（答疑导师）**，**22s** 返回索引对比回答，命中知识库 |
| 后端测试 | 143 例全绿 |
| E2E 回归 | 23 条断言全通过 |

> Milvus 启动：`docker compose -f vector-database.yml up -d`（etcd + minio + milvus + attu，约 1 分钟全部 healthy）。
> 端口：19530（SDK）/ 9091（健康检查）/ 8000（Attu 管理界面）。

## 八、测试与验收

```bash
# 后端单元 + 集成测试（143 例）
mvn -o -B test

# 端到端回归（23 条断言：鉴权/角色/隔离/统计）
node scripts/e2e.mjs        # 需后端运行中
```

---

## 九、目录结构

```
workbuddy魔改/
├── src/main/java/org/example/
│   ├── auth/          认证（JWT、验证码、拦截器、OwnerSupport、MailService）
│   ├── controller/    12 个控制器，49 个端点
│   ├── service/       业务 + 隔离守卫 + 评测器 + 分片器
│   ├── agent/         5 个 Agent + 路由 + 工具
│   └── config/        SchemaMigrator、LegacyDataMigration、线程池、静态资源
├── src/main/resources/
│   ├── application.yml
│   ├── schema.sql     10 张表
│   └── static/        Vue 构建产物（含 static-legacy/ 旧版备份）
├── src/test/java/     27 个测试类 / 143 个用例
├── frontend/          Vue3 + Vite + Element Plus 源码
└── scripts/e2e.mjs    端到端回归
```
