# 小说阅读数据合同草案

版本：`reading-contract-v1-draft1`，2026-10-20 用户已确认。**以下为批准的数据合同；实际实施以新增 Flyway 脚本及功能清单为准，不能把全部目标表当作已存在。** 批准时源码迁移最高 V28，不改历史脚本。

业务语义见[设计](../architecture/novel-reading-design.md)，容量和门槛见[唯一路线](../development/novel-reading-roadmap.md)，接口见[API](../api/novel-reading-api.md)。

当前实现：新增 [V29](../../backend/src/main/resources/db/migration/V29__create_reading_library.sql)，包含 `reading_book`、`book_import`、`book_version`、`book_chapter`、`book_paragraph`、`reading_state`、`reading_state_command`、`reading_bookmark`、`reading_note` 九张表。笔记仅 MANUAL/EXCERPT；索引、任务和 AI 提案表尚未创建。摘录编辑不修改原锚点，返回的 `originalExcerpt` 与 `userEdited` 由不可变原文比较生成。迁移仅在隔离测试数据库验证，未迁移用户现有本地数据库。

## 通用规则

- UUID 主键；所有业务表含 `created_at/updated_at`（TIMESTAMPTZ）与 `deleted SMALLINT CHECK IN (0,1)`；可变实体另有非负 `revision BIGINT`。不可变正文允许生命周期字段变化，内容不原地覆盖。
- 主体来自 `CurrentSubjectProvider`；顶层资源含 `owner_id REFERENCES app_user(id)`，子资源通过外键链及查询 JOIN 校验归属。JSONB 不能替代关键外键、唯一键和状态 CHECK。
- 普通删除只逻辑删除；父资源删除后所有派生 API 不可读，索引不可命中，活动任务失效。幂等唯一键不因软删除释放；历史重放不复活资源。
- 摘录、模型内容和笔记属于私密数据，不写普通日志；审计存数据库，日志只存 ID、状态、数量、耗时、hash 和错误码。客户端不接收 storage_key 或执行令牌。

## 书籍和导入

| 拟议表 | 核心字段 | 关系与不变量 |
| --- | --- | --- |
| `book_import` | owner_id、client_request_id、request_hash、original_filename、storage_key、file_hash、file_bytes、parser_id/version/options、preview_hash、status、expires_at、target_book_id、result_version_id、error_code | `(owner_id,client_request_id)` 唯一；状态 `PARSING/PREVIEW_READY/CONFIRMED/FAILED/EXPIRED`；预览 hash 绑定文件、解析与目标书籍；确认后不可修改 |
| `reading_book` | owner_id、title、source_description、latest_version_id、revision | title 1–120 字，来源 0–1000；latest_version 必须属于该书，更新指针不能改旧版本 |
| `book_version` | book_id、owner_id、source_import_id、file_hash、text_hash、parser_id/version/options_hash、status、chapter_count、paragraph_count、character_count | 同主体 `(file_hash,parser_id,parser_version,options_hash)` 唯一；来源导入唯一；`PREPARING/READY/FAILED`，只向普通阅读暴露 READY |
| `book_chapter` | version_id、chapter_ordinal、title、first_paragraph_ordinal、last_paragraph_ordinal、text_hash | `(version_id,chapter_ordinal)` 唯一；每章至少一个有效段落；空章节在预览中报错供换规则，不自动丢弃 |
| `book_paragraph` | version_id、chapter_id、paragraph_ordinal、chapter_paragraph_ordinal、text、code_point_count、text_hash | `(version_id,paragraph_ordinal)` 唯一；复合外键确保 chapter 属于 version；段落顺序连续，正文 immutable |

预览解析完成后有固定目录与分页样本，确认时复验 hash，按同一规范生成正文；可复用暂存解析产物但不允许重新猜章。更换规则时旧预览转 `EXPIRED` 并记录 `SUPERSEDED` 原因。书籍与版本发布事务需完整校验数量和哈希，之后原子改 READY；失败版本不可作为阅读/AI 输入。

重复导入命中既有未删除版本返回 duplicateVersionId，用户可打开原版；若原版已逻辑删除，返回 `DUPLICATE_VERSION_REMOVED`，首版不通过重新上传绕过删除或复活数据。新增书籍以导入确认创建，向已有书籍导入新版本显式携带 targetBookId。

## 阅读状态与笔记

| 拟议表 | 核心字段 | 约束 |
| --- | --- | --- |
| `reading_state` | owner_id、book_id、version_id、resume_paragraph_id、resume_offset、resume_revision、last_read_at、boundary_ordinal、scope_revision、visibility_epoch | `(owner_id,version_id)` 唯一；无进度时 resume 为空、B=0；offset 在该段 code point 数以内；分离 CAS，拒绝客户端提供 owner |
| `reading_state_command` | owner_id、version_id、client_request_id、command_type、request_hash、result_revision | `(owner_id,client_request_id)` 唯一；保存自动位置/显式边界的重放结果；旧重放只返回 revision 元数据，不回放超范围内容 |
| `reading_bookmark` | owner_id、book_id、version_id、paragraph_id、offset、label、required_boundary、revision | 相同主体/版本/位置唯一；label ≤120；显式添加不更新位置或边界；超范围只显示通用位置，隐藏 label |
| `reading_note` | owner_id、book_id、version_id、kind、title、body、anchor_start/end、required_boundary、source_task_id、source_proposal_id、citations_json、user_edited、revision | kind=`MANUAL/EXCERPT/AI_RECAP/AI_CHARACTER`；AI source_proposal 与 source_task 分别唯一；手写无 AI 来源；正文≤5000、标题≤120 |
| `reading_note_proposal` | owner_id、book_id、version_id、task_id、client_request_id、request_hash、body/title、citations_json、required_boundary、content_hash、scope_epoch、status、expires_at、supersedes_id、result_note_id、trace_id、error_code | `(owner_id,client_request_id)` 唯一；同任务最多一个 PENDING 提案（部分唯一索引）；状态按设计 CHECK；成功必须有结果关联 |

