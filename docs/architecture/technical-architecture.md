# ContextPilot 技术架构

> 状态：MVP 架构基线

## 架构形式

系统采用 Vue 前端与 Spring Boot 后端分离的模块化单体。后端使用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x；不拆分微服务，不引入消息队列、Agent 或工作流引擎。

## 后端能力边界

- `knowledgebase`：知识库元数据和生命周期。
- `document`：文件存储、解析、切分、索引任务和状态。
- `retrieval`：基于 `PgVectorStore` 的向量检索。
- `chat`：会话、消息以及 RAG 问答编排。
- `model`：ChatModel 和 EmbeddingModel 的应用级边界。
- `observation`：模型调用记录和最小指标。
- `feedback`：回答反馈。
- `common`：少量跨模块配置、错误类型和基础约定，不承载业务实现。

模块按业务能力组织，简单功能不强制创建空的 controller/service/mapper 层。`chat` 可以编排检索与模型调用，供应商专用配置不得散落到业务模块。

## 数据流

```text
文档上传 -> StorageService -> 解析与切分 -> DashScope Embedding
         -> PgVectorStore/PostgreSQL

用户问题 -> DashScope Embedding -> pgvector 检索 -> DeepSeek ChatModel
         -> SSE 回答与引用 -> 会话、调用记录和反馈
```

## 基础设施职责

- Flyway 管理业务表和 `vector_store` 表结构。
- Docker 初始化脚本为本地 PostgreSQL 启用 `vector` 扩展。
- 上传文件位于 Git 忽略的 `data/uploads/`，后端通过 `STORAGE_ROOT` 定位。
- 文档处理使用有界 `TaskExecutor`，任务状态持久化到 PostgreSQL。
- 所有模型凭据仅由后端环境变量提供。

## 版本化资产

提示词位于 `backend/src/main/resources/prompts/`，数据库迁移位于 `backend/src/main/resources/db/migration/`，评估数据位于 `evals/`，重要技术决策记录为 ADR。
