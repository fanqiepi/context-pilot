# ContextPilot 数据模型

> 本文定义 V1-V3 已完成模型和 V4 第一阶段已授权、已由 Flyway V15 落地的研究模型；已经实施的物理结构仍以 Flyway 迁移为事实来源。

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
| `action_request` | 保存需人工确认的业务操作提案、状态和安全结果摘要 | 关联会话、用户消息和提案助手消息 |
| `knowledge_base_health_report`（V3 / V11） | 保存不可变的知识库健康检查快照 | 关联知识库、会话和检查消息 |
| `knowledge_base_health_issue`（V3 / V11） | 保存报告中的文档异常、观察事实和修复建议 | 关联健康报告和目标文档 |

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

## V2 已实现操作结构

`V10__create_action_request.sql` 创建 `action_request`，关联会话、用户消息和唯一提案助手消息，保存静态动作类型、强类型校验后的 JSONB 参数、展示/结果/错误摘要、trace、过期与执行时间及逻辑删除标记。状态固定为 `PENDING_CONFIRMATION`、`EXECUTING`、`SUCCEEDED`、`FAILED`、`REJECTED` 或 `EXPIRED`。

只有原子取得 `PENDING_CONFIRMATION -> EXECUTING` 的请求可以执行动作；重复或并发确认只读取已有状态。客户端和模型都不能覆盖已保存的动作类型、参数、消息关系或 trace ID。完整物理字段和约束以 V10 及后续迁移为准。

## V3 已实现数据结构

V3 详细字段与约束见 [V3 知识库健康检查与维护助手详细设计](../architecture/v3-knowledge-base-maintenance-design.md)。V11 新增：

- `knowledge_base_health_report`：保存知识库、会话、触发消息、能力版本、`health_status`、完整性、`data_as_of`、当前 Embedding profile 快照、各文档状态计数、问题计数、确定性摘要和 trace ID。一条活动助手消息最多关联一份报告；报告创建后不刷新或覆盖。
- `knowledge_base_health_issue`：保存报告时观察到的文档 ID、文件名快照、问题类型、严重程度、文档状态、处理次数、安全错误摘要、Embedding profile、向量计数、来源更新时间、建议动作和不可执行原因。同一报告、文档和问题类型最多一条活动明细。

报告与明细是不可变审计快照，使用逻辑删除；`source_document` 仍是当前事实来源，历史读取不重新计算，维护提案生成和确认都会复核实时状态。V11 同时增加健康向量元数据索引；V12/V14 扩展消息匹配依据；V13 将 `action_request` 扩展为固定允许 `CREATE_KNOWLEDGE_BASE`、`RETRY_DOCUMENT_PROCESSING` 和 `REINDEX_DOCUMENT`，并用目标文档、健康明细和按动作类型的 JSONB 约束保持强关联。V11-V14 保持 V2 历史提案兼容，完整迁移行为以 Flyway 脚本和 V3 设计为准。

## V4 已实施研究数据结构

V4 详细设计见 [V4 知识研究助手详细设计](../architecture/v4-knowledge-research-design.md)。第一阶段合同确认四张审计型业务表：

| 实体 | 主要职责 | 关键关系 |
| --- | --- | --- |
| `research_run` | 保存幂等请求、固定任务类型、计划版本、2-5 个冻结文档、执行/回答状态、预算、trace 和重试来源 | 关联知识库、会话、用户消息和助手消息 |
| `research_step` | 保存服务端步骤 ID/顺序、目标、查询、受限文档数组、状态和执行摘要 | 属于一个研究运行 |
| `research_evidence` | 保存 vector ID 快照、真实文档/chunk、页码、相似度、摘录和 Embedding profile | 属于一个研究运行，不外键关联可重建向量 |
| `research_step_evidence` | 保存步骤与证据的多对多关系、步骤内排名和相似度 | 关联研究步骤与研究证据 |

四张表使用 UUID、带时区时间和逻辑删除。`research_run` 不重复保存原始问题，也不存在模型 Planner 原始 JSON；冻结文档与步骤文档范围使用受限 JSONB 数组，计划从规范化步骤恢复。第一阶段 `task_type` 只允许 `DOCUMENT_COMPARISON`，执行状态为 `PLANNING/EXECUTING/SYNTHESIZING/SUCCEEDED/PARTIAL/FAILED/CANCELLED`，回答状态为 `ANSWERED/REFUSED` 或空；状态只通过条件更新单向迁移。

Flyway V15 为 `message_citation` 增加可空 `research_evidence_id`，V4 引用必须关联当前运行的真实证据，V1-V3 历史行保持空且不回填。`chat_message` 增加 `CANCELLED`，只用于取消的研究助手消息；部分完成与拒答仍保存为已完成消息。应用启动把遗留活动运行标记为 `FAILED/RESEARCH_RUN_INTERRUPTED`，不自动恢复；重新执行创建新运行并通过 `retry_of_run_id` 关联旧记录。

Flyway `V15__create_document_comparison_research.sql` 已创建四表、唯一索引、外键、状态与预算检查约束，并以不回填方式兼容 V1-V3 历史数据。
