# V3 知识库健康检查与维护助手详细设计

> 状态：详细设计已批准，2026-08-15 获准实施；当前按第 18 节切片推进。
>
> 适用范围：`GET_KNOWLEDGE_BASE_HEALTH`、`RETRY_DOCUMENT_PROCESSING`、`REINDEX_DOCUMENT`。
>
> 前置基线：V1 知识库问答助手与 V2 可控行动助手均已完成。

## 1. 设计结论摘要

V3 建立一条固定、可恢复、可审计的维护闭环：

```text
用户请求检查知识库
  -> 确定性识别健康检查意图
  -> 专用只读查询生成健康报告与异常明细
  -> 保存 dataAsOf、完整性、来源和建议动作
  -> 前端恢复并展示当时的检查结论
  -> 用户选择一个异常明细
  -> 服务端重新校验并生成单文档操作提案
  -> 用户通过独立请求确认
  -> 服务端再次校验并原子接受后台任务
  -> 文档状态表达后台处理的最终结果
```

本设计采用以下固定决策：

- 保持 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION` 三个顶层能力不变。
- `GET_KNOWLEDGE_BASE_HEALTH` 是 `KNOWLEDGE_QA` 下的确定性只读任务，不调用聊天模型生成健康结论。
- `RETRY_DOCUMENT_PROCESSING` 和 `REINDEX_DOCUMENT` 是 `BUSINESS_ACTION` 下的静态白名单动作，必须复用提案、确认、幂等和审计协议。
- 健康检查保存不可变的 `knowledge_base_health_report` 报告主表和 `knowledge_base_health_issue` 异常明细表；历史消息不重新计算旧报告。
- 修复动作以文档 UUID 为目标，不以文件名定位；重名文档通过报告明细选择消除歧义。
- V3 首版只允许单文档、单动作、单提案，不支持批量修复、动作链或自动连续执行。
- 动作核心不再绑定 `CREATE_KNOWLEDGE_BASE` 参数和执行器，而演进为静态、强类型、显式分派的多动作协议；不建设动态工具注册表或通用执行网关。
- 生成提案和确认执行时都读取实时状态。报告是历史诊断与可信选择来源，不是执行时的最终事实。
- 动作成功只表示后台任务被可靠接受；文档最终成功或失败继续由 `source_document.status` 表达。

## 2. 背景与问题

当前项目已具备以下基础：

- `source_document` 保存 `PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`DELETING` 状态、处理次数和安全错误摘要。
- 文档记录 Embedding profile，应用可以判断 `CURRENT`、`OUTDATED`、`UNKNOWN` 和 `NOT_INDEXED`。
- `DocumentService.retry` 和 `DocumentService.reindex` 已提供单文档重试与重建索引原语。
- V2 已提供持久化 `action_request`、独立确认、拒绝、过期、原子执行权和历史操作卡片。

这些字段和服务能够回答“文档现在是什么状态”，但不能独立回答：

- 整个知识库在某个时间点是否健康；
- 一个 `SUCCEEDED` 文档是否实际存在对应向量；
- 检查结论使用了哪个 Embedding profile；
- 检查是否完整，是否因为向量存储不可用而只得到部分结论；
- 历史聊天中为什么曾建议用户重试或重建某个文档；
- 用户从历史建议发起修复时，目标是否仍满足执行条件。

V3 因此增加“不可变诊断快照”，但不把快照当成实时状态，也不把健康结论回写成 `source_document` 的额外生命周期状态。

## 3. 目标与非目标

### 3.1 目标

- 在当前知识库范围内检查文档处理失败、Embedding profile 未知或过期、实际向量缺失等问题。
- 返回有时间点、来源、完整性和稳定错误语义的结构化健康报告。
- 将报告与会话、用户消息、助手消息、能力版本和 trace ID 可信关联。
- 页面刷新后恢复当时报告，不根据最新数据库状态改写历史结论。
- 用户从具体异常明细生成单文档修复提案，并在确认前清楚看到目标、影响和当前校验结果。
- 在提案生成和确认执行两个时间点重新校验实时状态，阻止陈旧建议产生错误副作用。
- 通过持久化 `PENDING` 状态、事务提交后派发和有界恢复扫描，提高后台任务接受语义的可靠性。

### 3.2 非目标

