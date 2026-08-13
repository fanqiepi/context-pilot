# ContextPilot 项目背景

> 每次开始新的开发对话时，应先阅读本文，再结合当前代码、配置和 Git 状态开展工作。

## 项目定位

ContextPilot 是一个基于 Spring AI 的学习型单用户知识库与任务助手。功能型 MVP 已打通“资料上传、文档处理、向量检索、带引用的流式问答、历史记录与反馈”闭环，项目继续采用前后端分离的模块化单体架构。

功能型 MVP 已正式完成，项目进入持续完善阶段。当前完善范围包含固定能力路由、受控业务操作、Embedding 索引治理、检索质量、页面验收、评估基线与发布加固；仍不引入 Agent、LangGraph 或其他工作流引擎、微服务、消息队列、多租户、登录权限、独立向量数据库、插件系统或 OCR。

## 当前状态

- 已完成工程基线、PostgreSQL/pgvector 与 Flyway、知识库 CRUD，以及文档上传、异步解析切分、状态与有限重试、pgvector 索引、知识库隔离检索和逻辑删除后的向量清理。
- 前端已实现知识库与文档管理、文档处理状态轮询、独立聊天、SSE 增量回答、引用展示和会话历史恢复；类型检查和生产构建已通过，仍需在本地真实后端链路上完成页面级验收。
- 已提供无需 API Key 的 `offline` Profile 和确定性 1024 维测试 Embedding；真实 DashScope `qwen3.7-text-embedding`、非流式 RAG 问答和 DeepSeek SSE 均已通过本地真实链路验证。SSE 事件顺序、取消和失败落库已有自动化测试覆盖。
- 已提供按知识库查询会话列表和读取会话消息、引用的后端接口，前端通过这些接口恢复历史记录，并通过 `/api/chat/stream` 完成流式问答。
- 已实现已完成助手回答的单向“有用”反馈：用户可以标记或取消，历史消息会恢复反馈状态；消息、知识库和 trace ID 的关联由后端可信解析。
- 聊天在检索前通过固定白名单处理身份介绍、问候、能力说明、感谢和告别，不调用向量检索或模型；其他无可靠证据的问题仍然拒答。
- 产品决策确认功能型 MVP 已完成，后续工作统一归入“功能完善阶段”；前端真实页面验收、固定评估基线和发布前加固仍需逐步补齐，不应被误写为已完成。
- 已实现 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION` 三类固定、版本化能力路由：简单交互白名单优先，明确创建知识库意图次之，其余安全回落到知识库问答；路由结果随聊天消息持久化并通过即时响应和历史接口返回。
- `CREATE_KNOWLEDGE_BASE` 当前只完成业务意图识别并返回不会执行副作用的安全提示；操作提案、独立确认/拒绝接口、状态机、幂等执行和前端操作卡片尚未实现，属于下一开发切片。
- 已实现 Embedding 索引版本追踪与隔离：文档记录 profile、供应商、模型、维度、版本与索引时间，向量元数据记录同一 profile 来源；检索同时按知识库和当前 profile 过滤。迁移前的成功文档显示为来源未知，可通过文档重建索引接口升级。
- 日常开发和集成分支为 `develop`，不得直接提交到 `main`。

## 路线图边界

- 完善阶段保留已有 RAG、知识库隔离、拒答、引用、SSE、历史和反馈行为；任何新增能力不得破坏这些基线。
- 路由先使用确定性规则：简单交互白名单优先，明确业务意图次之，其余安全回落到 `KNOWLEDGE_QA`。在没有歧义数据支撑前，不增加每次请求都调用模型的分类器。
- 业务操作必须先生成经过强类型校验并持久化的提案，再等待用户通过独立请求确认；实际工具不能在确认前执行，重复确认不能造成重复副作用。
- 首个操作仅允许通过静态注册的应用动作调用现有 `KnowledgeBaseService` 创建知识库。通用 Skill 注册表、通用工具网关、动态工具、任意 SQL、Shell、文件或 HTTP 工具仍未授权。
- 项目 PostgreSQL 业务数据仍只能通过领域服务、Mapper 或后续明确批准的只读 DataPort 访问，模型不能直接获得数据库执行能力。
- 完整阶段计划见 `docs/requirements/product-requirements.md`，参考时序图适配结论见 `docs/architecture/agent-skill-tool-adaptation.md`。

## 技术栈

- 后端：Java 21、Spring Boot 4.1.x、Spring AI 2.0.x、Maven、MyBatis-Plus、Flyway。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router。
- 数据库：PostgreSQL 17 + pgvector，数据库名和用户名均为 `context_pilot`。
- 聊天模型：DeepSeek `deepseek-v4-flash`。
- Embedding：DashScope `qwen3.7-text-embedding`，固定 1024 维。
- 向量存储：Spring AI `PgVectorStore`，使用 `public.vector_store`、UUID、HNSW 和余弦距离。
- 接口文档：Knife4j Next 5.2.x 的 Spring Boot 4 OpenAPI3 starter。

## 本地环境

仓库位于 `F:\development\project\ContextPilot`，本机使用 Windows PowerShell。JDK 21 安装在：

```text
F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9
```

当前终端可能默认使用 Java 8，执行后端命令前应切换：

```powershell
$env:JAVA_HOME = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
java -version
```

Node.js 要求不低于 22.12.0。后端使用仓库内 Maven Wrapper；本地还需要 Docker Desktop 和 Docker Compose。

## 服务与配置

- 前端开发服务器：`http://localhost:5173`。
- 后端默认端口：`18080`；Knife4j：`http://localhost:18080/doc.html`；OpenAPI JSON：`http://localhost:18080/v3/api-docs`。
- 前端 `/api` 和 `/actuator` 代理到 `http://127.0.0.1:18080`。
- PostgreSQL：`127.0.0.1:15432/context_pilot`。
- 上传目录：仓库根目录下 Git 忽略的 `data/uploads/`；从 `backend/` 启动时默认值为 `../data/uploads`。
- 支持 UTF-8 TXT、Markdown 和文本型 PDF，默认单文件上限 20 MiB。
- 文本切分默认最大 1200 个字符、重叠 150 个字符；默认 Profile 不自动处理，`offline` Profile 自动完成入库与检索。

