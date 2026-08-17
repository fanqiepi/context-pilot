# REST API、SSE 与操作确认约定

> 本文记录已完成的 V1/V2/V3 接口和尚未授权实现的 V4 候选合同。已实现字段以 OpenAPI 为主要事实来源；V4 Slice 1 只授权评估资产，候选接口仍不应进入 OpenAPI 或客户端类型。

## 通用约定

- API 前缀使用 `/api`，JSON 字段使用 `camelCase`。
- 资源不存在返回 `404`，参数错误返回 `400`，状态冲突返回 `409`。
- 错误响应至少包含 `code`、`message`、`requestId`，不得暴露密钥、堆栈或完整私密文档内容。
- 列表接口采用稳定排序；需要分页时使用 `page` 和 `size`。
- 删除和重试接口必须考虑重复请求，避免重复向量和重复模型费用。
- 业务删除统一采用逻辑删除，`deleted = 0` 表示未删除、`deleted = 1` 表示已删除；被删除资源对普通查询表现为不存在。

## 已实现资源

- `/api/knowledge-bases`：知识库创建、查询和删除。
- `/api/knowledge-bases/{id}/documents`：文档上传和列表。
- `/api/documents/{id}`：文档状态、错误摘要和删除。
- `/api/documents/{id}/retry`：重试处理失败且未达到次数上限的文档。
- `/api/documents/{id}/reindex`：使用服务器当前 Embedding profile 重建成功文档的派生索引。
- `/api/knowledge-bases/{id}/search`：知识库内 Top-K 文档片段检索。
- `/api/conversations`：会话和历史消息。
- `/api/chat`：POST 非流式 RAG 问答，用于 V1 闭环和集成验证。
- `/api/chat/stream`：POST SSE 流式问答。
- `/api/messages/{id}/feedback`：标记或取消“有用”反馈。
- `/api/model-calls`：最小调用记录查询。

## V2 受控操作资源

- `/api/action-requests/{id}`：查询持久化业务操作提案及状态。
- `/api/action-requests/{id}/confirm`：人工确认后原子取得执行权并执行白名单操作。
- `/api/action-requests/{id}/reject`：拒绝仍在等待确认的操作。

V2 只允许 `CREATE_KNOWLEDGE_BASE`，不提供客户端可枚举或动态注册任意工具的接口。

## V3 已实现资源

V3 详细合同见 [V3 知识库健康检查与维护助手详细设计](../architecture/v3-knowledge-base-maintenance-design.md)：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/knowledge-base-health-reports/{id}` | 查询不可变健康报告、`dataAsOf`、完整性、来源和异常明细 |
| `POST` | `/api/knowledge-base-health-reports/{reportId}/issues/{issueId}/action-request` | 从可信明细生成或恢复单文档重试/重建提案，不接受请求体或客户端动作参数 |

健康检查通过 `/api/chat` 或 `/api/chat/stream` 发起，使用 `KNOWLEDGE_QA/v2` 和确定性只读 DataPort，不调用 ChatModel。SSE 顺序为：

SSE 已新增 `health_report` 事件：

```text
message -> route -> delta -> health_report -> done(COMPLETED)
```

该路径不发送 `citation` 或 `usage`；历史通过可空 `healthReport` 恢复保存快照。维护动作沿用 V2 确认资源，强类型动作固定为 `CREATE_KNOWLEDGE_BASE`、`RETRY_DOCUMENT_PROCESSING` 和 `REINDEX_DOCUMENT`。维护动作只接受服务端保存的单文档目标，生成和确认均复核实时资格；`SUCCEEDED` 表示任务已可靠提交，最终处理结果仍由文档状态表达。

## V4 已确认候选研究合同（未授权实现）

V4 详细方案见 [V4 知识研究助手详细设计](../architecture/v4-knowledge-research-design.md)。SSE 是第一阶段用户入口，同步接口只用于集成测试；两者使用同一强类型 `research` 对象：

```json
{
  "knowledgeBaseId": "uuid",
  "conversationId": "uuid-or-null",
  "question": "比较这些文档在部署方式和限制上的差异",
  "research": {
    "clientRequestId": "client-generated-uuid",
    "taskType": "DOCUMENT_COMPARISON",
    "documentIds": ["uuid-a", "uuid-b"]
  }
}
```

缺少 `research` 时继续执行 V1-V3 现有行为，服务端不得根据 `question` 自动进入研究编排。`clientRequestId` 由前端生成并用于幂等恢复；`taskType` 只允许 `DOCUMENT_COMPARISON`；`documentIds` 必须包含 2 至 5 个不重复、未删除、`SUCCEEDED` 且索引兼容的同知识库文档。任一文档失效时整体返回 `409`，不静默排除。客户端不能提交计划、预算、证据或工具名。

候选辅助资源：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/research-runs/{id}` | 查询执行/回答状态、冻结文档、计划步骤、预算/usage、错误摘要和最终消息关联 |
| `POST` | `/api/research-runs/{id}/cancel` | 原子取消活动运行；已取消重复请求幂等成功，其他终态返回 `409` |

