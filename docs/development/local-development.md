# 本地开发

2026-10-23 日常阅读真实调用使用[单开关配置](reading-direct-models.md)：本机 `backend/config/application-local-secrets.yml` 的 `contextpilot.reading.ai-enabled: true/false`，不需要金额预算、批准文件或期限。启动时使用 local-secrets profile。旧试读端口已关闭，用户自行启动日常前后端。

真人小说阅读试读使用[独立试读入口](reading-reader-trial.md)：`tools/reading-trial.ps1`，本机页面 5201、后端 18081、独立持久数据库 15434。它读取现有本地密钥与单独批准文件，保留日常环境及已停止评测账本；启动本身不调用模型。

## 前置条件

- JDK 21
- Node.js 22.12 或更高版本
- Docker Desktop 和 Docker Compose

后端 Maven 构建会校验 Java 版本。执行 `java -version` 和 `node --version` 确认当前终端环境。

## 单用户访问边界

研序 当前没有登录、认证、角色或多用户隔离，只适合本机或可信网络。V5 Slices 2-4 已引入服务端稳定的隐式用户 `LOCAL_OWNER`、`CurrentSubjectProvider` 和 `/memories` 管理入口，用于让经确认的显式记忆跨会话保持归属并可由用户纠正、启停或逻辑删除；它不会验证访问者身份，所有能访问实例的人都会共享同一用户和记忆。

客户端不得提交或覆盖用户 ID。不要仅依赖隐式用户把当前应用暴露到公网；如需让不受信任的人访问，必须先单独设计并实现登录、完整资源所有权迁移和服务端授权过滤。

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

健康卡片可为失败文档或索引异常生成提案；客户端只发送报告 ID 和明细 ID，确认时后端复核实时状态、profile、向量、处理开关和 VectorStore 可用性。文档任务在事务提交后派发，队列拒绝时保留 `PENDING`；应用启动及默认每 60 秒扫描恢复遗留任务，配置项为 `DOCUMENT_PROCESSING_RECOVERY_BATCH_SIZE` 和 `DOCUMENT_PROCESSING_RECOVERY_INTERVAL_MILLIS`。迁移由 Flyway 自动执行，不手工重放 SQL。

## 启动与验证

默认模型集成和文档自动处理关闭，上传文档会保持 `PENDING`。以下分别说明无密钥功能验证和真实模型运行，配置以 `application.yml` / `application-offline.yml` 为准。

### 两种运行方式的共同准备

从仓库根目录复制 `.env.example` 为 `.env`（已有文件则保留），配置本地数据库后执行 `docker compose up -d`。Compose 读取 `.env`，Spring Boot 不会自动读取它；若更改数据库密码，应在后端终端中设置相同的 `POSTGRES_PASSWORD`，不要把密钥打印到日志或提交到仓库。

前端在独立终端从 `frontend/` 运行 `npm ci`、`npm run dev`。开发代理默认指向 `http://127.0.0.1:18080`，可通过 `VITE_API_PROXY_TARGET` 覆盖。启动后端前确认 Java 21，Windows 可使用项目约定 JDK：

```powershell
$env:JAVA_HOME = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

### 无密钥演示

在新的后端终端中，从 `backend/` 执行，避免继承真实模型开关：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'offline'
$env:SPRING_AI_CHAT_MODEL = 'none'
$env:SPRING_AI_EMBEDDING_MODEL = 'none'
$env:SPRING_AI_VECTOR_STORE = 'pgvector'
$env:DOCUMENT_PROCESSING_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

可使用 `evals/corpora/v4-document-comparison-v1/` 中的公开合成 Markdown 验证文档上传、处理状态、隔离检索与页面；其中 `quartz-injection.md` 是对抗样例，`shadow-out-of-scope.md` 用于范围隔离，不应混作普通可信资料。该方式使用确定性测试向量且没有真实聊天模型，不能证明真实回答、偏好遵循、多文档综合质量或检索优化收益。

### 真实 DeepSeek + DashScope

在新的后端终端中，从 `backend/` 执行；通过本地安全配置事先注入 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY`，不要在共享命令记录中写实际密钥：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'default'
$env:SPRING_AI_CHAT_MODEL = 'deepseek'
$env:SPRING_AI_EMBEDDING_MODEL = 'openai'
$env:SPRING_AI_VECTOR_STORE = 'pgvector'
$env:DOCUMENT_PROCESSING_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

