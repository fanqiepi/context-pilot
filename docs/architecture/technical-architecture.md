# ContextPilot 技术架构

> 状态：功能型 MVP 架构基线 + 已授权的固定能力路由与受控业务操作设计

## 架构形式

系统采用 Vue 前端与 Spring Boot 后端分离的模块化单体。后端使用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x；下一阶段继续使用显式 Java 应用服务和持久化状态迁移，不拆分微服务，不引入消息队列、Agent、LangGraph 或其他工作流引擎。

前端使用 Vue 3、TypeScript、Vue Router 和 Element Plus。`/library` 承载知识库、文档上传与处理状态管理，`/chat` 承载知识库选择、会话历史、流式问答和单向“有用”反馈。普通 HTTP 请求统一通过 Axios 访问 `/api`，SSE 使用 `@microsoft/fetch-event-source` 解析具名事件；聊天页对突发到达的 `delta` 使用短缓冲渐进渲染，并在缓冲区清空后再展示引用和完成状态。开发服务器将 `/api` 和 `/actuator` 代理到后端。模型密钥不进入浏览器，回答和引用按纯文本渲染，不信任模型输出或文档内容中的 HTML。

## 后端能力边界

- `knowledgebase`：知识库元数据和生命周期。
- `document`：文件存储、解析、切分、索引任务和状态。
- `retrieval`：基于 `PgVectorStore` 的向量检索。
- `chat`：会话、消息以及 RAG 问答编排。
- `model`：ChatModel 和 EmbeddingModel 的应用级边界。
- `observation`：模型调用记录和最小指标。
- `feedback`：已完成助手回答的“有用”标记、取消和可信上下文关联。
- `capability`（下一阶段）：固定能力定义、版本和确定性路由，不加载动态 Skill 或插件。
- `action`（下一阶段）：已校验操作提案、人工确认状态和静态白名单业务执行；首个动作只委托 `KnowledgeBaseService` 创建知识库。
- `common`：少量跨模块配置、错误类型和基础约定，不承载业务实现。

模块按业务能力组织，简单功能不强制创建空的 controller/service/mapper 层。`chat` 可以编排检索与模型调用，供应商专用配置不得散落到业务模块。

## 数据流

```text
文档上传 -> StorageService -> 有界 TaskExecutor -> 解析与切分 -> EmbeddingModel
         -> PgVectorStore/PostgreSQL

用户问题 -> 会话/知识库校验 -> DashScope Embedding -> pgvector 隔离检索
         -> 证据校验/拒答 -> DeepSeek ChatModel -> SSE 回答与引用
         -> 会话、调用记录、trace ID 和反馈

下一阶段：
用户问题 -> CapabilityRouter
         -> SIMPLE_CHAT -> 固定回答
         -> KNOWLEDGE_QA -> 复用上述 RAG 链路
         -> BUSINESS_ACTION -> 校验参数 -> 保存待确认提案
                            -> 用户独立确认 -> 原子取得执行权
                            -> 白名单应用动作 -> KnowledgeBaseService
                            -> 保存结果并回显状态
```

文档解析使用 Spring AI 的 `TextReader`、`MarkdownDocumentReader` 和 `PagePdfDocumentReader`。PDF 按页解析以保留引用页码；切分采用确定性的字符窗口，默认最大 1200 个字符并重叠 150 个字符。上传后由有界 `TaskExecutor` 编排 `PENDING -> PROCESSING -> SUCCEEDED/FAILED`，原子状态更新防止重复处理。重试和删除均先按文档标识清理旧向量，向量 ID 保持确定性。

默认配置关闭自动处理和向量存储。显式启用 `offline` Profile 时，使用本地确定性 1024 维 Embedding 和 `PgVectorStore` 打通无网络测试闭环；该 Embedding 只用于开发和测试，不能用于评估真实语义检索质量。正式集成使用 DashScope `qwen3.7-text-embedding`。

## 聊天编排边界

MVP 的 `ChatApplicationService` 是确定性 RAG 编排器：读取会话和选定知识库、执行隔离检索、校验证据、组装版本化提示词、调用 `ChatModel`，并保存消息、引用和调用记录。检索不是由模型决定是否执行的工具，因此不增加 SkillRouter、ToolExecutionGateway 或自主工具循环。

检索前由 `SimpleChatReplyPolicy` 对规范化后的完整问题进行精确白名单匹配，仅处理身份介绍、问候、能力说明、感谢和告别，并返回版本内固定文案。这条路径不执行向量检索、不调用模型、不生成引用，但仍保存会话和消息；附加其他指令或未命中白名单的问题必须继续进入 RAG 流程，不能利用简单交互绕过知识库边界。

P2 先通过 `POST /api/chat` 提供非流式闭环，验证持久化、拒答、引用和真实 DeepSeek 调用；P3 的 `POST /api/chat/stream` 复用相同编排与持久化边界，只增加增量传输、取消和流式失败语义。