- 不支持批量重试、批量重建或自动修复全部问题。
- 不支持一个提案包含多个动作，也不支持动作链。
- 不引入 LangGraph、Agent 循环、消息队列、MCP、动态 Skill、插件系统或通用 `ToolExecutionGateway`。
- 不允许模型生成 SQL、决定健康事实、确认操作或直接调用文档服务。
- 不检查文档内容质量、答案质量、语义覆盖率、文件是否过期或外部来源是否更新。
- 不实现跨知识库检查、多用户权限或定时健康巡检。
- 不替换知识库管理页已有的直接文档重试和重建入口；V3 只约束聊天助手发起的维护路径。

## 4. 用户流程

### 4.1 健康检查

1. 用户在已选知识库的聊天中输入明确检查请求，例如“检查这个知识库有没有异常”。
2. `CapabilityRouter` 通过确定性完整句规则识别健康检查，不增加模型分类调用。
3. 服务端校验会话与知识库关系，创建用户消息和待完成助手消息。
4. `KnowledgeBaseHealthService` 在知识库隔离和只读事务内读取文档、当前 Embedding profile 和向量存在性。
5. 服务端生成确定性健康摘要，并持久化报告主表与异常明细表。
6. 助手消息完成，SSE 返回结构化 `health_report`；不调用 ChatModel，不生成引用或 usage。
7. 历史接口批量加载报告与明细，刷新后恢复相同快照。

### 4.2 选择修复建议

1. 健康卡片对可操作明细显示“生成重试提案”或“生成重建提案”。
2. 客户端只提交 `reportId` 和 `issueId`，不能提交或覆盖文档 ID、动作类型、参数或 trace ID。
3. 服务端验证报告、明细、会话和知识库关联，并读取文档实时状态进行第一次校验。
4. 校验通过后，在原会话中保存一条确定性用户选择消息、一条助手提案消息和一个 `PENDING_CONFIRMATION` 操作提案。
5. 同一异常明细重复生成提案时返回现有未删除提案，不重复创建多个待确认操作。

### 4.3 确认和后台处理

1. 用户通过现有独立确认接口确认提案。
2. 服务端原子取得 `PENDING_CONFIRMATION -> EXECUTING` 执行权。
3. 动作执行器再次读取文档实时状态和当前 Embedding profile。
4. 如果目标已经恢复、状态改变、达到重试上限或当前 profile 已匹配，动作进入 `FAILED`，保存安全的陈旧状态摘要，不产生副作用。
5. 校验通过时，文档在同一数据库事务内迁移为 `PENDING`，动作进入 `SUCCEEDED`，结果摘要明确表示“任务已接受”，而不是“文档已修复”。
6. 事务提交后向有界 `TaskExecutor` 派发；恢复扫描会重新派发遗留 `PENDING` 文档。
7. 文档通过 `PENDING -> PROCESSING -> SUCCEEDED/FAILED` 表达最终处理结果。

## 5. 能力路由与编排

### 5.1 顶层能力

顶层能力继续固定为：

| 用户请求 | 顶层能力 | 匹配依据 | 编排 |
| --- | --- | --- | --- |
| 简单问候等白名单 | `SIMPLE_CHAT` | `SIMPLE_INTERACTION_WHITELIST` | 固定回答 |
| 明确健康检查 | `KNOWLEDGE_QA` | `EXPLICIT_KNOWLEDGE_BASE_HEALTH` | 结构化健康查询 |
| 普通知识问题 | `KNOWLEDGE_QA` | `DEFAULT_KNOWLEDGE_QA` | 现有 RAG |
| 创建知识库 | `BUSINESS_ACTION` | `EXPLICIT_CREATE_KNOWLEDGE_BASE` | 创建提案 |
| 选择健康异常修复 | `BUSINESS_ACTION` | `HEALTH_REPORT_ISSUE_SELECTED` | 单文档修复提案 |

健康检查规则位于简单交互之后、默认 RAG 之前。含有额外知识问题、多个动作、自动执行要求或无法唯一识别的复合请求不应直接生成健康报告或动作提案，应安全回落或返回澄清。

V3 启用后，新增行为涉及的 `KNOWLEDGE_QA` 和 `BUSINESS_ACTION` 能力版本应提升；历史 `v1` 消息保持原值，不做推断性回填。

### 5.2 固定能力实现

- `KnowledgeBaseHealthService`：只读事实收集、问题分类、完整性计算和报告持久化编排。
- `KnowledgeBaseHealthDataPort`：专用参数化只读查询，只接受知识库 ID、当前 profile ID 和服务端限制。
- `HealthReportActionProposalService`：从可信异常明细生成强类型动作提案。
- `ActionRequestLifecycleService`：动作查询、过期、确认、拒绝、原子抢占和终态写入。
- 三个显式动作执行器：创建知识库、重试文档、重建索引。

这些类型是业务能力，不是可动态发现或注册的工具。

