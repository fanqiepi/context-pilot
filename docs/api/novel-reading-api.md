# 小说阅读 API 合同草案

版本：`reading-contract-v1-draft1`，2026-10-20 用户已确认。**以下为批准的目标接口，实际实现以 Controller/OpenAPI 和功能清单为准。** 既有 `/api/chat`、知识库、研究和动作接口不变。

业务语义见[设计](../architecture/novel-reading-design.md)，字段约束见[数据](../data/novel-reading-data-model.md)，数值限制见[新路线](../development/novel-reading-roadmap.md)。

当前实现范围：导入/书籍/版本/正文、state 与两个位置 PUT、书签、手写/摘录笔记。`state/events`、索引、任务、引用、AI 笔记提案均未实现。下表未实现部分是批准的后续合同，不是现有接口。正文增加 `atParagraphId` 用于恢复位置及书签/摘录定位，与 cursor 互斥并严格校验版本和章节关系；不会修改已读边界。预览目录当前返回数组，总数从预览 `chapterCount` 取得，默认 size=50；普通目录返回分页对象，默认 size=50。同位置新建重复书签返回 409 `BOOKMARK_EXISTS`；原幂等键重放返回原书签，已删除键返回 410。摘录编辑保留原锚点，并返回 `userEdited`/`originalExcerpt`，页面明确区分编辑内容与原选文。

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

## 索引与阅读任务

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

SSE 事件 `snapshot -> progress* -> result? -> done`；result 仅来自已持久化且当前可见的结构化结果，绝不发送模型原始 token。事件 id 为 taskId/revision，Last-Event-ID 只决定重放位置，不能跳过当前范围/状态校验；旧事件里的结果正文不直接重放。发送前与边界收缩使用同一发布序列，已开始发送的响应不能宣称可撤回。

入队满返回 429 `QUEUE_FULL`，不创建会自动运行的隐性后台重试。数据库已创建但尚未可靠派发时收敛到 PAUSED 并供显式恢复。模型调用未启用/未获授权返回 409 `LIVE_AI_NOT_AUTHORIZED`；索引不足 409 `INDEX_NOT_READY`；范围未设置 409 `READ_BOUNDARY_REQUIRED`。其他终态失败码按架构设计返回，技术失败不混为拒答。

## 笔记与独立确认

| 方法与路径 | 输入/行为 | 成功响应 |
| --- | --- | --- |
| `GET /versions/{id}/notes` | 分页 | 200 受范围约束的笔记列表 |
| `GET /notes/{id}` | 当前范围重验 | 200 详情或隐藏占位 |
| `POST /versions/{id}/notes` | clientRequestId、kind=`MANUAL/EXCERPT`、title/body、anchor?、expectedScopeRevision | 201；摘录必须匹配源选区；不接受 AI kind/sourceTaskId |
| `PATCH /notes/{id}` | expectedRevision、expectedScopeRevision、title/body；引用只可保留/删减 | 200；scope 标签只提高不降低；AI 标记 userEdited=true |
| `DELETE /notes/{id}` | expectedRevision | 204 逻辑删除 |
| `POST /tasks/{id}/note-proposals` | clientRequestId、title/body、retainedEvidenceIds、expectedScopeRevision、supersedesProposalId? | 201 不可变提案、contentHash、expiresAt；只有用户主动保存才创建 |
| `GET /note-proposals/{id}` | 无 | 200 当前可见的最终预览和状态 |
| `POST /note-proposals/{id}/confirm` | contentHash（必须与预览一致） | 200 当前状态/resultNoteId；不可夹带新 body 或替换引用 |
| `POST /note-proposals/{id}/reject` | 无 | 200 REJECTED；已成功返回 409 |

确认过期返回 410 `PROPOSAL_EXPIRED` 并持久化 EXPIRED；业务校验失败返回 409 及 FAILED 状态；当前范围不可见不给正文。数据库提交异常返回 503 `SAVE_RETRYABLE`，事务回滚，不假称失败状态已经提交。确认重复请求无需产生新 clientRequestId，提案 ID 就是副作用幂等键。

如果同任务已保存，返回既有 noteId；已删除则返回 `NOTE_ALREADY_SAVED_REMOVED`，不因新提案再插入。用户编辑已保存笔记走 PATCH；普通手写笔记不受这项 AI 来源唯一性限制。