聊天请求原子创建用户消息、待完成助手消息和研究运行，不增加第二个创建入口。相同 `clientRequestId` 与相同规范化请求返回已有运行；相同 ID 携带不同请求返回 `409 RESEARCH_REQUEST_ID_CONFLICT`。访问运行时必须通过会话与知识库可信关系验证范围。第一阶段固定错误语义为：

- `400 RESEARCH_REQUEST_INVALID`：问题、任务类型、文档数量或重复 ID 不合法；
- `404 RESEARCH_DOCUMENT_NOT_FOUND` / `RESEARCH_RUN_NOT_FOUND`：可信范围内资源不存在；
- `409 RESEARCH_DOCUMENT_NOT_ELIGIBLE`：任一文档状态或索引不满足启动资格；
- `409 RESEARCH_RUN_NOT_CANCELLABLE`：运行已进入非取消终态；
- `RESEARCH_PLAN_INVALID`、`RESEARCH_TIMEOUT`、`RESEARCH_DEPENDENCY_UNAVAILABLE` 和 `RESEARCH_RUN_INTERRUPTED`：作为运行失败码持久化，并通过同步响应或 SSE 安全摘要返回。

SSE 顺序固定为：

```text
message
-> route
-> research_plan
-> research_step*
-> delta*
-> citation*
-> usage?
-> done(COMPLETED|PARTIAL|REFUSED|FAILED|CANCELLED)
```

`research_plan` 只包含可读计划摘要和预算，不暴露 Planner 原始输出或隐藏思维；`research_step` 包含稳定步骤 ID、顺序、状态、目标、命中/裁剪数量和安全失败摘要；`delta` 只承载最终综合回答；`citation` 只能来自已保存证据账本。所有事件携带运行内单调 `sequence`。

数据库状态是事实来源。SSE 断开不会取消运行，任务继续执行；取消必须调用独立接口。历史消息只返回可空 `researchRun` 摘要，完整步骤通过运行接口查询；活动运行刷新后由前端轮询恢复，第一阶段不提供 SSE 续传。V1-V3 旧消息返回 `null`。

## 知识库接口

知识库 CRUD 当前提供以下接口：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `POST` | `/api/knowledge-bases` | 创建知识库，成功返回 `201` 和 `Location` 响应头 |
| `GET` | `/api/knowledge-bases` | 按创建时间和 ID 稳定倒序返回知识库列表 |
| `GET` | `/api/knowledge-bases/{id}` | 查询单个知识库 |
| `PATCH` | `/api/knowledge-bases/{id}` | 更新名称或描述 |
| `DELETE` | `/api/knowledge-bases/{id}` | 逻辑删除知识库，成功返回 `204` |

名称去除首尾空白后不能为空，最长 100 个字符，且大小写不敏感唯一；描述最长 1000 个字符。`PATCH` 至少包含一个非 `null` 字段，空字符串描述用于清空描述。重复名称返回 `409 KNOWLEDGE_BASE_NAME_CONFLICT`，资源不存在返回 `404 KNOWLEDGE_BASE_NOT_FOUND`。

知识库存在文档时，删除返回 `409 KNOWLEDGE_BASE_NOT_EMPTY`，调用方需要先删除文档。
逻辑删除后的知识库不再出现在列表或单项查询中，原记录仍保留在数据库中，且名称可以重新使用。

## 文档接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `POST` | `/api/knowledge-bases/{id}/documents` | 使用名为 `file` 的 multipart part 上传文档，成功返回 `201` 和 `Location` 响应头 |
| `GET` | `/api/knowledge-bases/{id}/documents` | 按创建时间和 ID 稳定倒序返回知识库下的文档 |
| `GET` | `/api/documents/{id}` | 查询文档元数据和处理状态 |
| `POST` | `/api/documents/{id}/retry` | 将可重试的失败文档恢复为 `PENDING` 并返回 `202` |
| `POST` | `/api/documents/{id}/reindex` | 将索引来源为 `UNKNOWN` 或 `OUTDATED` 的 `SUCCEEDED` 文档原子恢复为 `PENDING`，使用服务器当前 Embedding profile 异步重建并返回 `202` |
| `DELETE` | `/api/documents/{id}` | 逻辑删除文档元数据，成功返回 `204` |