常用环境变量：

| 变量 | 本地默认值或用途 |
| --- | --- |
| `POSTGRES_PASSWORD` | 本地数据库密码，示例值为 `context_pilot_local_dev` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:15432/context_pilot` |
| `DB_USERNAME` | `context_pilot` |
| `STORAGE_ROOT` | `../data/uploads` |
| `DOCUMENT_MAX_FILE_SIZE` | `20MB` |
| `DOCUMENT_MAX_REQUEST_SIZE` | `21MB` |
| `DOCUMENT_CHUNK_MAX_CHARACTERS` | `1200` |
| `DOCUMENT_CHUNK_OVERLAP_CHARACTERS` | `150` |
| `DOCUMENT_PROCESSING_ENABLED` | 默认 `false`；显式开启上传后的异步处理 |
| `DOCUMENT_PROCESSING_CORE_POOL_SIZE` | `1` |
| `DOCUMENT_PROCESSING_MAX_POOL_SIZE` | `2` |
| `DOCUMENT_PROCESSING_QUEUE_CAPACITY` | `50` |
| `DOCUMENT_PROCESSING_MAX_ATTEMPTS` | `3`，包含首次处理 |
| `CHAT_STREAM_CORE_POOL_SIZE` | SSE 异步执行器核心线程数，默认 `2` |
| `CHAT_STREAM_MAX_POOL_SIZE` | SSE 异步执行器最大线程数，默认 `8` |
| `CHAT_STREAM_QUEUE_CAPACITY` | SSE 异步执行器队列容量，默认 `100` |
| `CHAT_STREAM_TIMEOUT_MILLIS` | MVC SSE 请求超时，默认 `120000` 毫秒 |
| `SPRING_PROFILES_ACTIVE` | 本地无密钥闭环测试时设为 `offline` |
| `SERVER_PORT` | `18080` |
| `SPRING_AI_CHAT_MODEL` | 启用时设为 `deepseek` |
| `SPRING_AI_EMBEDDING_MODEL` | 启用时设为 `openai` |
| `SPRING_AI_VECTOR_STORE` | 启用时设为 `pgvector` |
| `EMBEDDING_PROFILE_ID` | 当前索引空间的稳定身份，默认 `dashscope_qwen3_7_1024_v1`；模型、维度或预处理语义变化时必须使用新值 |
| `EMBEDDING_PROVIDER` | 索引来源供应商，默认 `DASHSCOPE` |
| `EMBEDDING_MODEL` | 索引来源模型，默认 `qwen3.7-text-embedding` |
| `EMBEDDING_DIMENSIONS` | Embedding 维度，默认 `1024` |
| `EMBEDDING_PROFILE_VERSION` | 索引配置版本，默认 `v1` |
| `DEEPSEEK_API_KEY` | 后端专用 DeepSeek 密钥 |
| `DASHSCOPE_API_KEY` | 后端专用 DashScope 密钥 |

仓库根目录 `.env` 主要供 Docker Compose 读取，Spring Boot 不会自动读取它。模型密钥应注入后端进程或 IDE 运行配置，不得写入 Git、日志或前端代码。

## 常用命令

```powershell
# 仓库根目录
docker compose up -d
docker compose ps

# backend/
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd spring-boot:run

# frontend/
npm ci
npm run typecheck
npm run build
npm run dev
```

修改行为、架构、提示词或评估标准时，应在同一变更中更新对应文档。任何 API Key、`.env`、私密文档、上传数据和敏感评估数据都不得提交。
