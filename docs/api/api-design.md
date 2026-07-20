# REST API 与 SSE 约定

> 当前为 MVP 跨接口约定。接口实现后，字段细节以 OpenAPI 为主要事实来源。

## 通用约定

- API 前缀使用 `/api`，JSON 字段使用 `camelCase`。
- 资源不存在返回 `404`，参数错误返回 `400`，状态冲突返回 `409`。
- 错误响应至少包含 `code`、`message`、`requestId`，不得暴露密钥、堆栈或完整私密文档内容。
- 列表接口采用稳定排序；需要分页时使用 `page` 和 `size`。
- 删除和重试接口必须考虑重复请求，避免重复向量和重复模型费用。

## MVP 资源

- `/api/knowledge-bases`：知识库创建、查询和删除。
- `/api/knowledge-bases/{id}/documents`：文档上传和列表。
- `/api/documents/{id}`：文档状态、错误摘要和删除。
- `/api/conversations`：会话和历史消息。
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
| `DELETE` | `/api/knowledge-bases/{id}` | 删除知识库，成功返回 `204` |

名称去除首尾空白后不能为空，最长 100 个字符，且大小写不敏感唯一；描述最长 1000 个字符。`PATCH` 至少包含一个非 `null` 字段，空字符串描述用于清空描述。重复名称返回 `409 KNOWLEDGE_BASE_NAME_CONFLICT`，资源不存在返回 `404 KNOWLEDGE_BASE_NOT_FOUND`。

当前文档表尚未实施，因此删除只处理知识库记录。文档模块落地后，包含文档的知识库删除将返回 `409 KNOWLEDGE_BASE_NOT_EMPTY`，调用方需要先删除文档。

## SSE 事件

`POST /api/chat/stream` 使用 `text/event-stream`，客户端通过 `@microsoft/fetch-event-source` 建立连接。事件顺序为：

1. `message`：返回会话和助手消息标识。
2. `delta`：增量回答文本，可出现多次。
3. `citation`：结构化引用，可出现多次。
4. `usage`：可获得时返回模型、Token 和耗时摘要。
5. `done`：回答正常完成。
6. `error`：返回安全错误码和可展示消息，随后结束连接。

连接断开不等于模型调用必然取消；服务端必须记录最终状态。任何 SSE 事件都不能携带 API Key、完整提示词或未截断的内部异常。
