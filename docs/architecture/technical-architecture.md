# 研序 技术架构

> 本文维护模块职责和编排机制；逐项功能及版本状态见[功能清单](../development/feature-inventory.md)，运行配置见[本地开发](../development/local-development.md)。

## 架构形式

系统采用 Vue 前端与 Spring Boot 后端分离的模块化单体，后端使用 Java 21、Spring Boot 4.1.x 和 Spring AI 2.0.x。业务编排由显式 Java 服务和持久化状态迁移实现；V6 在独立模块内使用受限原生工具调用，不引入微服务、消息队列、MCP 或工作流框架。

前端使用 Vue 3、TypeScript、Vue Router 和 Element Plus。`/library` 承载知识库、文档上传与处理状态管理，`/chat` 承载知识库选择、会话历史、流式问答和单向“有用”反馈。普通 HTTP 请求统一通过 Axios 访问 `/api`，SSE 使用 `@microsoft/fetch-event-source` 解析具名事件；聊天页对突发到达的 `delta` 使用短缓冲渐进渲染，并在缓冲区清空后再展示引用和完成状态。开发服务器将 `/api` 和 `/actuator` 代理到后端。模型密钥不进入浏览器；助手回答使用 `marked` 解析 Markdown，并在 `v-html` 展示前通过 `DOMPurify` 清洗，禁止脚本、样式、表单、嵌入内容和远程媒体标签。用户消息继续按纯文本展示，模型输出和文档内容中的原始 HTML 均不受信任。

## 后端能力边界

- `knowledgebase`：知识库元数据和生命周期。
- `document`：文件存储、解析、切分、索引任务和状态。
- `retrieval`：基于 `PgVectorStore` 的向量检索。
- `chat`：会话、消息以及 RAG 问答编排。
- `model`：ChatModel 和 EmbeddingModel 的应用级边界。
- `observation`：模型调用记录和最小指标。
- `feedback`：已完成助手回答的“有用”标记、取消和可信上下文关联。
- `capability`（逻辑边界）：固定能力定义、版本和确定性路由；当前实现保持轻量，不加载动态 Skill 或插件。
- `action`：强类型持久化提案、独立确认、原子幂等执行及静态领域服务分派。
- `health`（V3 当前实现边界）：知识库健康只读事实、确定性问题分类、结构化健康结果以及后续不可变报告编排；数据库访问仅通过专用参数化 DataPort。
- `identity`（V5）：通过 `CurrentSubjectProvider` 提供服务端稳定的 `LOCAL_OWNER`；第一阶段不是登录、认证或多用户授权系统。
- `memory`（V5）：全局回答偏好的持久化、版本、管理、固定预算选择和消息上下文关联；不承载事实型记忆、任务状态或自动提取。
- `research`：V4 确定性多文档比对、运行状态与证据账本。
- `upgraderesearch` / `researchreport`：V6 受限工具循环、来源与调用审计、经确认的报告保存。
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

V5：
请求 -> CurrentSubjectProvider -> LOCAL_OWNER
显式“记住回答偏好” -> BUSINESS_ACTION/CREATE_USER_MEMORY
                     -> 保存提案 -> 用户独立确认 -> MemoryService
普通 RAG / V4 最终综合 -> MemoryContextService
                          -> 当前主体活动偏好（最多 10 条/2000 字符）
                          -> 独立 Prompt 数据区 -> 记录 message_memory_context
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

## V4 固定编排研究运行时

V4 的详细设计见 [V4 知识研究助手详细设计](v4-knowledge-research-design.md)。Slice 1 证明范围化多步检索能改善固定合成任务的完整覆盖，但未证明模型 Planner 优于固定编排，因此生产路径采用版本化固定维度生成最多 4 个强类型顺序步骤，确定性校验器冻结范围与预算，有界执行器按文档调用受控检索，证据账本保留真实来源，Synthesizer 最后生成并校验引用。该路径只由显式 `DOCUMENT_COMPARISON`、2 至 5 份文档触发。

该方案不是 ReAct 或自主 Agent 循环。首版不重规划，步骤只允许 `RETRIEVE`，不允许业务副作用、任意 SQL、Shell、文件、HTTP、MCP 或跨知识库访问，也不引入 LangGraph、通用 Tool Gateway、工作流引擎或多 Agent。模型的隐藏思维过程不保存；可审计对象是任务目标、结构化计划、步骤状态、检索范围、证据、预算和安全错误摘要。

生产模块包括 `DeterministicResearchPlanner`、`ResearchPlanValidator`、`ResearchStartService`、`ResearchExecutorService`、`ResearchQueryService`、`ResearchRunCommandService` 和 SSE 适配服务，仍采用模块化单体中的显式 Java 服务。运行状态与回答结果分离，90 秒内进入 `SUCCEEDED/PARTIAL/FAILED/CANCELLED`；取消使用条件更新，断线不取消，应用重启将遗留活动运行安全标记失败。数据库状态是事实来源，SSE 只负责进度；普通 RAG、V2/V3 动作与健康报告链路保持不变。

