# 小说阅读 API 合同草案

2026-10-22 续读工作台补充：GET /versions/{versionId}/chapters/{chapterId}/paragraphs 响应增加 chapterOrdinal（1 起），用于恢复到目录分页之外时正确显示当前章和相邻章；未读 title 仍为 null。GET /versions/{versionId}/index 增加 executionMode=OFFLINE_FIXTURE/EVALUATION_ONLY/DISABLED，分别表示离线替身、已批准固定评测、关闭；它是界面说明，不授予调用权限。liveAllowed 及服务端每次请求的批准、范围和预算检查继续有效；EVALUATION_ONLY 不允许页面随机创建自由任务。无新写接口。

2026-10-22 acceptance-v7 的本地 plan/approval 必须绑定 executionScope=DEBUG_RETEST，批准文件另含 previousAuthorizationId/previousApprovalDigest。状态接口新增 executionScope/previousAuthorizationId 并按批次返回调用上限；不提供 HTTP 授权/增额接口。仅接受冻结 DEBUG 请求，INDEX、TEST 或其他请求不在允许清单内，即使开启 debug-reviewed 也不能放行。前置账本不匹配/有未知预留返回 LIVE_PREDECESSOR_MISMATCH，已被其他批次承接返回 LIVE_BUDGET_ALREADY_TRANSFERRED；其余服务端门禁不变。

2026-10-22 拒答修复保持公共 API 不变：人物查证内部模型响应要求可回答性评估；有效 INSUFFICIENT_EVIDENCE+空 facts 返回既有拒答状态，FULL+非空 facts 为 COMPLETED，PARTIAL+非空 facts 为 PARTIAL。缺失/未知评估或空/非空矛盾为 FAILED/OUTPUT_INVALID，不伪装成语义拒答；validatedResult 仍为 facts/partial，不暴露新授权入口。回顾响应合同不变。

2026-10-22 分供应商预算：状态接口新增 providers.DEEPSEEK / providers.DASHSCOPE，各含 authorizationId、limitCny、reservedCny、budgetValuedCny、callsStarted、warningEmitted、stopReason。顶层金额/次数为两账本之和，顶层 authorizationId 仍为批准 ID；调用明细的 authorizationId 对应实际供应商账本。任一停止会使 liveAllowed=false。

2026-10-22 预算保护补充：evaluation/status 新增 warningThresholdPercent、warningEmitted、stopReason，表示本地批次预警和首次停止原因；未启用时仍只返回关闭状态。调用创建合同与授权范围不变，见[预算日志与停止](../evaluation/reading-budget-alerts.md)。

2026-10-22 授权接线补充：新增状态查询 GET /api/reading/evaluation/status，返回 liveAllowed/reason、authorizationId、planSha256、implementationLockSha256、limitCny、expiresAt、maxCalls/callsStarted、reservedCny/budgetValuedCny、debugReviewed、environment 和成本/预留口径；未启用时仅返回关闭原因。没有 HTTP 授权创建或增额入口。任务/索引创建请求形状不变，真实调用只接受批准文件派生的 clientRequestId 和对应版本/问题/范围。详情增加 queuedAt/elapsedMillis；usage.calls 增加 reservedInputTokens、authorizationId、providerModel/providerRequestId，费用口径显式为预算记账。索引 estimate 的 estimatedCost 为整作业上下文容量预留值，仅限批准评测条目。错误新增 LIVE_APPROVAL_INVALID、LIVE_APPROVAL_MISMATCH、LIVE_BATCH_MISMATCH、LIVE_CORPUS_MISMATCH、LIVE_AUTHORIZATION_STOPPED、DEBUG_REVIEW_REQUIRED；无密钥/内容泄露。默认仍拒绝真实调用，见[门禁验证](../evaluation/reading-live-gates-verification.md)。

以下协议轮记录保留为历史状态。

2026-10-22：阅读专用 HTTP 客户端和本地协议测试已实现，没有新增业务 API 或开放供应商代理/授权入口。生产 AI 创建仍返回 `LIVE_AI_NOT_AUTHORIZED`。适配及调用审计详情见[协议验证](../evaluation/reading-provider-verification.md)。

版本：`reading-contract-v1-draft1`，2026-10-20 用户已确认。**以下为批准的目标接口，实际实现以 Controller/OpenAPI 和功能清单为准。** 既有 `/api/chat`、知识库、研究和动作接口不变。

业务语义见[设计](../architecture/novel-reading-design.md)，字段约束见[数据](../data/novel-reading-data-model.md)，数值限制见[新路线](../development/novel-reading-roadmap.md)。