## 6. 健康报告合同

### 6.1 报告级字段

建议的 `KnowledgeBaseHealthReportResponse`：

```text
id
knowledgeBaseId
conversationId
userMessageId
assistantMessageId
capabilityId = KNOWLEDGE_QA
capabilityVersion
healthStatus
completeness
completenessReason
dataAsOf
currentEmbeddingProfile
documentCounts
issueCount
returnedIssueCount
summary
issues[]
traceId
createdAt
```

`healthStatus` 固定为：

- `EMPTY`：没有活动文档，且检查完整。
- `HEALTHY`：检查完整，没有异常，也没有处理中状态。
- `IN_PROGRESS`：没有已知异常，但存在 `PENDING`、`PROCESSING` 或 `DELETING` 文档。
- `ATTENTION_REQUIRED`：至少存在一个已知异常。
- `UNKNOWN`：检查不完整，且没有足够事实证明为其他状态。

`completeness` 固定为：

- `COMPLETE`：文档事实与向量存在性检查均完成，明细未截断。
- `PARTIAL`：文档事实可用，但向量检查不可用；不得把未检查到的向量误判为存在或缺失。
- `TRUNCATED`：聚合计数完整，但异常明细超过服务端上限，仅返回前 N 条。

如果文档业务数据本身不可读取，整个检查失败并返回安全错误，不生成看似有效的报告。向量查询失败时可以生成 `PARTIAL` 报告，并在 `completenessReason` 中记录固定安全原因。

`documentCounts` 至少包含：

- `total`
- `pending`
- `processing`
- `succeeded`
- `failed`
- `deleting`

### 6.2 异常明细字段

建议的 `KnowledgeBaseHealthIssueResponse`：

```text
id
reportId
documentId
originalFilename
issueType
severity
observedDocumentStatus
observedProcessingAttempts
observedErrorSummary
observedEmbeddingProfileId
observedVectorCount
sourceDocumentUpdatedAt
recommendedActionType
actionEligible
ineligibilityReasonCode
ineligibilitySummary
```

`issueType` 固定为：

- `DOCUMENT_PROCESSING_FAILED`
- `EMBEDDING_PROFILE_UNKNOWN`
- `EMBEDDING_PROFILE_OUTDATED`
- `VECTOR_INDEX_MISSING`

一个文档可以在同一报告中有多个不同问题，但同一 `reportId + documentId + issueType` 只能存在一条活动明细。

错误摘要、不可执行原因和建议文本均由服务端固定规则生成，不保存堆栈、SQL、存储路径、完整文档内容或模型请求。

### 6.3 判断规则

| 实时事实 | 报告结论 | 推荐动作 |
| --- | --- | --- |
| `FAILED` 且尝试次数未达上限、处理已启用 | `DOCUMENT_PROCESSING_FAILED` | `RETRY_DOCUMENT_PROCESSING` |
| `FAILED` 但已达上限或处理关闭 | `DOCUMENT_PROCESSING_FAILED` | 不可操作，并说明原因 |
| `SUCCEEDED` 且 profile 为空 | `EMBEDDING_PROFILE_UNKNOWN` | `REINDEX_DOCUMENT` |
| `SUCCEEDED` 且 profile 不等于当前 profile | `EMBEDDING_PROFILE_OUTDATED` | `REINDEX_DOCUMENT` |
| `SUCCEEDED`、profile 为当前值、实际向量数为 0 | `VECTOR_INDEX_MISSING` | `REINDEX_DOCUMENT` |
| `SUCCEEDED`、profile 为当前值、实际向量数大于 0 | 无异常 | 无 |
| `PENDING`、`PROCESSING`、`DELETING` | 处理中统计 | 不生成修复建议 |

向量检查不可用时不得生成 `VECTOR_INDEX_MISSING`。来源未知或过期已经可由业务表确认，仍可在 `PARTIAL` 报告中返回。

### 6.4 明细限制和排序

- 聚合统计覆盖知识库全部活动文档。
- 明细默认最多返回并持久化 100 条，硬上限不超过 500 条。
- 排序固定为严重程度、问题类型、文档更新时间、文档 ID。
- 超出限制时 `completeness = TRUNCATED`，同时保存 `issueCount` 和 `returnedIssueCount`。
- V3 不提供客户端自定义 SQL、排序字段或任意过滤表达式。

## 7. 数据来源与只读查询边界

### 7.1 权威来源

- 文档生命周期和尝试次数：`source_document`。
- 当前 Embedding 配置：应用级 `EmbeddingIndexProfile`。
- 实际索引存在性：`public.vector_store.metadata` 中的 `knowledge_base_id`、`document_id` 和 `embedding_profile_id`。
- 会话和知识库隔离：`conversation.knowledge_base_id` 与服务端请求上下文。