AI 笔记与提案之间使用复合关联校验 `(note.id, note.source_proposal_id)`，保证 proposal.result_note_id 真由该提案创建；循环外键可用延迟约束完成同事务插入。source_task 与版本/主体的复合关系在迁移和服务双重验证。

提案 content_hash 对规范 JSON 的标题、正文、引用、版本、范围、taskId 取 SHA-256；客户端仅回传看到的 hash，不能决定 hash 的计算字段。AI 引用由服务端复制原任务来源，用户只能删减引用而不能伪造来源；编辑不改变原任务的可信度标签。普通笔记编辑保留 kind/source 链，并设 user_edited。

## 索引

| 拟议表 | 核心字段 | 约束 |
| --- | --- | --- |
| `reading_index_job` | owner_id、version_id、client_request_id、request_hash、target_boundary、scope_epoch、profile_id、generation、status、execution_id、checkpoint、budget_json、usage_json、error_code | `QUEUED/RUNNING/PAUSED/READY/FAILED/CANCELLED/INVALIDATED`；幂等键唯一；范围/预算检查与任务一致，独立线程池 |
| `reading_index_segment` | version_id、paragraph_id、paragraph_ordinal、start_offset、end_offset、text_hash、profile_id、generation、vector_id、status | 同段/profile/generation/偏移/hash 唯一；vectorId 包含 generation；无跨段 chunk；`STAGED/READY`，写向量成功再切 READY；失败派生数据不能查询 |
| `reading_vector_store` | 向量 ID、正文 content、metadata、`vector(1024)` | 独立于旧知识库 vector_store；metadata 必须匹配关系表；只有所属有效段落与 READY 索引可查 |

索引完整性按“当前 profile 下 1..B 全部段落的所有片段就绪”计算，不能只看 maxIndexedParagraph。索引重建先建新 generation，成功后原子切换，查询固定任务所用 generation；模型/profile 更换不会自动升级旧索引。更高范围的局部段落索引可保留，但检索候选先过滤 B，且不可带入更高范围摘要。

不新增持久人物事实表或摘要缓存表。版本正文是事实源，向量是派生索引，不是阅读正文。

## 任务、证据与审计

| 拟议表 | 核心字段 | 约束 |
| --- | --- | --- |
| `reading_task` | owner_id、book_id、version_id、client_request_id、request_hash、type、mode、input_json、scope_json、required_boundary、status、revision、execution_id、checkpoint_json、budget_json、usage_json、active_millis、result_json、trace_id、error_code | `(owner_id,client_request_id)` 唯一；type=`RECAP/CHARACTER_LOOKUP`；结果输入依赖不得超过 scope；结果发布原子校验范围 |
| `reading_task_evidence` | task_id、evidence_key、version_id、chapter_id、paragraph_start/end、offset_start/end、text_hash、excerpt、data_as_of、source_segment_ids | `(task_id,evidence_key)` 唯一；excerpt 与 immutable 原文逐段核验；recap 可聚合连续段，lookup 仅段内片段 |
| `reading_task_event` | task_id、revision、kind、status、safe_payload_json | `(task_id,revision)` 唯一；事件只存安全进度和结果 ID，不复制可越界正文；SSE 重放重新生成受限视图 |
| `reading_job_command` | owner_id、task_id 或 index_job_id、client_request_id、request_hash、command、result_revision | 两个目标外键恰有一个；`(owner_id,client_request_id)` 唯一；仅 PAUSE/RESUME/CANCEL，重放返回当前可见状态与原命令 revision，不重复排队 |
| `reading_ai_call` | owner_id、task_id 或 index_job_id、ordinal、phase、execution_id、status、input_characters/input_bytes、reserved_budget_json、usage_json、elapsed_millis、trace_id、error_code | task/index 恰有一个；各任务/索引作业 ordinal 唯一；`STARTED/SUCCEEDED/FAILED/UNKNOWN`，先预留后发出请求 |
| `reading_spend_ledger` | owner_id、authorization_id、currency、limit_amount、reserved_amount、settled_amount、pricing_version、expires_at、revision | 正值约束、服务端原子扣预留；仅本地配置/执行授权建立，不接受模型或普通业务请求提高额度 |

scope_json 保存服务端 scope 快照与 schemaVersion；checkpoint_json 仅保存有限步骤、已验证证据、调用状态，不保存隐藏思维链。预算字段以路线唯一数字为准，配置只可收紧，放宽需修订合同。

## 并发与删除

统一锁顺序：书籍/版本生命周期 → reading_state → task/index_job → proposal → note/预算账本。可用短事务与条件更新实现等价顺序，禁止持锁等待供应商。发布、提案确认和删除/回退必须共享该锁协议。

CAS 更新失败返回 409 与当前 revision。删除书籍使全部版本不可访问，取消其活动作业；版本和笔记逻辑删除不复用旧键。索引物理清理只针对派生向量的独立维护，不物理删除正文、笔记或上传原文件。

迁移检查覆盖从空库创建、现有 V28 升级、外键/唯一/CHECK、逻辑删除重放、并发确认与故障回滚。具体 DDL 与迁移文件在确认后实施；本草案没有执行任何迁移。