2026-10-20 续读体验准备轮：未新增或变更业务 API。评测工具仅本地读取固定文件、验证和汇总；没有模型执行 HTTP 入口，也不放开真实调用。数据集任务类型沿用 `RECAP/CHARACTER_LOOKUP`、人物模式沿用 `DIRECT/MULTI_QUERY`，参考答案和禁用事实不得作为应用请求内容。见[验证记录](../evaluation/reading-experience-verification.md)。

当前实现范围：基础阅读、范围/预览、`ReadingTaskController` 的索引/任务与证据引用，以及本轮 `ReadingNoteProposalController` 的独立笔记提案与确认。真实供应商适配尚未开放，生产创建 AI 任务返回 `LIVE_AI_NOT_AUTHORIZED`；笔记保存本身不调用模型。正文增加 `atParagraphId` 用于恢复位置及书签/摘录定位，与 cursor 互斥并严格校验版本和章节关系；不会修改已读边界。预览目录当前返回数组，总数从预览 `chapterCount` 取得，默认 size=50；普通目录返回分页对象，默认 size=50。同位置新建重复书签返回 409 `BOOKMARK_EXISTS`；原幂等键重放返回原书签，已删除键返回 410。摘录编辑保留原锚点，并返回 `userEdited`/`originalExcerpt`，页面明确区分编辑内容与原选文。

## 通用协议

- 前缀 `/api/reading`，JSON camelCase；服务端解析 owner、版本所属关系与范围。请求中的 ownerId、预算、执行令牌、SQL、工具名等未知字段拒绝，不能默默接受。
- 创建类请求含 `clientRequestId` UUID；服务端存规范请求 hash。同 key 同请求返回原资源，不派发新工作；不同请求 409 `IDEMPOTENCY_CONFLICT`。已删除资源重放返回 410 `RESOURCE_REMOVED`，不重建。
- 更新命令含 expectedRevision；状态和笔记修改使用 409 `REVISION_CONFLICT`，返回当前 revision，用户/客户端刷新再决定，不按位置最大值合并。
- 列表 page 从 1 起、size 默认 20/最多 50；正文使用绑定 versionId 的 opaque cursor。查询稳定按时间/ID 或正文序号排序。越权、跨书/跨版本与不存在统一 404。
- 错误 `{code,message,requestId,traceId?,currentRevision?,details?}`；details 只提供安全计数与可操作原因，不输出未读标题/正文、密钥或调用请求。
- 阅读助手与笔记响应 `Cache-Control: no-store`；所有分页、详情、SSE、引用和提案 GET 共用范围可见性规则。较后资源只返回 `{id,visibility:"HIDDEN_BY_SCOPE"}` 及安全状态，不返回标题、用户问题、正文或摘录。
- 客户端离开页面不等于取消任务；显式取消才取消。SSE 断线通过 GET 快照恢复，不能重发创建产生费用。

## 导入、版本与正文

| 方法与路径 | 请求 / 行为 | 成功响应 |
| --- | --- | --- |
| `POST /imports` | multipart file、clientRequestId、title、sourceDescription、parserId、headingLevel?、targetBookId?；上传并异步解析，禁止自动索引 | 202 importId/status；同 key 重放 200 |
| `GET /imports/{id}` | 导入状态、previewHash、目录计数、分段警告、首尾样本、重复版本提示 | 200；失败含错误码；预览内容只在用户明确进入预览时取得 |
| `GET /imports/{id}/chapters` | 分页目录预览；非 READY 409 | 200 items/page/size/total |
| `POST /imports/{id}/reparse` | clientRequestId、parserId、headingLevel?；同一已存文件创建新的不可变预览，旧待确认预览作废 | 202 新 importId |
| `POST /imports/{id}/confirm` | previewHash；校验有效期和未变化，发布 book/version，不调用模型 | 200 bookId/versionId 或 duplicateVersionId；并发仅一版 |
| `GET /books` | 最近阅读优先；未读按导入时间排序；不载入正文 | 200 书架列表、每书最后使用版本/位置/状态 |
| `GET /books/{bookId}` | 元数据、版本摘要；不包含未读标题 | 200 |
| `PATCH /books/{bookId}` | expectedRevision、title/sourceDescription | 200；不改正文或版本哈希 |
| `DELETE /books/{bookId}` | expectedRevision | 204 逻辑删除；重复删除 204 |
| `GET /books/{bookId}/versions` | 本书版本列表 | 200；选择新版不搬迁旧位置 |
| `GET /versions/{versionId}/chapters` | 分页目录，未完全读完章 title=null、titleHidden=true | 200 |
| `GET /versions/{versionId}/chapters/{chapterId}/paragraphs` | cursor?、limit?、revealTitle=false；用户主动阅读可请求未读章；不改变任何进度 | 200 paragraphs、nextCursor、versionId、dataAsOf；title 默认按边界隐藏 |