### 7.2 DataPort 约束

`KnowledgeBaseHealthDataPort` 必须：

- 只接受服务端类型化参数；
- 固定查询字段和排序；
- 强制 `knowledge_base_id` 与 `deleted = 0`；
- 使用参数绑定，不拼接客户端文本；
- 具有查询超时和结果上限；
- 不向模型暴露数据库连接或 SQL；
- 在 PostgreSQL 只读事务内获取一致视图；
- 使用数据库事务时间或应用统一时钟生成一个 `dataAsOf`。

`vector_store.metadata` 当前为 JSON。V3 迁移可以增加表达式索引：

```text
(metadata ->> 'knowledge_base_id',
 metadata ->> 'document_id',
 metadata ->> 'embedding_profile_id')
```

该索引只服务于受控健康查询，不改变 `PgVectorStore` 的向量写入职责。

## 8. 持久化设计

### 8.1 `knowledge_base_health_report`

报告主表建议包含：

| 字段 | 约束与含义 |
| --- | --- |
| `id` | UUID 主键 |
| `knowledge_base_id` | 受限删除外键，报告所属知识库 |
| `conversation_id` | 受限删除外键，必须属于同一知识库 |
| `user_message_id` | 触发检查的用户消息 |
| `assistant_message_id` | 展示报告的助手消息，活动记录唯一 |
| `capability_id` | 固定为 `KNOWLEDGE_QA` |
| `capability_version` | 非空版本 |
| `health_status` | `EMPTY/HEALTHY/IN_PROGRESS/ATTENTION_REQUIRED/UNKNOWN` |
| `completeness` | `COMPLETE/PARTIAL/TRUNCATED` |
| `completeness_reason` | 可空的固定安全说明 |
| `data_as_of` | 本次查询事实时间点 |
| 当前 profile 快照字段 | ID、供应商、模型、维度、版本 |
| 六类文档计数字段 | 非负，合计必须等于总数 |
| `issue_count` | 全部问题数 |
| `returned_issue_count` | 已保存明细数，不大于问题数 |
| `summary` | 确定性可展示摘要 |
| `trace_id` | 与消息一致的 trace ID |
| 时间与 `deleted` | 逻辑删除约定 |

报告创建后不可刷新或覆盖。重新检查必须创建新消息和新报告。普通历史读取不得修改报告状态。

### 8.2 `knowledge_base_health_issue`

异常明细表建议包含：

| 字段 | 约束与含义 |
| --- | --- |
| `id` | UUID 主键 |
| `report_id` | 受限删除外键关联报告 |
| `document_id` | 受限删除外键关联文档 |
| `original_filename` | 检查时文件名快照 |
| `issue_type`、`severity` | 固定枚举 |
| 文档状态、尝试次数、错误摘要快照 | 检查时事实 |
| Embedding profile 和向量计数快照 | 可空，取决于问题类型和完整性 |
| `source_document_updated_at` | 用于说明观察版本 |
| `recommended_action_type` | 可空，只允许两个维护动作 |
| `action_eligible` | 检查时是否允许生成提案 |
| 不可执行原因 | 固定代码和安全摘要 |
| 时间与 `deleted` | 逻辑删除约定 |

增加活动唯一约束 `(report_id, document_id, issue_type)`，并建立报告明细稳定排序索引。

### 8.3 报告和实时状态的职责

- `source_document` 是当前状态和执行校验依据。
- 健康报告是历史诊断、来源和用户选择依据。
- 报告明细不会因为文档后续变化而更新。
- 旧报告可以继续查看，但旧建议不保证仍可执行。
- 生成提案和确认执行均重新读取 `source_document`、处理配置和当前 Embedding profile。

## 9. 动作协议演进

### 9.1 去除创建知识库专用硬编码

切片 4 实施前，`ActionRequestResponse.parameters`、SSE `ActionRequired.parameters`、Mapper 字段提取和执行服务都绑定 `CreateKnowledgeBaseActionParameters`。V3 将动作核心重构为静态强类型协议：

```text
ActionParameters（sealed interface）
  -> CreateKnowledgeBaseParameters
  -> RetryDocumentProcessingParameters
  -> ReindexDocumentParameters
```

每个参数类型由独立 record 完成规范化和校验。`ActionRequestResponse` 以 `actionType` 作为判别字段返回对应参数，前端使用判别联合类型进行穷尽处理。

动作执行采用显式分派：