SSE 使用 Spring AI `ChatModel.stream` 生成真实增量，RAG 正常路径固定为 `message -> delta* -> citation* -> usage -> done`；固定简单回复路径为 `message -> delta -> done(COMPLETED)`，拒答路径为 `message -> delta -> done(REFUSED)`。模型完成并成功落库后才发送引用、usage 和 done；异常路径发送安全 `error` 后结束，客户端提前取消则将仍在处理的助手消息和模型调用标记为失败。Spring MVC 使用独立有界执行器承载异步响应，避免占用文档处理线程池。

会话历史由只读的 `ConversationHistoryService` 查询：会话始终按知识库 ID 过滤，消息按时间稳定排序，引用和“有用”反馈按消息 ID 批量加载后在应用层关联，避免逐条消息产生 N+1 查询。MyBatis-Plus 逻辑删除规则统一过滤已删除记录。反馈接口只接受消息 ID，知识库和 trace ID 由后端从消息及会话解析，客户端不能指定关联上下文。

缺少知识库、问题为空或必要上下文不足时，由请求校验和固定规则返回澄清提示；固定简单交互之外，检索无可靠依据时明确拒答。模型可以改善用户可读文案，但不能改变校验结论、伪造引用或扩大知识库范围。

HTTP request ID 作为 MVP trace ID 的起点，后续贯穿 SSE、消息和 `model_call`。它用于关联与诊断，不承担身份或权限功能。

## 已授权的下一阶段编排

`ChatApplicationService` 仍是聊天总入口，并在进入现有 RAG 流程前调用轻量 `CapabilityRouter`。路由器只返回应用定义的能力 ID、版本和匹配依据，不返回可执行类名、SQL、URL 或任意工具描述。

第一版路由顺序固定：

1. `SimpleChatReplyPolicy` 命中时选择 `SIMPLE_CHAT`。
2. 明确匹配创建知识库意图时选择 `BUSINESS_ACTION`。
3. 其余请求选择 `KNOWLEDGE_QA`，继续执行知识库隔离检索和证据校验。

路由默认不增加独立模型调用。只有后续评估数据证明确定性规则无法满足真实表达时，才允许设计结构化分类器；分类结果仍只是候选能力，不能作为业务操作确认。

业务操作采用“两次请求”协议。聊天流只创建并返回 `PENDING_CONFIRMATION` 提案；真正执行由独立确认接口触发，因此 SSE 连接不需要为人工决定长期保持。确认服务通过带期望状态的原子更新把提案从 `PENDING_CONFIRMATION` 转为 `EXECUTING`，只有更新成功的请求可以调用动作。重复确认返回已有状态或结果，不能再次产生副作用。首个创建知识库动作与状态更新都在同一个 PostgreSQL 本地事务中完成，避免出现“知识库已经创建但操作仍显示未执行”的提交间隙。

首个 `CREATE_KNOWLEDGE_BASE` 动作使用强类型名称和可选描述，复用 `KnowledgeBaseService` 的规范化、唯一约束和错误语义。模型文本、检索文档和客户端提交的动作类型都不能绕过静态注册和服务端校验。提案参数只保存规范化后的必要字段，结果和错误只保存安全摘要。

前端在聊天消息下展示可恢复的操作卡片。卡片必须在确认前清楚展示操作类型、参数和影响；确认、拒绝、执行中、成功、失败及过期状态均以后端记录为准。

## 后续演进边界

当前已批准固定能力路由和首个受控业务操作。再往后仍按以下顺序扩展，每个新增操作都需要独立用例、测试和安全边界：

1. 增加经过单独批准的固定能力或业务动作，不引入动态注册。
2. 只有多个真实动作形成共同需求时，才提取有限的共享执行边界；不能演变成任意工具平台。
3. 项目结构化数据端口通过领域查询服务只读访问 PostgreSQL，使用参数化 SQL、字段白名单、限行和 `dataAsOf`。
4. Agent/工作流评估仅在固定编排不足时考虑，并要求步骤预算、循环上限、人工确认和评估数据；当前未授权。

完整组件映射和风险见 [Agent Skill 与工具调用时序图适配评估](agent-skill-tool-adaptation.md)。

## 基础设施职责

- Flyway 管理业务表和 `vector_store` 表结构。
- Docker 初始化脚本为本地 PostgreSQL 启用 `vector` 扩展。
- 上传文件位于 Git 忽略的 `data/uploads/`，后端通过 `STORAGE_ROOT` 定位。
- 文档处理使用有界 `TaskExecutor`，任务状态持久化到 PostgreSQL。
- 所有模型凭据仅由后端环境变量提供。

## 版本化资产

提示词位于 `backend/src/main/resources/prompts/`，数据库迁移位于 `backend/src/main/resources/db/migration/`，评估数据位于 `evals/`，重要技术决策记录为 ADR。