解析错误固定为 `INVALID_UTF8`、`UNSUPPORTED_FORMAT`、`EMPTY_TEXT`、`CHAPTER_RULE_NO_MATCH`、`EMPTY_CHAPTER`、`IMPORT_LIMIT_EXCEEDED`、`PREVIEW_EXPIRED`、`PREVIEW_CHANGED`。413 用于文件字节超限；语义参数错 400、预览状态错 409、过期 410。重选规则生成新预览，不修改既有版本。

原始阅读接口允许主动查看未来正文，这是阅读功能；AI 调用与引用不能使用此接口绕过 `ReadingScopeService`。未读章的原始标题若包含在用户主动打开的章节页面中，只能由明确的“显示本章标题”操作设置 revealTitle=true 请求；不在 AI、书架、目录或回顾中附带输出。引用接口没有 revealTitle 参数。

## 续读、已读范围与书签

| 方法与路径 | 请求 | 响应语义 |
| --- | --- | --- |
| `GET /versions/{id}/state` | 无 | resumePosition/resumeRevision、readBoundary/scopeRevision、visibilityEpoch、dataAsOf |
| `PUT /versions/{id}/resume-position` | clientRequestId、expectedResumeRevision、paragraphId、offset | 200 新 resumeRevision；不返回/修改 AI 边界 |
| `PUT /versions/{id}/read-boundary` | clientRequestId、expectedScopeRevision、lastReadParagraphId（null 表示清空）、confirmContiguousPrefix=true | 200 新 scopeRevision/visibilityEpoch；服务端解析段序，不能提交 ordinal 冒充关系 |
| `GET /versions/{id}/state/events` | SSE，Last-Event-ID? | `scope_changed` / 安全 state 快照，不含任何正文 |
| `GET /versions/{id}/bookmarks` | 分页 | 200；较后 label 隐藏 |
| `POST /versions/{id}/bookmarks` | clientRequestId、paragraphId、offset、label? | 201；定位由服务端验证 |
| `DELETE /bookmarks/{id}` | expectedRevision | 204 逻辑删除 |

两个位置 PUT 均须幂等与 CAS。相同 clientRequestId 的重放在检查 CAS 前匹配原请求，防止“写入成功但响应丢失”被误判为新修改。自动保存冲突停止覆盖，显示“另一个页面已更新”，刷新后由用户选择继续此处或采用已存位置。不能静默自动重试旧 CAS。

状态 SSE 已实现：首次/重连总是发送当前 `snapshot`，后续 `scope_changed`；id 为 `versionId/scopeRevision`，载荷仅 `versionId/readBoundary/scopeRevision/visibilityEpoch/dataAsOf`。`Last-Event-ID` 不控制历史正文重放；服务端只发送当前提交快照，因此回退再前进的中间变化可合并，但 epoch 不丢失。连接返回 `Cache-Control: no-store`、`X-Accel-Buffering: no`；超过路线规定的订阅上限返回 429 `STREAM_LIMIT`，资源不可访问则关闭连接。客户端收到变化或重连后重取受限记录。

### 已实现的原文预览

| 方法与路径 | 行为 |
| --- | --- |
| `GET /versions/{id}/paragraphs/{paragraph}/preview` | 单个完整已读段落；拒绝 query 参数（包括 revealTitle/上下文范围），无续读写入 |
| `GET /notes/{id}/source` | 按笔记完整输入依赖及不可变锚点校验；返回原选区，无续读写入；无出处返回 404 |

两者返回 `versionId/chapterId/chapterOrdinal/chapterTitle/paragraphId/paragraphOrdinal/startOffset/endOffset/text/textHash/requiredBoundary/scopeRevision/visibilityEpoch/dataAsOf`；`textHash` 是原段落 hash，offset 使用 code point。未完全已读章 `chapterTitle=null`；若返回真实章标题，输出 `requiredBoundary` 同时包含章末依赖。越界 409 `SCOPE_HIDDEN`，跨版本/不存在/已删除 404，均 `no-store`。这些基础出处与本轮任务 `evidenceId` 引用分别处理。任务引用另外核对任务归属、完整输入范围和冻结证据。

## 索引与阅读任务

