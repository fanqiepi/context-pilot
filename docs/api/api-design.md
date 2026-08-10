# REST API 与 SSE 约定

> 当前为 MVP 跨接口约定。接口实现后，字段细节以 OpenAPI 为主要事实来源。

## 通用约定

- API 前缀使用 `/api`，JSON 字段使用 `camelCase`。
- 资源不存在返回 `404`，参数错误返回 `400`，状态冲突返回 `409`。
- 错误响应至少包含 `code`、`message`、`requestId`，不得暴露密钥、堆栈或完整私密文档内容。
- 列表接口采用稳定排序；需要分页时使用 `page` 和 `size`。
- 删除和重试接口必须考虑重复请求，避免重复向量和重复模型费用。
- 业务删除统一采用逻辑删除，`deleted = 0` 表示未删除、`deleted = 1` 表示已删除；被删除资源对普通查询表现为不存在。

## MVP 资源

- `/api/knowledge-bases`：知识库创建、查询和删除。
- `/api/knowledge-bases/{id}/documents`：文档上传和列表。
- `/api/documents/{id}`：文档状态、错误摘要和删除。
- `/api/documents/{id}/retry`：重试处理失败且未达到次数上限的文档。
- `/api/knowledge-bases/{id}/search`：知识库内 Top-K 文档片段检索。
- `/api/conversations`：会话和历史消息。
- `/api/chat`：POST 非流式 RAG 问答，用于 P2 闭环和集成验证。
- `/api/chat/stream`：POST SSE 流式问答。
- `/api/messages/{id}/feedback`：有用/无用反馈。
- `/api/model-calls`：最小调用记录查询。

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
| `DELETE` | `/api/documents/{id}` | 逻辑删除文档元数据，成功返回 `204` |

上传支持 TXT、Markdown（`.md` 或 `.markdown`）和 PDF，默认最大文件大小为 20 MiB。TXT 和 Markdown 必须使用 UTF-8；PDF 上传阶段校验文件头，是否包含可提取文本留到解析阶段判断。客户端文件名只用于展示，不能决定实际存储路径。上传成功时先返回 `PENDING`；处理开关启用后，后台异步流转为 `PROCESSING`，最终进入 `SUCCEEDED` 或 `FAILED`。

文件过大返回 `413 DOCUMENT_FILE_TOO_LARGE`，类型不支持或文件内容无效返回 `400`。上传先写入文件，再在数据库事务中保存元数据；数据库写入失败时补偿删除文件。普通删除只将文档元数据标记为已删除并保留原始文件，后续如需物理清理必须由单独、可审计且可重试的维护流程完成。已开始处理的文档必须在 VectorStore 可用时删除，以确保派生向量先被清理；否则返回 `DOCUMENT_DELETE_FAILED`，不会留下“业务记录已删除但向量仍可检索”的状态。

TXT、Markdown 和文本型 PDF 分别通过 Spring AI 对应 reader 解析；PDF 按页保留页码元数据，没有可提取文本的文件会解析失败。切分默认每块最多 1200 个字符、相邻块重叠 150 个字符，并优先在段落或句末断开。每块带有稳定的 `chunk_index`，同时保留文件名、文件类型、原始 part 序号和 PDF 页码等来源元数据。状态响应包含 `processingAttempts`，默认最多 3 次处理尝试；超出上限返回 `409 DOCUMENT_RETRY_LIMIT_REACHED`。

## 检索接口

`POST /api/knowledge-bases/{id}/search` 请求体包含非空 `query` 和可选 `topK`。`topK` 默认为 5，范围为 1 到 20。检索强制通过向量元数据中的 `knowledge_base_id` 隔离结果，返回 chunk ID、文档 ID、原始文件名、chunk 序号、可用时的 PDF 页码、正文和相似度分数。未启用向量存储时返回安全的服务错误，不会回退为跨知识库或无过滤检索。

## 会话历史接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/conversations?knowledgeBaseId={id}` | 查询指定知识库下的会话，按更新时间和 ID 稳定倒序返回 |
| `GET` | `/api/conversations/{conversationId}/messages` | 查询会话消息，按创建时间、角色和 ID 稳定正序返回 |

会话列表会先校验知识库存在，并通过 `knowledgeBaseId` 强制隔离结果；知识库不存在时返回 `404 KNOWLEDGE_BASE_NOT_FOUND`。会话消息包含角色、正文、状态、安全错误摘要、trace ID、时间和结构化引用；同一时间戳下用户消息排在助手消息之前，引用按 rank 正序返回，并通过一次批量查询关联到各条消息。逻辑删除的会话、消息和引用不会出现在查询结果中。会话不存在时返回 `404 CONVERSATION_NOT_FOUND`。

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