```text
CREATE_KNOWLEDGE_BASE       -> CreateKnowledgeBaseActionExecutor
RETRY_DOCUMENT_PROCESSING  -> RetryDocumentProcessingActionExecutor
REINDEX_DOCUMENT           -> ReindexDocumentActionExecutor
```

分派必须是编译期可见的枚举 `switch` 或等价静态代码，不使用反射、类名、客户端 URL、动态 Bean 名或运行时插件发现。

### 9.2 `action_request` 演进

V3 Flyway 迁移应：

- 扩展 `action_type` 检查约束，允许三个固定动作；
- 以动作类型为条件校验 JSONB 参数形状；
- 增加可空 `target_document_id`，文档动作必须存在且通过外键关联 `source_document`；
- 增加可空 `health_issue_id`，从健康报告选择生成的动作必须关联可信明细；
- 对非空 `health_issue_id` 建立活动唯一索引，使同一异常明细只生成一个提案；
- 创建知识库动作必须没有文档目标；两个维护动作必须具有文档目标；
- 保留现有 V2 数据、状态和 API 兼容性，不修改已提交迁移 V10。

文档动作参数至少包含：

```text
documentId
originalFilenameSnapshot
observedDocumentStatus
observedEmbeddingProfileId（重建动作可用）
healthReportId（从报告选择时）
healthIssueId（从报告选择时）
```

这些观察字段用于展示和审计，不能代替确认时的实时读取。

### 9.3 提案生成

V3 首选从健康明细生成提案：

```text
POST /api/knowledge-base-health-reports/{reportId}/issues/{issueId}/action-request
```

请求无动作参数。服务端从报告明细解析目标和推荐动作，验证：

- 报告和明细存在且未逻辑删除；
- 报告属于当前会话知识库；
- 明细属于报告，文档属于相同知识库；
- 明细在检查时可操作；
- 实时文档状态仍满足动作条件；
- 处理开关、重试次数和向量存储可用性满足要求。

校验通过后，服务端在报告所属会话创建确定性用户选择消息、助手提案消息和操作记录。重复请求返回现有提案。

直接通过自然语言按文件名选择目标不属于 V3 首版主路径。后续若增加，只能在知识库内精确匹配唯一文档；零候选或多候选必须澄清，不得猜测。

### 9.4 确认时实时复核

确认取得执行权后：

- 重试动作要求文档仍为 `FAILED`、未达到最大次数且处理已启用。
- 重建动作要求文档仍为 `SUCCEEDED`，且来源未知、profile 过期或实际向量仍缺失；处理和向量存储必须可用。
- 如果状态已改变，动作进入 `FAILED`，使用 `ACTION_TARGET_STATE_CHANGED` 等固定代码和安全摘要。
- 失败动作不修改文档状态，不自动创建替代提案。

## 10. 后台任务可靠接受语义

当前文档服务在状态更新后立即调用内存 `TaskExecutor`。V3 动作事务中需要避免后台线程在事务提交前读取不到 `PENDING` 状态。

设计采用：

1. 动作确认事务内原子更新文档为 `PENDING`，并把动作写为 `SUCCEEDED`。
2. 注册事务提交后回调，再向 `DocumentProcessingCoordinator` 派发。
3. 增加有界 `PendingDocumentRecoveryService`，应用启动和低频固定间隔扫描有限数量的活动 `PENDING` 文档并重新派发。
4. 工作线程继续通过 `claimForProcessing` 原子取得 `PENDING -> PROCESSING`，重复派发不会重复处理。
5. 执行器队列拒绝时保留 `PENDING`，记录受限日志并等待恢复扫描，不立即把“已可靠接受”的任务改写为最终失败。

该机制仍使用现有 PostgreSQL 状态和有界 `TaskExecutor`，不增加消息队列或新任务平台。

动作结果摘要必须使用“已提交重试任务”或“已提交重建索引任务”。后台解析、Embedding 或向量写入失败时，动作仍保持 `SUCCEEDED`，文档进入 `FAILED` 并由后续健康检查展示最终问题。

## 11. API、SSE 与历史合同

