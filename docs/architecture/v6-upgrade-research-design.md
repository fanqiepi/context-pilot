# V6 受控升级研究详细设计

> 状态：2026-10-15 用户以“允许，继续执行任务”批准本合同，Slices 1–7 连续实施中。范围与初始预算已批准，尚不代表实现或验收完成。下文“拟定”等措辞均指批准的目标设计，不代表已交付能力。

实现与测试已落地，首轮冻结专项未满足验收条件；新建研究默认关闭。完整证据与剩余工作见 [V6 实现验证记录](../evaluation/v6-implementation-review.md)。不得把真实调试通过或结果采集测试成功视为版本验收。
>
> 2026-10-20：旧研究开发路线已弃用，唯一当前开发路线为[长篇小说阅读应用开发路线](../development/novel-reading-roadmap.md)。本文保留 V6 实现合同，调优与最终验收暂停，历史实施状态不触发恢复工作。V1–V5 基线及 V6 原有验收要求保持不变，不以既有测试方法数代替质量成绩。

## 1. 用户结果与首版边界

用户在一个知识库中选择 1–3 份旧版技术方案，指定允许仓库中的 1–3 个发布标签，并提出升级问题。助手通过模型选择受限只读工具，结合本地证据与发布记录生成带来源的升级影响、迁移清单和待核实项。用户预览后主动发起保存提案，再通过独立请求确认，报告才进入报告列表。

示例：“根据这份旧版 Spring AI 接入方案，分析指定目标版本的工具调用变化，列出迁移项和需要回归的行为。”旧方案使用自编公开合成资料；不能上传真实密钥或私密项目配置作为演示数据。

首版拟将仓库目录固定为 `spring-ai -> spring-projects/spring-ai`，仅读取公开 GitHub Releases。用户选择明确标签；不解释任意 URL、不默认查询 latest、不抓取网页或仓库代码、不自动遍历所有中间版本。多个标签是用户声明的研究材料集合，不构成完整迁移覆盖证明。页面和回答必须显示此限制。

新增 `UPGRADE_IMPACT` 知识任务，逻辑归属 `KNOWLEDGE_QA`；保存提案归属 `BUSINESS_ACTION/SAVE_RESEARCH_REPORT`。不新增顶层路由，不依靠关键词自动进入 V6，不改变 V4 `DOCUMENT_COMPARISON` 及其一次综合、90 秒、1800 字合同。

首版不包含 MCP、工作流引擎、多 Agent、动态工具、任意 HTTP/SQL/Shell、私有仓库、写 GitHub、执行迁移、混合检索、Rerank、登录或自动记忆。V6 首版不读取长期偏好，避免偏好进入工具决策；V5 在普通 RAG/V4 的行为保持原样。

## 2. 入口与应用边界

新增业务包 `upgraderesearch` 和 `researchreport`，使用显式 Java 应用服务、有界执行器和 MyBatis-Plus。V6 使用独立 API 和运行表，不扩展 V4 的固定计划步骤或复用其终态枚举。前端在研究入口增加“升级影响”，历史通过专用运行列表恢复；首版不将 V6 工具消息塞入普通聊天历史。

建议服务职责：

| 服务 | 职责 |
| --- | --- |
| `UpgradeResearchStartService` | 校验主体、知识库、文档、仓库键、标签、幂等请求；冻结输入与预算；事务提交后派发 |
| `UpgradeResearchExecutor` | 单次模型回合、工具校验、顺序执行、截止时间、取消检查、证据账本、终态 |
| `UpgradeResearchModelGateway` | Spring AI 原生工具 schema、单次响应和 usage 映射；不自行循环 |
| `ReleaseNotesClient` | 固定 GitHub GET 接口、超时、字节上限、来源规范化 |
| `UpgradeResearchQueryService` | 当前主体过滤的运行、来源、工具摘要和事件快照 |
| `ResearchReportService` | 草稿快照校验、保存提案、确认保存、报告查询与逻辑删除 |