本轮接口由离线替身验证。`GET index` 返回 `profile/readyBoundary/requestedBoundary/ready/liveAllowed/activeJob/dataAsOf`，activeJob 用于刷新后恢复作业控制；estimate 返回下一完整段落前缀的 `fromBoundary/targetBoundary/requestedBoundary/segments/characters/batches/estimateHash/liveAllowed/estimatedCost/reason`，未核实真实计价时 estimatedCost 为 null，不能按零元展示。边界变化或已完成索引变化会改变 estimateHash。

任务列表返回 `items/page/size`。可见任务包含 `currentScopeRevision/visibilityEpoch` 供页面丢弃旧响应；coverage 的 requestedRange 是 `[起始段序,冻结边界]`，另含 evidenceRanges（实际证据段序）、targetBoundary、completedParagraph 和 completeness。budget 提供累计执行时间、次数和完整输入计量；usage.calls 保存每次调用状态、供应商 usage 与预留/结算金额，未知项保持 null。内部 owner、executionId、原始模型响应和完整 checkpoint 不输出。

聚合回顾证据可能包含多个段落，因此引用响应为 `{evidenceId,sources:[ReadingSource...],scopeRevision,visibilityEpoch}`；每个 source 沿用基础预览定位，整体按任务完整输入边界隐藏。任务引用拒绝任何额外 query 参数，模型不能提交替代原文。任务软删除后幂等重放返回 410 `TASK_REMOVED`。

任务 SSE 总是重新构造当前 snapshot（包含当前校验过且可见的结果），再发送 progress/result/done；不按 Last-Event-ID 重放旧正文，也不承诺恢复每条历史进度事件。发送与边界写入使用同一范围锁；已经开始发送的响应无法撤回。新连接恢复当前状态已满足首版状态展示，不把安全事件表作为正文缓存。

| 方法与路径 | 输入 | 成功响应 |
| --- | --- | --- |
| `GET /versions/{id}/index` | 无 | 当前 profile、连续就绪边界、缺失片段数、可用性 |
| `GET /versions/{id}/index-estimate` | expectedScopeRevision | 待处理字数/片段/批次数、预算估算、estimateHash、是否允许真实调用 |
| `POST /versions/{id}/index-jobs` | clientRequestId、expectedScopeRevision、estimateHash | 202；验证预算授权与快照，默认只建已读部分 |
| `GET /index-jobs/{id}` | 无 | 状态、进度、累计调用/费用信息，不输出正文 |
| `POST /index-jobs/{id}/commands` | clientRequestId、expectedRevision、command=`PAUSE/RESUME/CANCEL` | 200；恢复不重置预算、不自动付费重试 |
| `POST /tasks` | 见下方例子 | 202 task；同 key 重放 200 |
| `GET /tasks` | versionId、page/size | 受当前范围约束的历史 |
| `GET /tasks/{id}` | 无 | task 快照、预算、usage、coverage、validatedResult 或隐藏占位 |
| `POST /tasks/{id}/commands` | clientRequestId、expectedRevision、command=`PAUSE/RESUME/CANCEL` | 200；终态不恢复/不重新执行 |
| `GET /tasks/{id}/events` | SSE，Last-Event-ID? | 安全进度与已校验最终结果 |
| `GET /tasks/{id}/citations/{evidenceId}` | 无任意上下文参数 | 200 原文、稳定定位、dataAsOf；超范围 409 `SCOPE_HIDDEN` |
| `DELETE /tasks/{id}` | expectedRevision | 204；仅终态逻辑删除，来源笔记不级联物理删除 |

回顾请求示例：

```json
{
  "clientRequestId": "uuid",
  "versionId": "uuid",
  "expectedScopeRevision": 8,
  "type": "RECAP",
  "chapterCount": 3
}
```

人物请求将 type 改为 `CHARACTER_LOOKUP`，仅额外接受 `question` 与 `mode: DIRECT | MULTI_QUERY`。不接受自由聊天历史、全书范围或 planner budget；回顾不接受任意系统指令。创建时 expectedScopeRevision 必须等于服务端当前值，scope 快照由服务端生成；推进后不会扩大该任务范围。

task 视图固定包含 `id,type,status,revision,scope,coverage,budget,usage,errorCode,traceId,visibility,dataAsOf`。coverage 含 requestedRange/evidenceRanges/completeness（`FULL_WINDOW/SEARCH_MATCHES/PARTIAL/NONE`）；人物命中不能称全已读事实穷尽。usage 未知字段为 null 且 `usageStatus=UNKNOWN`，不能填零。

SSE 事件 `snapshot -> progress* -> result? -> done`；result 仅来自已持久化且当前可见的结构化结果，绝不发送模型原始 token。事件 id 为 taskId/revision，本轮重连从当前受限快照恢复，不使用 Last-Event-ID 重放旧结果。发送前与边界收缩使用同一发布序列，已开始发送的响应不能宣称可撤回。