### 11.1 新增资源

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/knowledge-base-health-reports/{id}` | 查询不可变健康报告及明细 |
| `POST` | `/api/knowledge-base-health-reports/{reportId}/issues/{issueId}/action-request` | 从可信明细生成或返回单文档提案 |

健康报告的创建由 `/api/chat` 和 `/api/chat/stream` 中的明确健康检查请求触发，不额外暴露可绕过会话关联的创建接口。

### 11.2 SSE

新增 `health_report` 事件，包含完整结构化报告。事件序列：

```text
KNOWLEDGE_QA / GET_KNOWLEDGE_BASE_HEALTH:
message -> route -> delta -> health_report -> done(COMPLETED)
```

`delta` 是与报告一致的确定性摘要，不调用模型。该路径不发送 `citation` 或 `usage`。

从异常明细生成提案使用普通 HTTP 请求，返回新用户消息、助手消息和 `ActionRequest`，不为人工确认保持 SSE 连接。

### 11.3 历史恢复

`ConversationMessageResponse` 增加可空 `healthReport`。历史服务：

- 按助手消息 ID 批量读取报告；
- 按报告 ID 批量读取明细；
- 不在历史读取时重新运行健康检查；
- 不因报告中的文档已变化或逻辑删除而改写快照；
- 仍按会话和知识库隔离消息。

## 12. 前端设计

聊天页新增健康报告卡片：

- 顶部展示总体状态、`dataAsOf`、完整性和当前 profile。
- 展示各文档状态计数和异常总数。
- 按稳定顺序展示异常文件、观察状态、问题依据和建议动作。
- `PARTIAL` 和 `TRUNCATED` 必须有明显说明，不能显示成完全健康。
- 只有 `actionEligible = true` 的明细显示生成提案按钮。
- 点击按钮期间按 `issueId` 防重复提交；服务端返回已有提案时恢复同一操作卡片。
- 操作卡片按 `actionType` 使用判别联合类型展示不同参数。
- 修复动作成功后显示“任务已提交”，并复用现有文档状态轮询观察最终结果。

知识库管理页现有直接重试和重建行为保持不变，避免把 V3 设计扩大为全部管理 API 的迁移。

## 13. 错误语义

建议新增固定错误码：

| 错误码 | 场景 |
| --- | --- |
| `KNOWLEDGE_BASE_HEALTH_CHECK_FAILED` | 主业务数据无法检查，未生成报告 |
| `KNOWLEDGE_BASE_HEALTH_PARTIAL` | 仅作为报告完整性原因，不作为 HTTP 失败 |
| `HEALTH_REPORT_NOT_FOUND` | 报告不存在或已逻辑删除 |
| `HEALTH_REPORT_ISSUE_NOT_FOUND` | 明细不存在、不属于报告或已逻辑删除 |
| `HEALTH_REPORT_ISSUE_NOT_ACTIONABLE` | 明细没有允许的修复建议 |
| `HEALTH_REPORT_CONTEXT_MISMATCH` | 报告、会话、知识库或文档关联不可信 |
| `ACTION_TARGET_STATE_CHANGED` | 提案生成后目标状态变化，确认时不再允许执行 |
| `DOCUMENT_RETRY_LIMIT_REACHED` | 实时重试次数已达上限 |
| `DOCUMENT_REINDEX_NOT_REQUIRED` | 确认时目标已使用当前 profile 且向量存在 |

向量检查异常只能产生安全的 `PARTIAL` 原因，不保存数据库错误、SQL 或连接细节。

## 14. 安全与审计

- 健康查询和报告始终限定一个服务端已校验的知识库。
- 逻辑删除文档不参与新报告；历史报告保留其当时快照。
- 报告明细不保存完整文档正文、存储键或向量内容。
- 客户端不能提交推荐动作、目标文档、当前 profile 或动作参数。
- 模型、文档内容和历史消息不能充当用户确认。
- 报告、消息、提案和执行结果贯穿同一 trace ID 或记录明确的派生 trace 关联。
- 操作日志只记录动作 ID、文档 ID、trace ID、状态和安全摘要。
- 报告及明细使用逻辑删除；普通维护流程不得物理删除审计记录。

## 15. 可观测性

至少记录以下计数和耗时：

- 健康检查次数、完整/部分/截断报告数量；
- 按问题类型统计的异常数量；
- 健康检查总耗时、文档查询耗时、向量存在性查询耗时；
- 从异常明细生成提案的成功、陈旧和冲突数量；
- 两类维护动作的确认、拒绝、过期、执行接受和实时复核失败数量；
- `PENDING` 恢复扫描发现、派发、队列拒绝和成功取得处理权数量。

指标不得使用文件名、文档内容或错误全文作为标签。

## 16. 数据迁移与兼容性

V3 预计使用新的顺序 Flyway 迁移，至少覆盖：

- 健康报告主表和异常明细表；
- `action_request` 新动作、目标文档和健康明细关联；
- `chat_message.capability_match_reason` 新枚举值；
- `vector_store.metadata` 健康查询表达式索引。

迁移必须保留 V1/V2 历史数据：

- 历史消息能力版本和匹配依据不回填；
- V2 创建知识库提案仍能读取、确认、拒绝和恢复；
- API 中 `CREATE_KNOWLEDGE_BASE` 参数 JSON 保持兼容；
- 新增响应字段使用可空值，旧历史消息返回 `healthReport = null`；
- 不修改已经提交的 V1-V10 脚本。

## 17. 测试与固定评估

### 17.1 单元与集成测试

- 健康状态、完整性、排序和异常分类规则。
- 空知识库、全部健康、处理中、混合状态。
- 失败文档可重试、达到上限、处理关闭。
- profile `CURRENT`、`UNKNOWN`、`OUTDATED`。
- 当前 profile 但向量为零、向量查询不可用。
- 知识库隔离、逻辑删除过滤、报告与消息可信关联。
- 报告历史恢复不重新计算。
- 重名文件通过 issue ID 精确选择。
- 报告生成后文档状态变化时，提案生成或确认安全失败。
- 同一明细重复生成提案只得到一个活动提案。
- 两类动作未确认、拒绝、过期、重复确认和并发确认均不重复提交任务。
- 事务提交后派发、队列拒绝保留 `PENDING`、恢复扫描和原子处理抢占。
- V2 创建知识库动作和全部 V1 RAG 行为保持回归通过。

### 17.2 V3 固定评估集

新增公开、确定性的 V3 数据集和配置，至少度量：

- 健康检查意图准确率和误触发率；
- 健康问题分类准确率；
- 知识库隔离正确率；
- 完整性说明正确率；
- 修复建议与实际资格一致率；
- 陈旧报告阻止错误操作的正确率；
- 未确认操作零副作用；
- 重复及并发确认单次任务接受率；
- 历史报告、提案和 trace 关联完整率。

V3 评估 Profile 必须使用真实 PostgreSQL/pgvector 容器验证报告、向量存在性和动作状态机；Docker 不可用时不得通过跳过生成基线结论。

## 18. 实施切片

本设计及相关治理文档已于 2026-08-15 明确评审并授权实现，按以下顺序开发：

1. **健康只读核心**：DataPort、健康规则、结构化 DTO 和真实 PostgreSQL 测试。
2. **报告持久化**：报告主表、异常明细表、消息关联和历史恢复。
3. **聊天与前端卡片**：确定性路由、SSE `health_report` 和刷新恢复。
4. **动作核心重构**：强类型参数联合、静态执行分派、V2 创建动作兼容。
5. **失败文档重试提案**：报告选择、实时双重校验和单文档确认。
6. **索引重建提案**：profile、向量缺失和 VectorStore 可用性校验。
7. **可靠派发**：提交后派发、`PENDING` 恢复扫描和队列拒绝语义。
8. **评估与验收**：V1/V2 回归、V3 固定评估、真实页面检查和版本报告。

每个切片都必须保持可构建、可测试，不提前加入下一切片的通用抽象。

### 18.1 当前实施进度

- 2026-08-15 完成切片 1“健康只读核心”：新增 `health` 业务边界、参数化 PostgreSQL DataPort、固定健康状态与四类问题规则、结构化评估结果、明细上限配置，以及规则单元测试和真实 PostgreSQL/pgvector 隔离测试。
- 切片 1 只返回应用内部健康评估，不创建报告表、不写聊天消息、不新增 HTTP/SSE 合同，也不改变现有文档重试或重建入口。
- 2026-08-15 完成切片 2“报告持久化”：新增 V11 报告主表、异常明细表与向量元数据表达式索引；报告服务校验知识库、会话、用户消息、待完成助手消息及 trace 的可信关联，在同一事务保存不可变快照并完成助手消息；历史服务按消息和报告批量恢复快照，不重新执行健康检查。
- 切片 2 已通过真实 PostgreSQL/pgvector 迁移及持久化测试，覆盖来源快照不可变、稳定排序、重复创建冲突、跨知识库拒绝和历史回显；公开报告端点、健康聊天路由和 SSE 事件已在切片 3 补齐。
- 2026-08-15 完成切片 3“聊天与前端卡片”：新增完整句健康意图白名单、`KNOWLEDGE_QA/v2` 与 `EXPLICIT_KNOWLEDGE_BASE_HEALTH`，V12 扩展消息路由约束；同步聊天、报告 GET、固定顺序 SSE `health_report` 和历史刷新恢复读取同一不可变报告，且不调用 ChatModel。
- 前端卡片展示总体状态、`dataAsOf`、完整性、当前 profile、文档计数、异常依据、建议动作和不可执行原因；切片 5 已开放失败文档的重试提案按钮，切片 6 已开放来源未知、profile 过期和实际向量缺失明细的索引重建提案按钮。后端聚焦测试、真实 PostgreSQL HTTP/SSE 链路、前端类型检查和生产构建已通过。
- 2026-08-15 完成切片 4“动作核心重构”：新增 sealed `ActionParameters` 及三个固定参数 record，Mapper 以 `action_type` 静态编解码 JSONB，确认服务通过枚举 `switch` 显式分派；V13 增加两个维护动作、文档目标、健康明细外键、按动作类型的参数形状约束和活动明细唯一索引。前端 `ActionRequest` 同步演进为判别联合。
- 切片 4 完成时尚未开放维护提案入口；V12 既有创建知识库提案迁移到 V13 后 JSON、状态及空目标字段保持兼容。
- 2026-08-15 完成切片 5“失败文档重试提案”：新增无请求体的报告明细提案端点和 `HealthReportActionProposalService`，以明细行锁与 V13 唯一索引保证并发请求只创建一组确定性消息和一个提案；消息使用 `BUSINESS_ACTION/v2` 与 V14 `HEALTH_REPORT_ISSUE_SELECTED`。客户端仅提交报告与明细 ID，服务端校验报告、会话、知识库、文档及动作资格，并从不可变快照构造强类型参数。
- `RetryDocumentProcessingActionExecutor` 在确认取得执行权后复用单文档重试原语，再次校验 `FAILED`、处理开关和次数上限；陈旧、删除、关闭或超限目标进入动作 `FAILED` 且不修改文档，重复及并发确认最多提交一次。
- 2026-08-15 完成切片 6“索引重建提案”：同一无请求体端点按可信明细静态选择 `REINDEX_DOCUMENT`，提案参数保存报告观察到的 profile；`ReindexDocumentActionExecutor` 复用单文档重建原语，生成和确认均校验 `SUCCEEDED`、当前 profile、实际向量及处理与 VectorStore 可用性。来源未知、profile 过期和当前 profile 向量为零均可重建；向量在报告后恢复、状态变化或依赖不可用时安全拒绝且不修改文档。前端开放重建按钮，重复请求恢复同一操作卡片。
- 2026-08-15 完成切片 7“可靠派发”：上传、重试和索引重建统一注册事务提交后派发；执行器队列拒绝只记录受限日志并保留 `PENDING`。新增启动与低频固定间隔的有界恢复扫描，按稳定顺序重新派发活动 `PENDING` 文档，并继续依靠 `claimForProcessing` 原子抢占消解重复派发。该切片不新增数据库迁移。
- 2026-08-15 完成切片 8“评估与验收”：建立固定数据集、离线 Maven Profile 与脱敏基线报告，完成 V1/V2 回归、V3 专项评估、全量回归、前端构建、真实接口验收和页面目检。八个切片及第 19 节完成标准全部满足，V3 正式完成。

## 19. V3 完成标准

- 健康报告字段、来源、`dataAsOf`、完整性和限制与本设计一致。
- 报告及明细可在会话历史中稳定恢复，旧结论不会被实时重算覆盖。
- 处理失败、profile 未知、profile 过期和实际向量缺失均有确定性测试。
- 用户通过报告明细精确选择目标，重名文档不会产生歧义。
- 提案生成和确认执行均完成实时复核，陈旧报告不产生错误副作用。
- 两个维护动作复用统一的动作生命周期，但仍采用强类型参数和静态执行器。
- V2 动作核心不再硬编码创建知识库参数，同时 V2 API 和历史数据保持兼容。
- 后台任务具有事务提交后派发和 `PENDING` 恢复能力，重复派发不会重复处理。
- 原有 RAG、引用、拒答、SSE、历史、反馈、Embedding 隔离和 V2 创建知识库动作回归通过。
- OpenAPI、Flyway、数据模型、前端类型、评估资产和页面验收同步完成。

## 20. 授权边界

2026-08-15 的实施授权明确确认以下默认决策；后续实现不得静默扩大这些边界：

- 首版只支持单文档动作，不支持批量修复；
- 健康报告使用主表加明细表并保持不可变；
- 实际向量存在性属于 V3 健康范围；
- 报告明细按钮是首版修复提案的主要入口；
- 动作核心采用 sealed 强类型参数和静态分派，不建设通用工具网关；
- `PENDING` 文档使用提交后派发和有界恢复扫描保证任务接受语义。

本次授权已同步更新 `AGENTS.md`、产品需求、技术架构、数据模型、API 设计和评估计划中的版本状态。V4 及任何超出上述默认决策的扩展仍需单独批准。