上传支持 TXT、Markdown（`.md` 或 `.markdown`）和 PDF，默认最大文件大小为 20 MiB。TXT 和 Markdown 必须使用 UTF-8；PDF 上传阶段校验文件头，是否包含可提取文本留到解析阶段判断。客户端文件名只用于展示，不能决定实际存储路径。上传成功时先返回 `PENDING`；处理开关启用后，任务在元数据事务提交后派发，后台异步流转为 `PROCESSING`，最终进入 `SUCCEEDED` 或 `FAILED`。重复派发由 `PENDING -> PROCESSING` 原子抢占消解。

文件过大返回 `413 DOCUMENT_FILE_TOO_LARGE`，类型不支持或文件内容无效返回 `400`。上传先写入文件，再在数据库事务中保存元数据；数据库写入失败时补偿删除文件。普通删除只将文档元数据标记为已删除并保留原始文件，后续如需物理清理必须由单独、可审计且可重试的维护流程完成。已开始处理的文档必须在 VectorStore 可用时删除，以确保派生向量先被清理；否则返回 `DOCUMENT_DELETE_FAILED`，不会留下“业务记录已删除但向量仍可检索”的状态。

TXT、Markdown 和文本型 PDF 分别通过 Spring AI 对应 reader 解析；PDF 按页保留页码元数据，没有可提取文本的文件会解析失败。切分默认每块最多 1200 个字符、相邻块重叠 150 个字符，并优先在段落或句末断开。每块带有稳定的 `chunk_index`，同时保留文件名、文件类型、原始 part 序号和 PDF 页码等来源元数据。状态响应包含 `processingAttempts`，默认最多 3 次处理尝试；超出上限返回 `409 DOCUMENT_RETRY_LIMIT_REACHED`。

文档响应还包含可空的 `embeddingIndex` 和 `embeddingIndexCompatibility`。索引来源记录 profile、供应商、模型、维度、配置版本和完成时间；兼容性取值为 `CURRENT`、`OUTDATED`、`UNKNOWN` 或 `NOT_INDEXED`。迁移前成功文档保持 `UNKNOWN`，必须显式调用重建接口，不进行猜测性回填。重建接口不接受模型参数，只允许 `UNKNOWN` 或 `OUTDATED` 的成功文档；已经是 `CURRENT` 时返回 `409 DOCUMENT_REINDEX_NOT_REQUIRED`，页面也不展示重建入口。处理开关或 VectorStore 未启用时返回 `409`，文档不在 `SUCCEEDED` 状态时返回 `409 DOCUMENT_REINDEX_NOT_ALLOWED`。

## 检索接口

`POST /api/knowledge-bases/{id}/search` 请求体包含非空 `query` 和可选 `topK`。`topK` 默认为 5，范围为 1 到 20。检索强制通过向量元数据中的 `knowledge_base_id` 与当前 `embedding_profile_id` 双重隔离结果，返回 chunk ID、文档 ID、原始文件名、chunk 序号、可用时的 PDF 页码、正文和相似度分数。未启用向量存储时返回安全的服务错误，不会回退为跨知识库或无过滤检索。知识库存在成功文档但没有当前 profile 索引时返回 `409 KNOWLEDGE_BASE_REINDEX_REQUIRED`。

## 会话历史接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/conversations?knowledgeBaseId={id}` | 查询指定知识库下的会话，按更新时间和 ID 稳定倒序返回 |
| `GET` | `/api/conversations/{conversationId}/messages` | 查询会话消息，按创建时间、角色和 ID 稳定正序返回 |

会话列表会先校验知识库存在，并通过 `knowledgeBaseId` 强制隔离结果；知识库不存在时返回 `404 KNOWLEDGE_BASE_NOT_FOUND`。会话消息包含角色、正文、状态、安全错误摘要、trace ID、时间、结构化引用和 `helpful` 布尔值；同一时间戳下用户消息排在助手消息之前，引用和反馈分别批量查询后关联到各条消息，避免逐条查询。逻辑删除的会话、消息、引用和反馈不会出现在查询结果中。会话不存在时返回 `404 CONVERSATION_NOT_FOUND`。

## 回答反馈接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `PUT` | `/api/messages/{messageId}/feedback` | 将已完成的助手回答标记为“有用”，重复请求保持同一状态 |
| `DELETE` | `/api/messages/{messageId}/feedback` | 取消“有用”标记，重复取消仍返回 `204` |

