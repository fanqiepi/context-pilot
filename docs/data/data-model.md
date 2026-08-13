# ContextPilot 数据模型

> 本文定义已实现逻辑模型和完善阶段的已授权规划；已经实施的物理结构以 Flyway 迁移为事实来源，规划结构在对应迁移落地前不得视为已存在。

## 核心实体

| 实体 | 主要职责 | 关键关系 |
| --- | --- | --- |
| `knowledge_base` | 知识库名称、描述和状态 | 包含多个文档 |
| `source_document` | 原始文件、类型、存储键、处理状态和 Embedding 索引来源 | 属于一个知识库 |
| `vector_store` | 分段正文、元数据和 1024 维向量 | 元数据关联知识库、文档、页码、分段和 Embedding profile |
| `conversation` | 会话标题和时间信息 | 包含多条消息 |
| `chat_message` | 用户问题、助手回答和状态 | 属于一个会话 |
| `message_citation` | 引用的文档、页码、分段和排序 | 属于助手消息 |
| `model_call` | 模型、耗时、Token、状态和脱敏错误 | 关联消息或文档任务 |
| `answer_feedback` | “有用”正向反馈 | 每条助手消息最多一条当前反馈 |

## 完善阶段规划实体（尚未实现）

| 实体 | 主要职责 | 关键关系 |
| --- | --- | --- |
| `action_request` | 保存需人工确认的业务操作提案、状态和安全结果摘要 | 关联会话、用户消息和提案助手消息 |

## 数据约定

- 主键优先使用 UUID，时间统一保存带时区时间戳。
- 业务表统一使用 `deleted` 逻辑删除标记：`0` 表示未删除，`1` 表示已删除；普通 CRUD 不物理删除业务记录或上传文件。`vector_store` 保存可重建的派生索引，不属于业务记录，重建或删除文档时按 `document_id` 物理清理。
- 文档状态至少区分待处理、处理中、成功和失败。
- 重试操作必须避免重复创建向量和重复计费。
- `vector_store.metadata` 只保存检索和引用需要的非敏感标识，不保存完整私密文档副本。
- 模型调用错误只保存可诊断的脱敏摘要，不保存 API Key 或默认记录完整请求。
- 删除知识库时，业务记录、向量和本地文件需要采用明确且可重试的清理顺序。

## 已实施的知识库结构

`V2__create_knowledge_base.sql` 已创建 `knowledge_base` 表：

- `id` 使用应用生成的 UUID。
- `name` 最长 100 个字符，并通过 `lower(name)` 唯一索引保证大小写不敏感唯一。
- `description` 可空，最长 1000 个字符。
- `status` 当前仅使用 `ACTIVE`，为后续可重试删除状态预留扩展位置。
- `created_at` 和 `updated_at` 使用带时区时间戳。

`V4__add_logical_delete.sql` 为知识库增加 `deleted` 字段。MyBatis-Plus 默认只读写 `deleted = 0` 的记录，删除操作将标记更新为 `1`。知识库名称唯一索引只约束未删除记录，因此逻辑删除后可以复用名称。存在未删除文档时，知识库删除由服务层显式拒绝。

## 已实施的文档结构

`V3__create_source_document.sql` 已创建 `source_document` 表：

- 文档通过受限删除外键关联 `knowledge_base`。
- 原始文件名只用于展示；`storage_key` 由服务端生成并保持唯一。
- 文件类型限制为 `TXT`、`MARKDOWN` 和 `PDF`，文件大小必须大于 0。
- SHA-256 使用 64 位小写十六进制字符串保存，为后续完整性校验和重复检测提供依据。
- 状态包括 `PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED` 和 `DELETING`。
- `V5__add_document_processing_attempts.sql` 增加非负的 `processing_attempts`，每次成功取得处理权时递增；默认最多进行 3 次处理尝试。
- 列表查询使用知识库、创建时间和 ID 的组合索引。
- `V4__add_logical_delete.sql` 增加 `deleted` 字段；文档删除仅逻辑删除元数据并保留原始文件，未来物理清理由显式维护流程负责。
- `V9__track_document_embedding_index.sql` 增加 `embedding_profile_id`、`embedding_provider`、`embedding_model`、`embedding_dimensions`、`embedding_profile_version` 和 `indexed_at`。约束要求这些字段要么全部为空，要么全部存在；历史成功文档保持为空，表示来源未知。

