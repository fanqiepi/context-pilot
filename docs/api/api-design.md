# REST API、SSE 与操作确认约定

> 本文记录已完成的 V1/V2 接口和已获准实施的 V3 合同。已实现字段以 OpenAPI 为主要事实来源；V3 合同按实施切片进入 OpenAPI，尚未完成的端点仍明确标记为待实现。

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

## V3 资源（按切片实施）

V3 详细合同见 [V3 知识库健康检查与维护助手详细设计](../architecture/v3-knowledge-base-maintenance-design.md)。资源状态如下：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/knowledge-base-health-reports/{id}` | 已实现；查询不可变健康报告、`dataAsOf`、完整性、来源和异常明细 |
| `POST` | `/api/knowledge-base-health-reports/{reportId}/issues/{issueId}/action-request` | 待切片 5/6；从可信异常明细生成或恢复单文档修复提案，不接受客户端动作参数 |

明确健康检查请求通过 `/api/chat` 或 `/api/chat/stream` 发起。顶层能力仍为 `KNOWLEDGE_QA`，健康路径使用能力版本 `v2` 与匹配依据 `EXPLICIT_KNOWLEDGE_BASE_HEALTH`；普通 RAG 和历史消息保持原版本。服务端使用专用只读 DataPort 生成确定性报告，不调用 ChatModel。

SSE 已新增 `health_report` 事件：

```text
message -> route -> delta -> health_report -> done(COMPLETED)
```

`delta` 是与报告一致的确定性摘要；该路径不发送 `citation` 或 `usage`。历史消息响应已增加可空 `healthReport`，按助手消息 ID 批量恢复已保存快照，不重新运行检查；V1/V2 旧消息返回 `null`。

动作确认资源沿用 V2 路径，但 `ActionRequest` 参数将由创建知识库专用对象演进为以 `actionType` 判别的强类型联合，固定允许：

- `CREATE_KNOWLEDGE_BASE`
- `RETRY_DOCUMENT_PROCESSING`
- `REINDEX_DOCUMENT`

两个维护动作只接受服务端保存的单个文档目标。操作 `SUCCEEDED` 表示任务已可靠接受，文档处理最终结果仍通过文档查询接口返回。

截至 V3 切片 3，报告查询端点、同步聊天报告、确定性健康路由、SSE `health_report`、历史 `healthReport` 恢复和前端报告卡片均已可用。前端展示总体状态、检查时间、完整性、当前 profile、文档计数和稳定排序异常；当前只展示建议与可执行性，不提前提供维护动作按钮。

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

上传支持 TXT、Markdown（`.md` 或 `.markdown`）和 PDF，默认最大文件大小为 20 MiB。TXT 和 Markdown 必须使用 UTF-8；PDF 上传阶段校验文件头，是否包含可提取文本留到解析阶段判断。客户端文件名只用于展示，不能决定实际存储路径。上传成功时先返回 `PENDING`；处理开关启用后，后台异步流转为 `PROCESSING`，最终进入 `SUCCEEDED` 或 `FAILED`。

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

`message` 数据包含 `conversationId`、`userMessageId`、`assistantMessageId` 和 `traceId`；`delta` 数据包含本次增量文本；`citation` 与非流式接口使用相同的结构化引用；`usage` 包含模型、Token 和耗时；`done` 包含 `COMPLETED` 或 `REFUSED` 状态及 `traceId`。模型流失败时在已发送的事件之后追加一个安全的 `error` 事件并结束，不再发送引用、usage 或 done。客户端在模型完成前断开时，待处理的助手消息和模型调用记录为失败，并保存脱敏的取消摘要。

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