`PUT` 不接收请求体。成功响应包含反馈 ID、消息 ID、后端解析出的知识库 ID、消息 trace ID、`helpful: true` 和时间信息。知识库 ID 与 trace ID 不接受客户端覆盖。

只有状态为 `COMPLETED` 的 `ASSISTANT` 消息允许反馈；用户消息、处理中消息或失败消息返回 `409 MESSAGE_FEEDBACK_NOT_ALLOWED`。消息不存在返回 `404 MESSAGE_NOT_FOUND`。取消采用逻辑删除，再次标记会恢复原反馈记录。

## SSE 事件

`POST /api/chat` 请求体包含必填的 `knowledgeBaseId`、必填的 `question` 和可选的 `conversationId`。未提供会话 ID 时自动创建会话；提供时必须与所选知识库一致。接口先执行知识库隔离检索和相似度校验，无可靠依据时保存固定拒答且不调用模型；有依据时调用 DeepSeek，并返回会话、用户消息、助手消息、回答、结构化引用、模型、usage 和 trace ID。该接口与后续 SSE 接口复用同一个确定性 RAG 编排核心。

`POST /api/chat/stream` 使用 `text/event-stream`，客户端通过 `@microsoft/fetch-event-source` 建立连接。事件顺序为：

1. `message`：返回会话和助手消息标识。
2. `delta`：增量回答文本，可出现多次。
3. `citation`：结构化引用，可出现多次。
4. `usage`：可获得时返回模型、Token 和耗时摘要。
5. `done`：回答正常完成。
6. `error`：返回安全错误码和可展示消息，随后结束连接。

连接断开不等于模型调用必然取消；服务端必须记录最终状态。任何 SSE 事件都不能携带 API Key、完整提示词或未截断的内部异常。

`message` 数据包含 `conversationId`、`userMessageId`、`assistantMessageId` 和 `traceId`；`delta` 数据包含本次增量文本；`citation` 与非流式接口使用相同的结构化引用；`usage` 包含模型、Token 和耗时；`done` 包含 `COMPLETED` 或 `REFUSED` 状态及 `traceId`。V1-V3 普通模型流失败时追加安全 `error` 后结束，客户端提前断开会把待处理消息和模型调用标记失败。V4 持久化研究运行采用前述独立规则：SSE 断开不取消运行，由取消接口和运行状态负责后续收敛。

固定白名单内的身份介绍、问候、能力说明、感谢和告别不调用模型，事件顺序为 `message -> delta -> done(COMPLETED)`，不发送 `citation` 或 `usage`。其他无可靠知识库证据的问题仍使用 `done(REFUSED)`。

## V2 SSE 事件

V2 在保持 `/api/chat/stream` 路径兼容的前提下增加：

- `route`：包含固定 `capabilityId`、`capabilityVersion`、安全的匹配依据和 trace ID。
- `action_required`：直接包含 `actionRequestId`、固定 `actionType`、服务端规范化后的展示参数、影响摘要、状态、过期时间和可信消息关联；不接受客户端动作参数。

三条事件序列为：

```text
SIMPLE_CHAT:
message -> route -> delta -> done(COMPLETED)

KNOWLEDGE_QA:
message -> route -> delta* -> citation* -> usage? -> done(COMPLETED|REFUSED)

BUSINESS_ACTION:
message -> route -> action_required -> done(AWAITING_CONFIRMATION)
```

`action_required` 只表示提案已保存，绝不表示操作已经执行。服务端不得为等待人工确认而长期保持 SSE 连接。

## V2 操作确认接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/action-requests/{id}` | 返回提案类型、展示参数、影响摘要、当前状态、结果摘要和 trace ID |
| `POST` | `/api/action-requests/{id}/confirm` | 仅对等待确认的提案原子取得执行权；重复确认返回已有状态或结果，不重复执行 |
| `POST` | `/api/action-requests/{id}/reject` | 将等待确认的提案标记为拒绝；已执行或已过期操作不能改为拒绝 |

确认和拒绝接口不接受客户端覆盖 `actionType`、参数、会话、消息或 trace ID。执行参数完全来自服务端已校验并持久化的提案。状态冲突返回 `409`，提案不存在或已逻辑删除返回 `404`。

`CREATE_KNOWLEDGE_BASE` 提案只包含规范化后的 `name` 和可选 `description`。确认成功后调用现有知识库领域服务；名称冲突沿用 `KNOWLEDGE_BASE_NAME_CONFLICT` 语义。执行错误保存并返回安全摘要，不暴露堆栈、SQL 或内部类名。