V6 模型适配器只取得一次模型响应，将结构化 tool calls 交还应用。静态分派仅覆盖下面三个读工具；不注册到全局 ChatClient，不允许框架在应用预算检查之外自动递归执行。工具调用 ID 必须非空且在单轮内唯一；工具名不匹配立即失败；严格 JSON 解析拒绝未知字段、重复键及非预期类型。

当前 `backend/pom.xml` 固定 Spring AI 2.0.0。官方 2.0 工具执行机制已改变，不能复制旧版 `internalToolExecutionEnabled(false)`。实施时先针对本地 2.0.0 jar 编译验证单次响应与工具结果消息映射，再验证真实 DeepSeek 返回协议。使用项目已有 Spring AI 依赖和 JDK HTTP 客户端，预计无新增生产依赖；若必须引入依赖，先说明用途与替代方案。

技术参考：[Spring AI 2.0 工具执行说明](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/)、[Spring AI 工具文档](https://docs.spring.io/spring-ai/reference/api/tools.html)、[GitHub Release API](https://docs.github.com/en/rest/releases/releases)。这些资料用于 API 核验，不是本项目验收证据。

## 3. 静态工具合同

`ownerId`、`runId`、知识库、文档全集、仓库路径、允许标签、预算和 deadline 都来自服务端运行上下文，不能由模型参数覆盖。

| 工具 | 模型可传参数 | 服务端执行规则 |
| --- | --- | --- |
| `search_selected_documents` | `query`：1–500 字；`documentIds`：1–3 个 UUID | 必须是冻结文档集合的子集；逐文档检索，固定 topK=3；校验当前 profile 和文档可用性；每次底层查询计入预算 |
| `read_evidence` | `evidenceId`：服务端已返回的 ID | 仅从本次运行账本返回该条受限摘录；不接受任意 chunk、文件或文档 ID；不再次访问文件系统 |
| `get_release_notes` | `tag`：允许标签中的一个 | 仓库由运行上下文解析；精确调用 `/repos/spring-projects/spring-ai/releases/tags/{tag}`，无列表遍历与自动重试 |

标签输入仅允许 1–100 个 ASCII 字符 `[A-Za-z0-9._-]`，最多 3 个去重值；不接受斜杠、百分号或路径片段。后端固定 HTTPS 主机 `api.github.com`，禁用重定向，使用正常证书校验。生产配置不接受客户端覆盖 base URL；测试替身只能经测试配置注入。GitHub token 可缺省；若配置，只放后端环境变量且只发送到固定主机。

外部响应流按解压后的字节计数，超过 256 KiB 立即关闭；请求连接 3 秒、完整响应 10 秒并受运行剩余时间限制。只保留 release ID、tag、标题、发布时间、抓取时间和受限正文；不抓资产、链接目标或 diff。草稿版本拒绝；预发布版本明确标注。401/403/429、404、5xx、非法 JSON、正文为空各有独立安全错误码；不自动等待限流恢复。

只向模型返回每条发布记录最多 6000 字的正文，并记录 `truncated`；本地摘录每条最多 1200 字。返回值包含不可信数据标识、来源 ID、截断状态及安全错误码，不包含凭据、原始堆栈或私密日志。禁止将工具内容拼接成系统指令。外部链接由服务器按固定仓库和标签构造，不能信任响应中的任意 URL。

相同工具与规范化参数在同一次运行内重复调用返回已有结果，仍消耗工具请求次数但不重复发起网络/检索；参数哈希包含冻结范围。模型重复 tool-call ID 或超大参数不能被解释成新权限。没有任何确认、保存、记忆修改或文件写入工具提供给模型。

保存提案由用户点击发起，这是对路线图“提交报告保存提案”候选工具的首版收敛：模型只研究，用户决定何时保存。若希望由模型创建提案，需在审阅时明确变更此选择。

## 4. 循环、预算与结果

以下数值是保守的待批准起点，不声称已由真实 V6 延迟测量证明。参考 V4 的单次 90 秒上限并为多次调用预留空间，最终由 Slice 1 兼容性实验验证；调整必须更新预算版本与评估配置，不能因测试失败静默放宽。

| 限制 | 拟定 `upgrade-budget-v1` |
| --- | --- |
| 并发运行 / 等待队列 | 单实例最多 1 个运行，队列 2 个；满时 429，不遗留待运行任务 |
| 总时间 | 从持久化开始 180 秒，包含排队；单次模型至多 60 秒且不超过剩余时间 |
| 模型请求 | 最多 4 次，总数包含最终综合和失败调用；无结构修复/隐藏重试 |
| 工具请求 | 最多 8 个，参数非法、重复和缓存命中都计数；顺序执行，不并行 |
| 实际检索 | 最多 9 次逐文档查询；发起前预留本批全部预算 |
| 外部读取 | 最多 3 次 HTTP 请求，对应最多 3 个标签；不自动重试 |
| 证据账本 | 最多 18 条本地摘录，3 条发布记录；总摘录最多 24000 字 |
| 单次模型输入 | 系统/问题/schema/工具历史/证据合计最多 40000 字；超限终止，不自动摘要 |
| 工具参数 / 单轮调用数 | 单调用 JSON 最多 2000 字；单轮最多 3 个调用，整批预校验 |
| 最终正文 | 最多 3000 字；最多 20 个来源引用；超限失败，不截断成貌似完整报告 |

不继承 V4 的“单次调用”实现约束，但不改动 V4。V6 暂拟不发送请求级 `max_tokens`，必须单独用 DeepSeek 实测确认；时间与调用数不能保证费用硬上限，usage 缺失记为未知，不能填 0。付费验收的调用总数和估算价格另行记录。

执行顺序：取得运行权 → 构造冻结上下文 → 单次模型调用 → 检查取消/deadline → 验证整批工具名、参数与预算 → 逐个登记工具开始并执行 → 有界结果入账 → 下一轮。工具调用和预期参数是审计对象，不采集模型隐藏思维过程。

最多前三轮可执行工具；第 4 轮明确关闭工具，只允许根据现有证据综合。仍返回工具调用则 `FAILED/TOOL_PROTOCOL_INVALID`。模型提前返回最终答案时也须通过证据门禁：至少一条本地依据和一条所选 release 依据，所有引用 ID 来自账本。证据不支持的推断必须写为“待验证”，不能写成发布事实。

结果包含升级影响、逐项迁移建议、验证清单和资料缺口。`SUCCEEDED` 表示结构/来源门禁通过且指定材料读取完整，不等于语义事实自动验真；语义质量须人工评估。某标签缺失、截断或局部检索缺失但仍有双侧证据时可 `PARTIAL` 并显式列出缺口。无双侧证据则 `REFUSED`，不生成可保存草稿。非法范围、协议、上下文/调用预算耗尽直接失败，不借额外综合绕过上限。

## 5. 持久化、幂等与状态竞争

新增表由 V20/V21 迁移建立，不修改 V1–V19 既有迁移：

| 表 | 关键字段 / 约束 |
| --- | --- |
| `upgrade_research_run` | UUID、服务端 owner、KB、clientRequestId、requestHash、问题、文档快照、repoKey、标签、预算/Prompt/schema版本、状态、deadline、traceId、计数、draft正文及hash、errorCode、时间、deleted；唯一 `(owner_id, client_request_id)` 包含删除记录 |
| `upgrade_research_tool_call` | runId、ordinal、modelCallId、providerToolCallId、静态工具名、参数hash、安全参数摘要、状态、cacheHit、开始/结束、errorCode；唯一 `(run_id, ordinal)` |
| `upgrade_research_source` | runId、sourceId、类型、文档/chunk/profile/内容hash 或 releaseId/tag/固定URL/publishedAt/fetchedAt、受限摘录、截断标记、deleted；唯一 `(run_id, source_id)` |
| `upgrade_research_model_call` | runId、ordinal、provider/model、Prompt/schema版本、耗时、usage可空、状态、traceId、errorCode；不记录完整 Prompt 或隐藏推理 |
| `research_report` | ownerId、KB、runId、sourceActionId、标题、不可变正文、draftHash、来源快照、createdAt、deleted；`run_id` 和 `source_action_id` 均唯一（包含删除记录） |

工具轨迹和模型记录明确关联；既有 `model_call` 不强行接收没有 chat message 的 V6 调用。所有业务表逻辑删除，外键 `ON DELETE RESTRICT`；来源摘录是业务数据，不进入普通日志。知识库存在报告或运行时禁止删除，需先由专用用户操作逻辑删除相关资源；仍不做物理清理。

运行：`QUEUED -> RUNNING -> SUCCEEDED/PARTIAL/REFUSED/FAILED/CANCELLED`。超时归为 `FAILED/DEADLINE_EXCEEDED`；重启时将遗留活动记录标记 `FAILED/PROCESS_RESTARTED`，不自动重跑模型或恢复工具。工具状态 `STARTED -> SUCCEEDED/FAILED/CANCELLED`；故障恢复收敛遗留 STARTED 记录。

相同 clientRequestId + 相同规范化 requestHash 返回原运行；同 ID 不同输入返回 409。用户重试失败/取消任务使用新 clientRequestId 与可选 retryOfRunId；服务端校验原运行、主体、知识库，重试是新预算、新审计。相同内容的新 ID 表示新研究，不自动复用旧外部结果。

requestHash 使用版本化规范 JSON：问题只去首尾空白，文档 UUID 集合排序，标签去重排序但保留大小写，包含 repositoryKey、knowledgeBaseId 和 retryOfRunId；不含服务端时间、traceId 或预算当前配置。幂等检查先于队列容量检查，避免重放被新任务挤出。已逻辑删除运行的幂等键仍占用：同键重放返回 `409 UPGRADE_RUN_DELETED`，不返回已隐藏正文、不重新执行。

提交前预留单实例执行器容量；数据库回滚释放预留，提交后才派发。若提交成功但派发异常，将已持久化运行更新为 `FAILED/DISPATCH_FAILED` 并释放容量；进程在这两步之间退出则由重启收敛负责。容量不足且尚未持久化时返回 429，不生成运行；不能让客户端收到 429 却留下一个等待偷偷执行的任务。不得在请求线程中使用 CallerRunsPolicy 绕过并发上限。

运行表另含非负 `revision`，每次可见状态、工具进度或草稿变更与 revision 递增在同一事务中提交；事件快照不得混合不同 revision 的字段。启动时冻结每份文档的内容 SHA-256、profileId 和 indexedAt；每次检索前后及最终发布草稿前核对快照。删除、重建或处理中变化返回 `FAILED/SOURCE_CHANGED`，不把新旧索引拼成同一份研究证据。确认报告时复核同一快照；已保存报告保留原来源快照，但页面标注当前源已不可用，不从已删除源重新读取正文。

取消接口锁定运行并提交 CANCELLED；每个工具/模型 dispatch 的授权登记也锁定同一运行，只有活动状态且 deadline 未到才登记。以登记提交作为“调用已开始”的线性化点：取消后不得登记新调用；取消前已登记的调用尽力中断，结果不得复活运行或创建草稿。取消与完成以条件更新的提交赢家为准；完成后取消返回 409，重复取消返回已有 CANCELLED。不得声称提供商取消或停止计费。

后台截止扫描每秒检查活动运行，用条件更新收敛超时；线程/HTTP中断不成功也不能阻止数据库终态。结果写入与终态检查在一个短事务中完成；不在网络请求期间持有数据库锁。

## 6. 报告保存：预览、提案、独立确认

运行草稿和证据为研究审计数据，自动持久化并不等于保存用户报告。只有 `research_report` 插入算保存副作用。UI 区分“研究草稿”和“已保存报告”。

仅 `SUCCEEDED/PARTIAL` 且通过来源校验的不可变草稿允许提案；PARTIAL 的缺口必须进入提案摘要和报告。标题由用户提供（去首尾空白后 1–120 字），正文和来源只从服务端 run 读取。提案参数强类型为 `{runId, draftHash, title}`；不允许客户端或模型覆盖正文、owner、KB、状态、结果 ID。

为了保持现有 action_request 与聊天消息关联约束，发起保存提案时在对应知识库的合法会话中持久化一条显式用户保存请求及助手提案卡，标记 `BUSINESS_ACTION` 和新匹配原因 `UPGRADE_REPORT_SAVE_SELECTED`。无 conversationId 则创建会话；提供时必须与运行知识库匹配。不复用 V6 研究正文作为该动作的用户授权。

提案接口带 `clientRequestId`，新增专用 `research_report_save_request` 关联表：owner、clientRequestId、requestHash、runId、actionRequestId、时间、deleted；唯一 `(owner_id, client_request_id)`，同键不同内容 409。对 run 加行锁，已有待确认提案返回原提案；已有成功报告返回已有结果；拒绝/过期后，用户新请求才创建新提案。不同请求不能覆盖已展示的标题或参数。

新 clientRequestId 遇到已有待确认提案时，只有规范化标题和会话目标相同才映射到原提案；否则返回 `409 REPORT_PROPOSAL_CONFLICT`，让用户先处理原提案。同键重放保持首次参数，不以当前输入覆盖。新建提案、聊天消息与 save_request 映射在一个事务中提交；不得因重放额外生成用户/助手消息。报告已删除时，新保存请求返回 `409 REPORT_ALREADY_DELETED`，不重新创建提案。

新增静态 `SAVE_RESEARCH_REPORT`、sealed 参数类型及执行器。`action_request` 新增 `upgrade_run_id` 外键及 `parameters.runId` 一致性约束，扩展 action_type、参数白名单和 result_resource_id 约束。当前 V17 只允许 CREATE_USER_MEMORY 使用 result_resource_id，不能只加 Java 枚举而遗漏数据库约束。

确认仍调用 `/api/action-requests/{id}/confirm`，不接受请求体覆盖参数。确认时复核主体、运行可用性、draftHash、来源未删除/未失效和提案未过期；本地文档被删除或重建后拒绝保存，提示重新研究。过期沿用既有 confirmationTimeoutMinutes，不新设不一致时限。

`PENDING_CONFIRMATION -> EXECUTING -> SUCCEEDED/FAILED` 复用现有原子取得执行权。报告插入和 action 成功标记在同一数据库事务中完成，无 HTTP/文件写入；唯一 runId 防止不同提案重复保存。若报告已被逻辑删除，重复确认只能返回历史结果/已删除标识，不恢复或创建第二份报告。需要新报告须重新发起研究。

数据库错误不得被吞掉后提交半成品；需要用真实 PostgreSQL 注入“插入后失败”“成功标记失败”和并发确认验证整体回滚。不能仅依赖当前 ActionRequestService 捕获 RuntimeException 的路径推断原子性已经满足新动作。

报告首版只支持列表、详情和用户逻辑删除，不支持编辑、导出文件、自动上传或加入向量索引。创建新版本通过新研究完成。

## 7. API、SSE 与页面合同

以下 API 已进入实现与验证阶段，默认关闭新运行入口；验收状态以评估记录为准。

| 方法 / 路径 | 请求与返回 |
| --- | --- |
| `GET /api/upgrade-research/repositories` | 返回固定 repoKey、显示名、公开来源地址；不返回 token |
| `POST /api/upgrade-research/runs` | `{clientRequestId, knowledgeBaseId, documentIds, repositoryKey, releaseTags, question, retryOfRunId?}`；question 1–2000 字；新建 202，幂等重放 200 |
| `GET /api/upgrade-research/runs?knowledgeBaseId=…&page=…&size=…` | 当前主体、KB、逻辑删除过滤；size 1–50；稳定时间/ID排序 |
| `GET /api/upgrade-research/runs/{id}` | `{run,sources,tools,modelCalls,saveActionId,runId,traceId,revision}`；保存提案 ID 来自数据库，支持跨浏览器恢复；run 包含冻结范围、状态、预算、草稿与缺口 |
| `GET /api/upgrade-research/runs/{id}/events` | SSE 当前快照与进度，不重跑研究 |
| `POST /api/upgrade-research/runs/{id}/cancel` | 无请求体；返回最终状态 |
| `POST /api/upgrade-research/runs/{id}/save-proposals` | `{clientRequestId,title,conversationId?}`；返回提案，绝不直接保存报告 |
| `GET /api/research-reports?knowledgeBaseId=…&page=…&size=…` | size 1–50，返回当前主体活动报告 |
| `GET /api/research-reports/{id}` | 不可变正文、来源快照、原运行、缺口、保存时间 |
| `DELETE /api/research-reports/{id}` | 用户逻辑删除，重复操作 204；不物理删除 |
| `DELETE /api/upgrade-research/runs/{id}` | 仅终态、无活动报告且无待确认/执行中提案时逻辑删除，其他 409；不物理删除证据 |

不存在/已删除资源返回 404；参数/schema 非法 400；状态、幂等键冲突、失效来源 409；队列满 429；配置关闭返回稳定的 `UPGRADE_RESEARCH_DISABLED`。权限来源仍是 LOCAL_OWNER，不能描述为多用户隔离安全边界。

SSE 为 `snapshot -> progress* -> result? -> done`；网络异常发送安全 error 并允许 GET 恢复。每个事件携带 runId、traceId 和单调 revision；重连先发数据库快照，客户端丢弃旧 revision，不保证逐条事件回放。SSE 断开不取消任务；显式取消才改变状态。首版最终正文通过 result 一次发送，进度呈现工具名/耗时/状态；不流式展示未校验模型文本或隐藏推理。

页面包括：公开来源告知、旧文档选择、标签输入、开始/取消、预算与工具轨迹摘要、本地/外部引用区分、截断/缺失提示、草稿预览、保存提案卡、独立确认/拒绝、报告列表/详情。Markdown 复用现有清洗策略，外链只允许服务端规范化来源，不渲染原始 HTML 或远程媒体。刷新后从运行/报告 API 恢复；390×844 与桌面均验证。

## 8. 评估合同与按序交付

进入生产切片前必须冻结统一 50 个独立核心任务、调试/测试分离与评分口径。该工作可以作为 V6 Slice 1 的前置交付，不删除原路线门槛，不把尚未完成的展示录屏声称为完成。V6 专项任务另行定义，去重后报告其分母，不把多次执行相加。

拟定专项 `v6-upgrade-research-v1`：24 个独立语义任务（8 直接影响、6 跨源组合、4 资料缺口、6 注入/范围隔离）及至少 16 个生命周期/故障案例。资料使用公开自编旧方案、版本化合成 release fixture，调试资料与最终评估按来源族拆分。真实 GitHub 结果另存抓取时间/hash并标记可变来源，不能和固定 fixture 分数混算。无授权转载长发布文本；固定语料采用事实摘要和来源链接。

语义评分逐条人工核查关键事实、引用支持、迁移项可执行性及未支持推断；关键词/schema 检查仅是工程门禁。阈值拟定：任务完成率 ≥85%，引用支持率 ≥95%，缺口/拒答正确率 ≥90%；越界读取、未确认保存、预算超限、新调用晚于取消登记均为 0。所有生命周期用真实 PostgreSQL 验证；无 Docker、未运行和失败不得填通过。

故障至少覆盖：未知工具、参数越界、重复调用、超大结果、恶意发布正文、空/缺失 release、403/429/5xx、检索失败、模型失败/协议不支持、调用/上下文耗尽、超时、取消竞态、断线恢复、进程重启、重复提案、重复/并发确认、插入后事务失败、来源失效、逻辑删除后重放。

真实验收至少 8 个代表任务（成功、局部缺失、拒答、提示注入各 2 个），每个 2 次独立运行，记录真实 DeepSeek tool calls、DashScope/pgvector、GitHub 来源和确认保存；失败也保留结果。首次兼容性探针最多 2 次任务、每次最多 4 次模型请求；协议不支持则停止，不以模拟成功冒充模型工具调用。

统一报告记录数据/代码/配置/Prompt/schema版本、逐条结果、每轮调用与 usage、首次进度/首字/总延迟、P50/P95、错误归因、未知 usage 与费用估算来源。非流式首字定义为通过校验的 result 到达时间，不能拿 progress 冒充首字。超时样本纳入终态延迟，原值与样本数必须保留。

| Slice | 交付与独立验收 |
| --- | --- |
| 1 | 统一核心基线冻结；V6 公开语料/专项任务/评分/预算；测试专用 Spring AI 2.0 编译及 DeepSeek 工具协议探针；不启用生产循环 |
| 2 | 运行/来源/工具/模型记录迁移、主体过滤、幂等启动、取消/截止/重启收敛；真实 PostgreSQL |
| 3 | 三个静态只读工具、固定 GitHub adapter、大小/超时/范围/缓存门禁；本地 HTTP fixture 故障测试 |
| 4 | 有界模型循环、证据与引用校验、usage与错误终态；生产默认关闭，可在验收环境显式开启 |
| 5 | 不可变草稿到 SAVE_RESEARCH_REPORT、数据库约束、用户提案与原子确认；并发/回滚/删除验证 |
| 6 | API/SSE、升级入口与报告页、历史恢复、桌面/窄屏、正常/失败演示 |
| 7 | V1–V5 全量回归、专项冻结评估、真实端到端与人工评分、预算采用决定、最终验收报告 |

设计整体批准后，以上切片是连续实现检查点，不是重复授权门；只在超出合同或实测必须改变合同的情况下提出具体变更。各切片保留未运行项目与失败证据。

## 9. 本次审阅决定

2026-10-15 已整体批准以下首版取舍：固定公开 Spring AI 仓库及用户明确标签；1–3 份文档与 3 个只读工具；4 次模型/8 次工具/180 秒预算；用户主动发起保存提案、独立确认后保存数据库报告；不读取长期偏好、不接入 MCP；统一基线作为 Slice 1 前置。

从 Slice 1 开始连续实施；已同步 AGENTS、概览与产品状态。实际切片进度与证据写入独立报告，不把设计合同本身当作已测量的基线或已交付能力。

### 2026-10-19 兼容问题范围澄清（用户已确认）

宽泛的“如何兼容旧客户端”默认要求回答资料明示的兼容机制，不要求证明所有旧行为不变。已回答所需机制时，未承诺的其他行为写为范围说明，不自动成为 missingEvidence。用户明确要求某项行为保证、恢复时间或完整迁移方案而证据不足时，仍保留必要缺口及 PARTIAL；无双侧证据仍拒答。工具失败、截断和所选来源缺失的服务端判定不变。不得将范围说明写成行为保持不变的事实。

本决定只指导后续实现和新验收，不改写已冻结问题、锁文件或历史结果；Prompt 候选须经独立调试后再决定是否进入生产。

### HTTP 读取超时对齐（2026-10-19）

独立 DEBUG 中发现请求约 10 秒后抛出 Reactor ReadTimeoutException。核对当前 Spring Web 7.0.8 请求工厂实现，默认 responseTimeout 为 10 秒；升级研究自行构造的 RestClient 未显式覆盖该默认值。现显式使用 ReactorClientHttpRequestFactory，并将读取超时设为既有 UpgradeBudget.MODEL_SECONDS（60 秒）。应用 Future 的单次硬截止与运行总截止继续生效，超时仍取消等待；无重试、调用次数及总预算均不变。这是底层传输与已批准预算的对齐，不是延长批准预算。该变更不影响其他功能的客户端配置。

2026-10-19 独立调试后采用 upgrade-research-v6，执行器资源与新运行 Prompt 版本同步更新；候选对照见范围调试记录。功能默认关闭，独立质量验收与人工签核尚未完成。
