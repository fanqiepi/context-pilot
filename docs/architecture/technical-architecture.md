# ContextPilot 技术架构

> 状态：MVP 架构基线

## 架构形式

系统采用 Vue 前端与 Spring Boot 后端分离的模块化单体。后端使用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x；不拆分微服务，不引入消息队列、Agent 或工作流引擎。

前端使用 Vue 3、TypeScript、Vue Router 和 Element Plus。`/library` 承载知识库、文档上传与处理状态管理，`/chat` 承载知识库选择、会话历史和流式问答。普通 HTTP 请求统一通过 Axios 访问 `/api`，SSE 使用 `@microsoft/fetch-event-source` 解析具名事件；聊天页对突发到达的 `delta` 使用短缓冲渐进渲染，并在缓冲区清空后再展示引用和完成状态。开发服务器将 `/api` 和 `/actuator` 代理到后端。模型密钥不进入浏览器，回答和引用按纯文本渲染，不信任模型输出或文档内容中的 HTML。

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
文档上传 -> StorageService -> 有界 TaskExecutor -> 解析与切分 -> EmbeddingModel
         -> PgVectorStore/PostgreSQL

用户问题 -> 会话/知识库校验 -> DashScope Embedding -> pgvector 隔离检索
         -> 证据校验/拒答 -> DeepSeek ChatModel -> SSE 回答与引用
         -> 会话、调用记录、trace ID 和反馈
```

文档解析使用 Spring AI 的 `TextReader`、`MarkdownDocumentReader` 和 `PagePdfDocumentReader`。PDF 按页解析以保留引用页码；切分采用确定性的字符窗口，默认最大 1200 个字符并重叠 150 个字符。上传后由有界 `TaskExecutor` 编排 `PENDING -> PROCESSING -> SUCCEEDED/FAILED`，原子状态更新防止重复处理。重试和删除均先按文档标识清理旧向量，向量 ID 保持确定性。

默认配置关闭自动处理和向量存储。显式启用 `offline` Profile 时，使用本地确定性 1024 维 Embedding 和 `PgVectorStore` 打通无网络测试闭环；该 Embedding 只用于开发和测试，不能用于评估真实语义检索质量。正式集成使用 DashScope `qwen3.7-text-embedding`。

## 聊天编排边界

MVP 的 `ChatApplicationService` 是确定性 RAG 编排器：读取会话和选定知识库、执行隔离检索、校验证据、组装版本化提示词、调用 `ChatModel`，并保存消息、引用和调用记录。检索不是由模型决定是否执行的工具，因此不增加 SkillRouter、ToolExecutionGateway 或自主工具循环。

检索前由 `SimpleChatReplyPolicy` 对规范化后的完整问题进行精确白名单匹配，仅处理身份介绍、问候、能力说明、感谢和告别，并返回版本内固定文案。这条路径不执行向量检索、不调用模型、不生成引用，但仍保存会话和消息；附加其他指令或未命中白名单的问题必须继续进入 RAG 流程，不能利用简单交互绕过知识库边界。

P2 先通过 `POST /api/chat` 提供非流式闭环，验证持久化、拒答、引用和真实 DeepSeek 调用；P3 的 `POST /api/chat/stream` 复用相同编排与持久化边界，只增加增量传输、取消和流式失败语义。

SSE 使用 Spring AI `ChatModel.stream` 生成真实增量，RAG 正常路径固定为 `message -> delta* -> citation* -> usage -> done`；固定简单回复路径为 `message -> delta -> done(COMPLETED)`，拒答路径为 `message -> delta -> done(REFUSED)`。模型完成并成功落库后才发送引用、usage 和 done；异常路径发送安全 `error` 后结束，客户端提前取消则将仍在处理的助手消息和模型调用标记为失败。Spring MVC 使用独立有界执行器承载异步响应，避免占用文档处理线程池。

会话历史由只读的 `ConversationHistoryService` 查询：会话始终按知识库 ID 过滤，消息按时间稳定排序，引用按消息 ID 批量加载后在应用层关联，避免逐条消息产生 N+1 查询。MyBatis-Plus 逻辑删除规则统一过滤已删除记录。

缺少知识库、问题为空或必要上下文不足时，由请求校验和固定规则返回澄清提示；固定简单交互之外，检索无可靠依据时明确拒答。模型可以改善用户可读文案，但不能改变校验结论、伪造引用或扩大知识库范围。

HTTP request ID 作为 MVP trace ID 的起点，后续贯穿 SSE、消息和 `model_call`。它用于关联与诊断，不承担身份或权限功能。

## 后续演进边界

MVP 稳定后可按以下顺序扩展，但每一阶段都需要独立用例、测试和 ADR：

1. 固定能力路由：应用内显式注册、可版本化，不加载动态插件。
2. 受控工具网关：静态白名单、强类型参数、Schema、权限、次数、超时、错误分类和审计。
3. 项目结构化数据端口：通过领域查询服务只读访问 PostgreSQL，使用参数化 SQL、字段白名单、限行和 `dataAsOf`。
4. Agent/工作流评估：仅在固定编排不足时考虑，并要求步骤预算、循环上限和人工确认。

完整组件映射和风险见 [Agent Skill 与工具调用时序图适配评估](agent-skill-tool-adaptation.md)。

## 基础设施职责

- Flyway 管理业务表和 `vector_store` 表结构。
- Docker 初始化脚本为本地 PostgreSQL 启用 `vector` 扩展。
- 上传文件位于 Git 忽略的 `data/uploads/`，后端通过 `STORAGE_ROOT` 定位。
- 文档处理使用有界 `TaskExecutor`，任务状态持久化到 PostgreSQL。
- 所有模型凭据仅由后端环境变量提供。

## 版本化资产

提示词位于 `backend/src/main/resources/prompts/`，数据库迁移位于 `backend/src/main/resources/db/migration/`，评估数据位于 `evals/`，重要技术决策记录为 ADR。
