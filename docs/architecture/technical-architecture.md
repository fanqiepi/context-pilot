# ContextPilot 技术架构

> 状态：V1、V2 与 V3 均已完成；V4 受限 Plan-and-Execute 第一阶段合同已确认，Slice 1 评估工作已授权，但尚未授权改变生产架构

## 架构形式

系统采用 Vue 前端与 Spring Boot 后端分离的模块化单体。后端使用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x；V2 继续使用显式 Java 应用服务和持久化状态迁移，不拆分微服务，不引入消息队列、Agent、LangGraph、MCP 或其他工作流运行时。更远版本是否采用这些技术，必须由已经确认的用户场景和评估数据决定。

前端使用 Vue 3、TypeScript、Vue Router 和 Element Plus。`/library` 承载知识库、文档上传与处理状态管理，`/chat` 承载知识库选择、会话历史、流式问答和单向“有用”反馈。普通 HTTP 请求统一通过 Axios 访问 `/api`，SSE 使用 `@microsoft/fetch-event-source` 解析具名事件；聊天页对突发到达的 `delta` 使用短缓冲渐进渲染，并在缓冲区清空后再展示引用和完成状态。开发服务器将 `/api` 和 `/actuator` 代理到后端。模型密钥不进入浏览器，回答和引用按纯文本渲染，不信任模型输出或文档内容中的 HTML。

## 后端能力边界

- `knowledgebase`：知识库元数据和生命周期。
- `document`：文件存储、解析、切分、索引任务和状态。
- `retrieval`：基于 `PgVectorStore` 的向量检索。
- `chat`：会话、消息以及 RAG 问答编排。
- `model`：ChatModel 和 EmbeddingModel 的应用级边界。
- `observation`：模型调用记录和最小指标。
- `feedback`：已完成助手回答的“有用”标记、取消和可信上下文关联。
- `capability`（逻辑边界）：固定能力定义、版本和确定性路由；当前实现保持轻量，不加载动态 Skill 或插件。
- `action`（V2 已实现边界）：已校验操作提案、人工确认状态和静态白名单业务执行；V2 只委托 `KnowledgeBaseService` 创建知识库。
- `health`（V3 当前实现边界）：知识库健康只读事实、确定性问题分类、结构化健康结果以及后续不可变报告编排；数据库访问仅通过专用参数化 DataPort。
- `common`：少量跨模块配置、错误类型和基础约定，不承载业务实现。

模块按业务能力组织，简单功能不强制创建空的 controller/service/mapper 层。`chat` 可以编排检索与模型调用，供应商专用配置不得散落到业务模块。

## 数据流

```text
文档上传 -> StorageService -> 有界 TaskExecutor -> 解析与切分 -> EmbeddingModel
         -> 写入 embedding profile 元数据 -> PgVectorStore/PostgreSQL

用户问题 -> 会话/知识库校验 -> DashScope Embedding -> 知识库 + embedding profile 隔离检索
         -> 证据校验/拒答 -> DeepSeek ChatModel -> SSE 回答与引用
         -> 会话、调用记录、trace ID 和反馈

V2：
用户问题 -> CapabilityRouter
         -> SIMPLE_CHAT -> 固定回答
         -> KNOWLEDGE_QA -> 复用上述 RAG 链路
         -> BUSINESS_ACTION -> 校验参数 -> 保存待确认提案
                            -> 用户独立确认 -> 原子取得执行权
                            -> 白名单应用动作 -> KnowledgeBaseService
                            -> 保存结果并回显状态
```

文档解析使用 Spring AI 的 `TextReader`、`MarkdownDocumentReader` 和 `PagePdfDocumentReader`。PDF 按页解析以保留引用页码；切分采用确定性的字符窗口，默认最大 1200 个字符并重叠 150 个字符。上传后由有界 `TaskExecutor` 编排 `PENDING -> PROCESSING -> SUCCEEDED/FAILED`，原子状态更新防止重复处理。重试、重建索引和删除均按文档标识替换或清理派生向量，向量 ID 保持确定性。

默认配置关闭自动处理和向量存储。显式启用 `offline` Profile 时，使用本地确定性 1024 维 Embedding 和 `PgVectorStore` 打通无网络测试闭环；该 Embedding 只用于开发和测试，不能用于评估真实语义检索质量。正式集成使用 DashScope `qwen3.7-text-embedding`。

Embedding 索引由应用级 `EmbeddingIndexProfile` 标识，默认真实模型使用 `dashscope_qwen3_7_1024_v1`，离线确定性模型使用独立的 `offline_deterministic_1024_v1`。文档成功处理时，`source_document` 记录 profile、供应商、模型、维度、版本和索引时间；每个向量片段记录相同的 profile 来源字段。检索过滤条件固定包含知识库 ID 与当前 profile ID，因此离线、旧模型和当前真实模型的向量不会混入同一结果集。模型、维度或影响向量语义的预处理发生变化时应分配新 profile，并通过显式重建索引迁移文档。

## 聊天编排边界

V1 的 `ChatApplicationService` 是确定性 RAG 编排器：读取会话和选定知识库、执行隔离检索、校验证据、组装版本化提示词、调用 `ChatModel`，并保存消息、引用和调用记录。检索不是由模型决定是否执行的工具，因此不增加 SkillRouter、ToolExecutionGateway 或自主工具循环。

检索前由 `SimpleChatReplyPolicy` 对规范化后的完整问题进行精确白名单匹配，仅处理身份介绍、问候、能力说明、感谢和告别，并返回版本内固定文案。这条路径不执行向量检索、不调用模型、不生成引用，但仍保存会话和消息；附加其他指令或未命中白名单的问题必须继续进入 RAG 流程，不能利用简单交互绕过知识库边界。

