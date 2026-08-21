# 本地开发

## 前置条件

- JDK 21
- Node.js 22.12 或更高版本
- Docker Desktop 和 Docker Compose

后端 Maven 构建会校验 Java 版本。执行 `java -version` 和 `node --version` 确认当前终端环境。

## 数据库

从仓库根目录执行：

```powershell
docker compose up -d
docker compose ps
```

本地数据库地址为 `127.0.0.1:15432`，数据库名和用户名均为 `context_pilot`。Docker 初始化脚本启用 pgvector，Flyway 在后端启动时创建和校验应用表。

Docker 初始化脚本只在数据卷首次创建时执行。使用非 Docker PostgreSQL 时，数据库管理员需要先执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

## 上传目录

默认配置假设从 `backend/` 启动后端，并将 `STORAGE_ROOT` 解析为 `../data/uploads`。从 IDEA 或其他工作目录启动时，应显式设置为仓库根目录 `data/uploads/` 的绝对路径。

运行数据不得提交 Git。后续文件读写必须通过 `StorageService`，不能由 controller 或业务服务直接拼接路径。

文档实际存储键由后端生成，结构为 `knowledge-bases/{knowledgeBaseId}/documents/{documentId}/source.{ext}`；原始文件名不会作为磁盘路径。默认单文件限制为 20 MiB，可通过 `DOCUMENT_MAX_FILE_SIZE` 调整；multipart 请求限制通过 `DOCUMENT_MAX_REQUEST_SIZE` 单独配置，并应略大于单文件限制。

上传支持 UTF-8 编码的 TXT、Markdown 和带有效文件头的 PDF。文档解析使用 Spring AI 的文本、Markdown 和按页 PDF reader；不含可提取文本的扫描版 PDF 会被拒绝，当前范围不提供 OCR。

真实 Embedding 默认索引身份为 `dashscope_qwen3_7_1024_v1`，`offline` Profile 使用隔离的 `offline_deterministic_1024_v1`。不要让两个环境复用同一个 `EMBEDDING_PROFILE_ID`。模型、维度或影响向量语义的预处理变化后，应设置新的 profile ID，启动应用完成迁移，再在资料库页面对旧文档执行“重建索引”。

文本切分默认每块最多 1200 个字符并重叠 150 个字符，可分别通过 `DOCUMENT_CHUNK_MAX_CHARACTERS` 和 `DOCUMENT_CHUNK_OVERLAP_CHARACTERS` 覆盖。重叠值必须大于等于 0 且小于块大小。自动处理使用有界线程池，默认 1 个核心线程、最多 2 个线程、队列容量 50；每个文档默认最多进行 3 次处理尝试。

V3 健康检查默认最多返回 100 条异常明细，可通过 `HEALTH_ISSUE_LIMIT` 调整，合法范围为 1 到 500。聚合计数仍覆盖当前知识库的全部活动文档；达到明细上限时结果必须标记为截断。

聊天中输入完整句“检查这个知识库有没有异常”“检查当前知识库的健康状态”或 `knowledge base health check` 可触发确定性健康报告。额外附带总结、修复或自动执行要求的复合请求不会命中健康白名单。健康路径使用 `KNOWLEDGE_QA/v2`，SSE 会发送 `health_report`，且不调用聊天模型。

V13 会在后端启动时由 Flyway 自动扩展动作表，V14 会增加健康明细选择消息的匹配依据。不要手工执行这些 SQL；如手工改过结构但未登记迁移历史，Flyway 会因对象已存在或校验不一致而拒绝启动。切片 5 和切片 6 已允许在健康卡片中为可操作的失败文档或索引异常生成提案，客户端只发送报告 ID 和明细 ID；确认前后端会再次检查实时状态、profile、实际向量、处理开关和 VectorStore 可用性。切片 6 和切片 7 均没有新增数据库迁移。切片 7 在事务提交后派发文档任务，队列拒绝时保留 `PENDING`，并在应用启动和默认每 60 秒的低频扫描中按批次恢复；可通过 `DOCUMENT_PROCESSING_RECOVERY_BATCH_SIZE` 和 `DOCUMENT_PROCESSING_RECOVERY_INTERVAL_MILLIS` 调整批次与间隔。