入队满返回 429 `QUEUE_FULL`，不创建会自动运行的隐性后台重试。数据库已创建但尚未可靠派发时收敛到 PAUSED 并供显式恢复。模型调用未启用/未获授权返回 409 `LIVE_AI_NOT_AUTHORIZED`；索引不足 409 `INDEX_NOT_READY`；范围未设置 409 `READ_BOUNDARY_REQUIRED`。其他终态失败码按架构设计返回，技术失败不混为拒答。

## 笔记与独立确认

| 方法与路径 | 输入/行为 | 成功响应 |
| --- | --- | --- |
| `GET /versions/{id}/notes` | 分页 | 200 受范围约束的笔记列表 |
| `GET /notes/{id}` | 当前范围重验 | 200 详情或隐藏占位 |
| `POST /versions/{id}/notes` | clientRequestId、kind=`MANUAL/EXCERPT`、title/body、anchor?、expectedScopeRevision | 201；摘录必须匹配源选区；不接受 AI kind/sourceTaskId |
| `PATCH /notes/{id}` | expectedRevision、expectedScopeRevision、title/body、retainedEvidenceIds?；省略引用表示保留，传入时只能为当前引用子集 | 200；scope 标签只提高不降低；AI 标记 userEdited=true |
| `DELETE /notes/{id}` | expectedRevision | 204 逻辑删除 |
| `POST /tasks/{id}/note-proposals` | clientRequestId、title/body、retainedEvidenceIds、expectedScopeRevision、supersedesProposalId? | 201 不可变提案、contentHash、expiresAt；只有用户主动保存才创建 |
| `GET /note-proposals/{id}` | 无 | 200 当前可见的最终预览和状态 |
| `GET /tasks/{id}/note-proposal` | 无；刷新后读取该任务最新提案，不创建新提案 | 200 `{proposal: 当前受限预览或 null}` |
| `GET /notes/{id}/citations/{evidenceId}` | 无扩展 query；使用笔记自身冻结来源，任务移除后仍可读 | 200 `{evidenceId,sources,scopeRevision,visibilityEpoch}`；范围外 409 |
| `POST /note-proposals/{id}/confirm` | contentHash（必须与预览一致） | 200 当前状态/resultNoteId；不可夹带新 body 或替换引用 |
| `POST /note-proposals/{id}/reject` | 无 | 200 REJECTED；已成功返回 409 |

确认过期返回 410 `PROPOSAL_EXPIRED` 并持久化 EXPIRED；业务校验失败返回 409 及 FAILED 状态；当前范围不可见不给正文。数据库提交异常返回 503 `SAVE_RETRYABLE`，事务回滚，不假称失败状态已经提交。确认重复请求无需产生新 clientRequestId，提案 ID 就是副作用幂等键。

如果同任务已保存，返回既有 noteId；已删除则返回 `NOTE_ALREADY_SAVED_REMOVED`，不因新提案再插入。用户编辑已保存笔记走 PATCH；普通手写笔记不受这项 AI 来源唯一性限制。

实现映射：创建同 key 重放及同任务已有笔记返回 200 原提案视图（其中 `resultNoteId` 指向既有笔记），首次返回 201。提案视图包含 `id/status/revision/visibility`，可见时另含 `taskId/versionId/kind/title/body/retainedEvidenceIds/requiredBoundary/contentHash/expiresAt/resultNoteId/resultNoteRemoved/userEdited/errorCode/scopeRevision/visibilityEpoch/dataAsOf`；范围外不返回这些内容字段。笔记视图增加 `sourceTaskId/retainedEvidenceIds/requiredBoundary`；隐藏笔记不返回来源及正文。

确认预览 hash 不符持久化 `FAILED/PREVIEW_CHANGED`；范围回退（即使随后再次前进）持久化 `FAILED/SCOPE_CHANGED`；来源任务移除为 `FAILED/SOURCE_REMOVED`。这些确认业务错误返回 409 的安全提案状态与 `code/message`；过期为 410/EXPIRED。过期状态在读取/操作时惰性落库；已保存后重复确认返回既有成功，笔记逻辑删除后则返回 410 `NOTE_ALREADY_SAVED_REMOVED`。失败/过期/拒绝不可复活，用户可创建新提案；待确认提案必须显式带 `supersedesProposalId` 才能原子替代。首次编辑与提案的范围均取任务整个输入范围和当前 B 的较大值。提案引用从任务结果可引用的证据中删减，不能传入原文或新定位。