V1 先通过 `POST /api/chat` 提供非流式闭环，验证持久化、拒答、引用和真实 DeepSeek 调用；随后实现的 `POST /api/chat/stream` 复用相同编排与持久化边界，只增加增量传输、取消和流式失败语义。

SSE 使用 Spring AI `ChatModel.stream` 生成真实增量，RAG 正常路径固定为 `message -> delta* -> citation* -> usage -> done`；固定简单回复路径为 `message -> delta -> done(COMPLETED)`，拒答路径为 `message -> delta -> done(REFUSED)`。模型完成并成功落库后才发送引用、usage 和 done；异常路径发送安全 `error` 后结束，客户端提前取消则将仍在处理的助手消息和模型调用标记为失败。Spring MVC 使用独立有界执行器承载异步响应，避免占用文档处理线程池。

会话历史由只读的 `ConversationHistoryService` 查询：会话始终按知识库 ID 过滤，消息按时间稳定排序，引用和“有用”反馈按消息 ID 批量加载后在应用层关联，避免逐条消息产生 N+1 查询。MyBatis-Plus 逻辑删除规则统一过滤已删除记录。反馈接口只接受消息 ID，知识库和 trace ID 由后端从消息及会话解析，客户端不能指定关联上下文。

缺少知识库、问题为空或必要上下文不足时，由请求校验和固定规则返回澄清提示；固定简单交互之外，检索无可靠依据时明确拒答。模型可以改善用户可读文案，但不能改变校验结论、伪造引用或扩大知识库范围。

HTTP request ID 作为 V1 trace ID 的起点，后续贯穿 SSE、消息和 `model_call`。它用于关联与诊断，不承担身份或权限功能。

## V2 已完成编排

`ChatApplicationService` 通过确定性 `CapabilityRouter` 在 `SIMPLE_CHAT`、`BUSINESS_ACTION` 和 `KNOWLEDGE_QA` 之间选择；默认不增加模型分类调用。`CREATE_KNOWLEDGE_BASE` 使用强类型持久化提案和独立确认请求，原子取得执行权后才调用 `KnowledgeBaseService`，重复确认不产生第二次副作用。操作状态、失败摘要和前端卡片均以数据库记录为准。完整行为见 [V2 版本验收报告](../evaluation/v2-acceptance-report.md)。

## V3 已完成架构与后续边界

V3 通过专用只读 DataPort 生成不可变健康报告，使用 `dataAsOf`、完整性和来源说明恢复历史结论。可信报告明细可以生成单文档重试或索引重建提案；生成与确认都会复核实时事实，动作使用 sealed 强类型参数和静态分派。文档任务在事务提交后派发，并由有界扫描恢复遗留 `PENDING` 任务。

完整架构、迁移和切片见 [V3 详细设计](v3-knowledge-base-maintenance-design.md)，完成证据见 [V3 版本验收报告](../evaluation/v3-acceptance-report.md)。已完成范围不包含批量动作、自动动作链或通用 Agent/工具平台。

## V4 受限 Plan-and-Execute 设计边界

V4 的详细设计见 [V4 知识研究助手详细设计](v4-knowledge-research-design.md)。第一阶段合同已确认，当前只授权 Slice 1 评估工作：使用显式 `DOCUMENT_COMPARISON`、2 至 5 份合格文档和固定问题集，对照现有单轮 RAG 与评估专用的受限 Plan-and-Execute 候选方案。生产研究路径的目标架构仍是 Planner 产生最多 4 个强类型顺序步骤，确定性校验器冻结范围与预算，有界执行器按文档调用受控检索，证据账本保留真实来源，Synthesizer 最后生成并校验引用，但该生产架构尚未获准实现。

该方案不是 ReAct 或自主 Agent 循环。首版不重规划，步骤只允许 `RETRIEVE`，不允许业务副作用、任意 SQL、Shell、文件、HTTP、MCP 或跨知识库访问，也不引入 LangGraph、通用 Tool Gateway、工作流引擎或多 Agent。模型的隐藏思维过程不保存；可审计对象是任务目标、结构化计划、步骤状态、检索范围、证据、预算和安全错误摘要。

候选模块包括 `ResearchApplicationService`、`ResearchPlanner`、`ResearchPlanValidator`、`ResearchExecutor`、`ResearchEvidenceLedger` 和 `ResearchSynthesizer`，仍采用模块化单体中的显式 Java 服务。运行状态与回答结果分离，90 秒内进入 `SUCCEEDED/PARTIAL/FAILED/CANCELLED`；取消使用条件更新，断线不取消，应用重启将遗留活动运行安全标记失败。数据库状态是事实来源，SSE 只负责进度；普通 RAG、V2/V3 动作与健康报告链路保持不变。

以上合同已完成评审。Slice 1 可以在评估资产和测试范围内建立候选原型并产生基线，不得新增生产模块、迁移、接口或页面。固定评估证明多步检索相对单轮 RAG 有实际质量收益后，才逐片讨论和授权后续生产实现。V5 的显式长期记忆、V6 的外部工具互操作和更远期多 Agent 仍为方向性候选。

## 基础设施职责

- Flyway 管理业务表和 `vector_store` 表结构。
- Docker 初始化脚本为本地 PostgreSQL 启用 `vector` 扩展。
- 上传文件位于 Git 忽略的 `data/uploads/`，后端通过 `STORAGE_ROOT` 定位。
- 文档处理使用有界 `TaskExecutor`，任务状态持久化到 PostgreSQL。
- 所有模型凭据仅由后端环境变量提供。

## 版本化资产

提示词位于 `backend/src/main/resources/prompts/`，数据库迁移位于 `backend/src/main/resources/db/migration/`，评估数据位于 `evals/`，重要技术决策记录为 ADR。