2026-08-24 批准的 DeepSeek 兼容性决定：研究综合不发送请求级 `max_tokens`，仍记录 usage，并保留单次模型调用、90 秒硬超时和 1800 字符正文上限。`PARTIAL` 原因按稳定 `errorCode` 展示。验证及决定过程见 [V4 验收报告](../evaluation/v4-implementation-acceptance-report.md)。

## V5 隐式主体与显式偏好记忆

V5 详细合同见 [V5 显式记忆的个人助手详细设计](v5-explicit-memory-design.md)，身份决定见 [ADR 0002](decisions/0002-use-implicit-single-user-subject.md)。第一阶段引入 `identity` 和 `memory` 两个明确业务边界，但不增加登录、Spring Security 或多用户授权。

`LocalSingleUserSubjectProvider` 从服务端稳定映射到数据库种子用户 `LOCAL_OWNER`。Controller、客户端 DTO 和模型输出都不能指定用户 ID；所有记忆 Mapper 查询必须包含当前主体 ID。该主体只提供持久归属，所有能访问实例的人仍共享同一身份，因此部署继续限定在本机或可信网络。

首个聊天动作 `CREATE_USER_MEMORY` 只接受确定性显式句式中的 `GLOBAL/RESPONSE_PREFERENCE`，复用 V2 的持久化提案、独立确认、原子幂等和静态分派。普通聊天、历史、文档和模型输出不得自动产生记忆。管理页中的纠正、启用/禁用、过期和删除由用户直接操作专用强类型 API，使用当前主体过滤、版本条件更新和不可变 revision；模型不能调用这些接口。

`MemoryContextService` 只向普通 RAG 和 V4 最终 Synthesizer 提供当前主体下活动、未过期的回答偏好，最多 10 条、合计 2000 字符。记忆位于独立的不可信 Prompt 数据区，只能影响表达方式；当前请求优先于偏好，系统规则、grounding、文档引用、拒答、研究范围和预算优先于二者。简单确定性回复、路由、健康报告和 Action 执行不读取记忆。每次实际注入的 revision 通过 `message_memory_context` 关联助手消息，支持 SSE 和历史解释。

第一阶段不使用 Embedding 或模型选择记忆，不保存事实型记忆或任务状态，不做自动候选、语义去重、冲突合并或跨用户隔离。V5 Slices 1-7 已完成，2026-10-15 通过冻结评估、真实 PostgreSQL 生命周期、真实模型对照、页面验证和完整构建，详见 [V5 验收报告](../evaluation/v5-acceptance-report.md)。后续维护继续保留这些门禁，完成 V5 不扩大 V6 实施范围。

## V6 受控升级研究

[V6 批准设计](v6-upgrade-research-design.md)规定独立 `UPGRADE_IMPACT` 运行，不复用 V4 的计划、预算或终态枚举。应用逐轮调用 Spring AI 原生 DeepSeek 工具协议，只分派选定文档检索、证据读取和固定 Spring AI 仓库发布记录读取三个只读工具。

应用负责资源与强类型参数校验、工具结果回传、重复调用缓存、调用数／上下文／时间预算、来源快照和终态竞争。外部内容始终作为不可信证据。每轮使用 JSON 对象输出模式，不设置请求级 `max_tokens`；最终内容仍须通过结构与引用校验。

保存由用户发起 `SAVE_RESEARCH_REPORT` 提案，模型不能创建或确认提案。独立确认调用报告领域服务，报告写入与动作成功标记同事务提交。数据、接口和保存幂等语义见 V6 设计及 Flyway V20/V21。

首版 API、页面和执行链路已实现，验收未通过，新建运行默认关闭；已知问题及证据见[实现记录](../evaluation/v6-implementation-review.md)。MCP、检索优化及其他未来范围只在[产品需求](../requirements/product-requirements.md)维护。

## 基础设施职责

- Flyway 管理业务表和 `vector_store` 表结构。
- Docker 初始化脚本为本地 PostgreSQL 启用 `vector` 扩展。
- 上传文件位于 Git 忽略的 `data/uploads/`，后端通过 `STORAGE_ROOT` 定位。
- 文档处理使用有界 `TaskExecutor`，任务状态持久化到 PostgreSQL。
- 所有模型凭据仅由后端环境变量提供。

## 版本化资产

提示词位于 `backend/src/main/resources/prompts/`，数据库迁移位于 `backend/src/main/resources/db/migration/`，评估数据位于 `evals/`，重要技术决策记录为 ADR。

## 研究任务工作台模块

新增独立 taskworkspace 模块：控制器 → 带事务的任务状态服务 → 有界执行器 → 计划模型／限定文档检索／综合模型。任务检查点和事件同事务持久化，执行令牌阻止过期结果写回；不改变既有聊天路由、比对或升级研究。详细合同见[研究任务工作台](research-task-workspace-design.md)。