## 启动和验证

后端：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

不配置任何模型 API Key 时，可使用离线 Profile 验证“上传、异步解析、切分、pgvector 入库、检索、删除清理”完整流程：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'offline'
.\mvnw.cmd spring-boot:run
```

离线 Profile 使用确定性的 1024 维测试向量，只适合功能测试，不代表真实语义检索效果。默认 Profile 不自动处理新上传文档，文档会保持 `PENDING`。

前端：

```powershell
cd frontend
npm ci
npm run typecheck
npm run dev
```

前端开发代理默认指向 `http://127.0.0.1:18080`；后端使用其他端口时，可通过 `VITE_API_PROXY_TARGET` 覆盖代理目标。

真实模型集成默认关闭。后续启用真实服务时，才需要在后端进程中设置 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 和对应 Spring AI 模型配置。

## AI 调用日志

后端默认以 `INFO` 级别记录安全的结构化 AI 调用生命周期，统一使用 `ai.call.started`、`ai.call.succeeded`、`ai.call.failed` 和 `ai.call.cancelled` 事件。覆盖普通聊天、SSE 聊天、V4 研究综合和文档 Embedding 入库；单次检索查询 Embedding 生命周期降为 `DEBUG`，避免多文档研究在 `INFO` 产生逐调用噪声。日志字段包括：

- `operation`、`provider`、`model`；
- 可用时的 `traceId`、每次都有的 `callId`、关联资源类型和资源 ID，其中聊天与研究的 `callId` 对应 `model_call.id`；
- Prompt 版本、输入字符数、文档或证据数量和最大输出 Token，不记录实际输入内容；
- 成功调用的耗时、Token 用量或结果数量；
- 失败调用的根异常类型、单行错误摘要和服务端异常堆栈。

可以使用前端 SSE 错误中的 `traceId` 查询后端日志和 `model_call.trace_id`，再使用 `callId` 对齐单次模型调用。日志不得输出 API Key、完整 Prompt、完整文档正文或完整模型回答；排查 HTTP 客户端问题时也不要在共享环境中直接开启可能打印请求正文或认证头的全局 `DEBUG`/`TRACE` 日志。

V4 研究在 `INFO` 使用成对的 `research.step.started`、`research.step.retrieval.completed` 和最终 `research.run.completed` 记录步骤及运行汇总。步骤检索汇总在该步骤全部文档检索结束后立即输出，包含 `traceId`、`runId`、步骤序号、文档数、命中数和步骤耗时；证据账本需要跨步骤统一去重与裁剪，因此最终证据字符数、截断状态和总耗时只在运行汇总中输出。逐文档的 `research.retrieval.*` 明细位于 `DEBUG`，并包含文档 ID，但不记录查询正文或证据正文。步骤之间仍按计划顺序执行；同一步骤内默认最多并行检索 4 份文档，可通过 `RESEARCH_RETRIEVAL_PARALLELISM` 配置为 1 至 5。结果始终按冻结文档列表顺序汇总，执行器在提交前再次强制最多 20 次检索调用。

AI 调用日志级别可通过 `AI_CALL_LOG_LEVEL` 调整，例如临时关闭成功调用日志时设置为 `WARN`；研究步骤日志可通过 `RESEARCH_LOG_LEVEL` 调整，需要查看逐文档检索明细时设置为 `DEBUG`。失败的 AI 调用使用 `ERROR`，仍会保留。示例：

```text
ai.call.failed operation=CHAT_STREAM provider=DEEPSEEK model=deepseek-v4-flash traceId=... callId=... resourceType=assistantMessage resourceId=... latencyMs=... errorType=... errorMessage=...
```

后端默认监听 `18080`，Knife4j 接口文档位于 `http://localhost:18080/doc.html`，原始 OpenAPI JSON 位于 `http://localhost:18080/v3/api-docs`。如需使用其他端口，可设置 `SERVER_PORT`；修改后还应同步调整前端开发代理目标。