默认聊天为 `deepseek-v4-flash`，Embedding 为 `qwen3.7-text-embedding` / 1024 维。保持真实与离线 profile ID 隔离；切换后旧文档需通过现有重建入口生成当前 profile 的索引，不得通过改 ID 冒充真实向量。

用公开样例验证上传 → 处理完成 → 知识问答/显式多文档比对 → 引用/历史，再验证偏好提案、确认、单次忽略及使用卡片。真实服务会发送公开样例片段并产生调用费用，记录 traceId、usage 和失败终态。版本评估命令见 [evals](../../evals/README.md)；V5 最终验收已完成，见 [验收报告](../evaluation/v5-acceptance-report.md)。录屏和完整应用从干净环境复跑仍是展示交付项，不能把 Testcontainers 数据库验证等同于干净机器安装验证。

### 工程检查

| 位置 | 命令 | 用途 |
| --- | --- | --- |
| `backend/` | `.\mvnw.cmd test` | 后端回归 |
| `backend/` | `.\mvnw.cmd package` | 测试与打包 |
| `frontend/` | `npm run typecheck` | TypeScript 检查 |
| `frontend/` | `npm run build` | 类型检查与生产构建 |

Unix 使用 `./mvnw`。付费模型测试的启用条件见[评估入口](../../evals/README.md)，测试通过不等于真实语义验收通过。

## V6 升级研究联调

在上述真实模型、Embedding、pgvector 和文档处理配置基础上设置 `$env:UPGRADE_RESEARCH_ENABLED='true'`，打开 `/upgrade-research`。默认关闭新建研究，既有运行与报告仍可查看；重启时遗留活动运行会失败收敛，不自动重新调用模型。只支持固定 `spring-projects/spring-ai` 公开仓库及用户明确选择的标签。可选 `GITHUB_RELEASE_TOKEN` 仅由后端读取；不要把 token 放进前端或来源 URL。

单次运行最多 4 次模型调用、8 次工具请求、9 次逐文档检索、3 次 HTTP 请求和 180 秒总时间。默认不发送请求级 `max_tokens`；调用数和时间限制不构成费用硬上限。研究草稿不自动成为报告，须创建保存提案并单独确认。

真实调试测试使用独立 Testcontainers 数据库和公开调试文档；须事先配置本地 `local-secrets`，从 `backend/` 执行 `./mvnw.cmd -Dtest=V6LiveDebugTests -Dcontextpilot.evaluation.v6-debug=true test`。结果保存在 `target/v6-live-debug.json`，包含公开调试回答；这是调试证据，不进入最终任务成绩。普通 `test`/`package` 跳过付费调试。最终语义评测与生产验收尚未完成，不能凭一次调试通过宣称 V6 已验收。

## 研究任务工作台运行

在真实模型、pgvector 与文档处理配置基础上，设置 `$env:TASK_WORKSPACE_ENABLED='true'` 后启动后端，访问前端 `/research-tasks`。默认关闭该入口对应 API；开启后 Flyway 自动创建任务与事件表。先生成计划，再点击“按计划开始研究”；澄清或补充会保留旧轮次检查点，暂停后可继续尚未完成的步骤。

普通 `mvnw.cmd package` 包含离线和真实 PostgreSQL 工程验证，不调用付费模型。公开样例真实验证命令为 `./mvnw.cmd -Dtest=TaskWorkspaceLiveTests -Dcontextpilot.evaluation.task-live=true test`，需要 local-secrets 与 Docker，输出 `backend/target/task-workspace-live.json`。结果是调试证据，不作为独立语义成绩。当前轮次结果与数据库检查点通过 API 获取，页面不依赖浏览器本地状态恢复。更多约束见[工作台设计](../architecture/research-task-workspace-design.md)。

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

## 项目资料接入

独立 MCP 服务、功能开关、确认导入、公开研究演示与恢复说明见 [项目资料接入运行指南](project-source-integration.md)。