处理状态、错误摘要和尝试次数直接保存在 `source_document`，当前不单独创建任务表。处理器通过带状态条件的原子更新取得处理权，避免同一文档并发执行。向量使用由 `documentId + chunkIndex` 派生的确定性 UUID，写入前先删除该文档旧向量，避免重复索引。重建索引仅允许来源未知或不同于当前 profile 的成功文档，通过带状态与 profile 条件的 `SUCCEEDED -> PENDING` 原子迁移取得任务权并重置本轮尝试次数；处理成功后才写入当前 profile，失败时清空来源字段并清理派生向量。

## 已实现的对话结构

`V6__create_chat_tables.sql` 创建 `conversation`、`chat_message`、`message_citation` 和 `model_call`。会话固定关联一个知识库；用户消息直接完成，助手消息按 `PENDING -> COMPLETED/FAILED` 流转。引用保存文档、chunk、页码、顺序、相似度和受限摘录；模型调用只保存供应商、模型、Prompt 版本、Token、耗时、状态、trace ID 和脱敏错误摘要，不保存完整 Prompt 或密钥。四张表均使用逻辑删除字段。

## 已实现的反馈结构

`V7__create_answer_feedback.sql` 创建 `answer_feedback`。一条未删除记录表示对应回答被用户标记为“有用”，不保存“无用”值或原因。`message_id` 通过受限删除外键关联 `chat_message`，并由唯一约束保证每条消息最多对应一条反馈记录；取消反馈时设置 `deleted = 1`，再次标记时原子恢复同一记录，避免重复数据。

只有已完成的助手消息可以接收反馈。知识库 ID 和 trace ID 不由客户端提交，也不在反馈表重复保存；后端通过 `chat_message -> conversation -> knowledge_base` 及消息自身的 `trace_id` 可信关联，避免冗余字段漂移或客户端伪造上下文。

## 下一阶段操作结构规划（尚未实现）

下一次数据库迁移计划创建 `action_request`，至少包含：

- `id`：应用生成 UUID。
- `conversation_id`、`user_message_id`、`assistant_message_id`：通过受限删除外键关联提案产生时的聊天上下文。
- `capability_id`、`capability_version`：当前固定为业务操作能力及其版本。
- `action_type`：静态白名单动作；首个且当前唯一允许值为 `CREATE_KNOWLEDGE_BASE`。
- `parameters`：JSONB，只保存服务端完成强类型解析、规范化和校验后的必要参数。
- `display_summary`：确认卡片使用的安全影响摘要，不包含密钥或原始模型请求。
- `status`：`PENDING_CONFIRMATION`、`EXECUTING`、`SUCCEEDED`、`FAILED`、`REJECTED` 或 `EXPIRED`。
- `result_summary`、`error_summary`：成功结果或脱敏失败摘要；不得保存堆栈、SQL、密钥或完整模型请求。
- `trace_id`、`expires_at`、`confirmed_at`、`executed_at`、`created_at`、`updated_at` 和 `deleted`。

`assistant_message_id` 应具有唯一约束，使一条提案消息最多关联一条业务操作。新产生的聊天消息还计划记录可空的 `capability_id` 和 `capability_version`；历史行保持为空，读取时不得错误推断。

操作执行使用带期望状态的原子更新：只有成功把 `PENDING_CONFIRMATION` 改为 `EXECUTING` 的请求可以调用业务服务。重复或并发确认读取现有状态，不再次执行。首个 `CREATE_KNOWLEDGE_BASE` 操作的状态迁移、知识库写入和成功结果写入使用同一个本地数据库事务；可预期业务失败在事务内转为 `FAILED` 和脱敏摘要。终态不能回退，普通删除继续使用逻辑删除。

客户端不能提交或覆盖持久化后的 `action_type`、`parameters`、消息关系或 trace ID。模型输出只作为候选输入，必须在保存提案前转换为明确 DTO 并通过领域校验。
