# 研序 项目背景

2026-10-23 回顾长度与 SSE 补修：任务 09f93b3d-45ad-43f7-9bce-bda9db7353dc 已确认 FACT_TEXT_LIMIT，正常结束生成，非 JSON_SCHEMA。用户随后明确只需百字回顾：Prompt v6 改为约 100 字、2–3 要点，服务端总字数上限 160（人物查证不变）。修复发送失败后 complete 再刷损坏响应及全局 ApiExceptionHandler 追加 JSON 的遗漏路径，隔离单连接异常。当前 acceptance-v17，未真实调用或代启；下一步先由用户重启并查看已有任务验证连接，后续手动日常回顾验证长度改善，见 docs/evaluation/reading-recap-length-stream-verification.md。

2026-10-23 多章回顾格式修复：现场两章/三章任务既有成功也有 JSON_SCHEMA 失败，三次报告均 finishReason=stop；旧回答未保留，具体结构错误不可追溯。Prompt v5 明确统一扁平 facts/partial 合同，回顾按类型检查并持久化细分原因，未放宽校验或自动重试。当前 acceptance-v16，真实改善尚未验证。下一步用户自行重启并手动验证相同范围的多章回顾，按具体错误码继续定位，见 docs/evaluation/reading-recap-schema-verification.md。

2026-10-23 SSE 修复：用户截图显示回顾完成，数据库最近三次回顾也均 COMPLETED。日志是 ReadingHttpAdvice 将已提交 text/event-stream 再写为 JSON 引发的二次异常，原始触发原因未留在所给日志中。现识别断连并禁止对已提交响应追加错误 JSON，未改变任务结果或重跑模型。实现 acceptance-v15 / Prompt v4；下一步用户重启后仅查看已有结果、开关助手验证连接，见 docs/evaluation/reading-sse-error-verification.md。


2026-10-23 回顾失败诊断修复：用户任务 1c5f4739-d33d-4945-8bb4-bcab28a4135c 返回实际 6097/979 token 后 OUTPUT_INVALID；旧数据缺少结束原因，不能确诊。现统一记录停止日志、持久化 failureReason 与白名单 finishReason，页面展示具体类别。当前 acceptance-v14 / Prompt v4；未重试或代启。下一步由用户重启并手动发起日常任务，用明确诊断定位实际故障，详见 docs/evaluation/reading-failure-diagnostics-verification.md。


2026-10-23 用户要求沿用原 Application：已删除新增 IDEA 启动项，在现有 application.yml 内自动导入本地配置，支持仓库根目录/backend 启动。日常仍只修改本地 ai-enabled true/false 并重启原后端；普通测试默认 offline。当前冻结 acceptance-v13，Prompt v4 不变，详见 docs/development/reading-direct-models.md。


2026-10-23 阅读反馈修复：位置自动保存与 AI 范围保持独立，首次回顾可在助手内直接确认保存位置；Prompt v4 补齐既有输出上限，日志增加不含正文的校验分类。离线回归通过，当前真实失败原因尚待用户错误码/任务编号确认，不宣称回顾已真实修复。实现冻结 acceptance-v12；下一阶段为用户自行启动后的单次回顾现场验证，详见[本轮记录](../evaluation/reading-recap-usability-verification.md)。


2026-10-23 最新规则：用户要求日常阅读仅用 true/false 开启模型，不设置金额预算。已接入 DIRECT_LIVE，本地 ai-enabled=true，历史试读账本不改。按用户要求停止 5201/18081 前后端，后续用户自行启动，见[日常开关](reading-direct-models.md)。旧试读授权/期限要求不再适用于此日常模式；固定评测和失败证据保持原状。

2026-10-23 真人试读首个回顾失败：DeepSeek 已连通并返回 usage，结果校验报 `OUTPUT_INVALID`，结算 0.02221000 元、无未知预留，试读账本已停止。另发现停止状态查询被事务回滚异常掩盖为 `READING_RETRYABLE`，当前离线修复见[失败记录](../evaluation/reading-trial-failure-verification.md)及当前交接；不自动恢复或重试。

2026-10-22 真人试读：已补齐实际自由提问所需的生产接线并启动独立试读服务；旧“核心功能已齐”仅指工程流程，之前页面只开放离线演示，真实调用只支持固定评测。用户已批准本机人工试读独立预算 DeepSeek 20 元/阿里 10 元，24 小时；本轮新入口和独立持久环境详见当前交接，原停止评测不恢复。

2026-10-22 用户最新决定：不再录制桌面/窄屏续读演示，录屏从当前待办及交付要求中移除，不记作已完成。已批准的小说阅读核心功能均有工程实现；最后的整体质量/体验验收尚未完成，主要缺口为最新修复的真实 DEBUG 复核、满足前置门禁后的正式 TEST/语义及 AI 性能评价、真实读者反馈。后续以[当前交接](continuation-handoff.md)为准，不再按下方历史建议安排录屏；本次说明不授权重启停止的付费批次。

2026-10-22 最新交付：完成长篇续读性能闭环，新增隔离测量入口、500 万字/1000 章夹具、桌面/窄屏各至少 30 次浏览器往返及失败判定。续读 p95 为 438.1/327.5 ms，口径及局限见[长篇性能验证](../evaluation/reading-performance-verification.md)。生产源码、Prompt 与 acceptance-v9 锁不变，无新增付费调用。下一阶段为可复现的续读展示与试用交付，范围、完成标准和依赖见[当前交接](continuation-handoff.md)。以下为历史交付，不再要求重复已完成的本机浏览器采样。

2026-10-22 最新交付：用户要求按完整功能点推进，当前已完成“回来接着读”工作台的桌面/窄屏交互与一键隔离演示：书架直达回顾、回顾/人物/历史分栏、引用与确认笔记、跨目录页续读。使用 reading-evidence-v3 / acceptance-v9；固定离线结果不代表真实语义通过。详见[工作台验证](../evaluation/reading-workspace-verification.md)和[当前交接](continuation-handoff.md)。以下各轮记录保留历史范围，旧的下一步不再作为当前待办。

2026-10-22 最新开发：已完成 FULL/PARTIAL 与依据组织的离线修正，Prompt 为 reading-evidence-v3，实现冻结 acceptance-v8。完整性按用户所问事项判断；有依据但遗漏时补齐答案，真实缺口继续 PARTIAL，无依据继续拒答。公共协议、检索与预算不变。v5/v7 真实观测及四个停止账本保留，本轮未执行付费或 TEST；离线回归不证明真实模型已修好。详见[完整性修复验证](../evaluation/reading-completeness-verification.md)和[当前交接](continuation-handoff.md)。下文为各轮历史记录。

2026-10-22 最新结果：用户主动发起 DEBUG 重测，已完成 acceptance-v7（Prompt 仍为 reading-evidence-v2）。V34 唯一前置账本承接剩余额度，严格限 12 条 DEBUG、复用旧索引。真实执行 29 次 HTTP，原 debug-008 正确拒答；debug-006/011 出现依据充分但 PARTIAL 过于保守的问题，因此新账本也以 DEBUG_REVIEW_FAILED 持久停止，TEST 96 次仍 NOT_RUN。本轮保守结算 DeepSeek 0.019374 元、阿里 0.00015050 元；含 v5 剩余分别为 19.963232 元、9.99951250 元，未知/预留均为零。后端和隔离库已停止。 详见[重测记录](../evaluation/reading-live-v7-results.md)。下一轮只推进完整性判断的离线修正，不自动恢复付费或 TEST；下文旧版本记录保留为历史。

2026-10-22 当前进展：已核实 PR #38 合并。本轮针对 debug-008 完成人物查证的明确可回答性协议与离线回归，Prompt 升级 reading-evidence-v2，实现冻结 acceptance-v6。公共结果格式、调用数及预算不变。新提示词尚未真实复测，v5 失败及停止账本保留；详见[拒答修复验证](../evaluation/reading-refusal-verification.md)。下方此前版本记录按当时状态保留。

2026-10-22 最新授权：用户明确批准 DeepSeek 20 元、阿里向量模型 10 元，并允许真实调用。执行固定语料批次，两个供应商独立账本、额度不可互借，任一停止阻止整个批次；此前“未授权/待确认”描述仅为历史。当前实现冻结为 acceptance-v5，保留 v1–v4；结果见[本轮执行记录](../evaluation/reading-live-v5-results.md)。

执行结果：真实 DEBUG 已完成 12 题和 6 个索引，35 次 HTTP 有已知用量；debug-008 未正确拒答，两个账本已以 DEBUG_REVIEW_FAILED 持久停止，TEST 96 次保留 NOT_RUN。下一步修复该 DEBUG 缺陷并离线验证，不能沿用旧“请求付费授权”待办或自动重跑停止批次。详细金额、证据、恢复材料及未完成项见上方执行记录。

> 新会话先读本文，再读 [AGENTS.md](../../AGENTS.md)、[新开发路线](novel-reading-roadmap.md)和[当前交接](continuation-handoff.md)，并核对代码、配置和 Git 状态。更新：2026-10-22。

## 当前方向

当前代码的展示名称仍为 **研序｜技术研究 Agent**，技术标识仍为 ContextPilot。已有单用户知识工作区具备 RAG、受控动作和有限研究任务框架；小说基础阅读、范围、任务和确认笔记离线链路已实现，本轮推进“验证续读体验并准备展示”的离线准备，真实 AI 调用尚未开放。

用户已认可转向长篇小说阅读应用，以“中断后快速接着读”为首个核心场景：按已读进度回顾剧情、查证人物、跳转原文，并选择保存笔记。2026-10-20 用户批准具体阅读合同，2026-10-22 补充提交规则：**每个会话只完成一个大功能点，相关检查通过并审阅 diff 后主动创建本地提交，使用中文提交信息，再提醒发起 PR、开新窗口继续**。具体规则见 [AGENTS.md](../../AGENTS.md)，本地提交不自动授权推送或创建 PR。新名称待定。

**[长篇小说阅读应用开发路线](novel-reading-roadmap.md)是唯一当前开发路线；旧研究路线及转向初稿已弃用。** 既有能力与验收事实保留，未完成收尾仍暂停，不默认恢复升级研究调优、录屏或检索优化。产品目标和参考见[产品需求](../requirements/product-requirements.md)，实施顺序、复用、预算和验收条件只在新路线维护。

已批准[业务合同包](../architecture/novel-reading-design.md) `reading-contract-v1-draft1`。2026-10-22 核对 PR #31–#35 均已合并，本轮起点为干净的 `develop/4f13128`，与远程一致。固定语料、评分工具、容量证据和[演示脚本](reading-demo.md)已提交；本轮按用户授权补齐阅读专用 HTTP 客户端、完整请求包装计量、embedding usage 传递和本地协议测试。生产网关仍无条件拒绝真实调用，未获得付费授权；价格、真实供应商可用性及语义/体验验收仍待完成。当前验收实现版本为 `reading-acceptance-v2`，保留 v1 数据集及历史锁，见[协议验证记录](../evaluation/reading-provider-verification.md)。

## 最新开发状态（2026-10-22）

最新更新：用户追加的预算日志/停止功能已实现，V33 保存预警和首次停止原因，当前冻结版本 acceptance-v4；云端配置说明及工程边界见[预算保护](../evaluation/reading-budget-alerts.md)。此前 v3 计划保持历史，真实调用仍未授权。

最新进展：PR #37 已合并；已在隔离库完成四份固定语料的真实 UUID 绑定和最终计划生成，两个账户的模型开通/按量条件经用户确认。批次仍未授权、调用为零，待用户确认 20 元停止额度及到期时间，见[最终批次准备](../evaluation/reading-live-batch-preparation.md)。

用户要求开始下一阶段开发后，已完成默认关闭的固定批次授权与采集接线：embedding total_tokens 修复、北京专属端点、V32 原子金额/次数预留、本地批准文件与 DEBUG/TEST 采集器。当前实现冻结为 acceptance-v3，v1/v2 保留。没有有效付费授权、没有真实调用；下一步在隔离评测库绑定 UUID/生成最终 plan，复核当时价格与账户适用条件，再请求独立付费授权。详见[门禁工程验证](../evaluation/reading-live-gates-verification.md)。前文及下方的适配/准备轮描述保留为历史记录。

## 已有能力与证据

2026-10-22 后续核对起点为干净的 `develop/144726e`，远程 develop 相同。本轮完成[公开模型/端点/价格核实与授权准备](../evaluation/reading-live-authorization-preparation.md)：旧 DeepSeek 请求名有兼容依据，北京 DashScope 共享端点仍支持；用户随后确认 DeepSeek 人民币计费、百炼北京地域及业务空间专属端点；账户权限尚未实测，付费授权仍为零。代码仍固定共享端点，后续接线需明确最终端点。发现 embedding 用量字段与当前官方文档不一致，需在真实调用前修复；授权接线、采集入口及可靠费用上界仍待补齐。此记录更新公开资料状态，不覆盖 acceptance-v2 的历史锁，也不代表生产已开放。

| 能力 | 当前结论 | 详情 |
| --- | --- | --- |
| 知识问答、受控动作、知识库维护、多文档比对、回答偏好 | V1–V5 已验收；后续改动仍需相关回归 | [功能清单](feature-inventory.md) |
| 升级影响研究 | 已实现但最终质量验收未通过，默认关闭；专项收尾暂停 | [最新独立采集](../evaluation/upgrade-closeout-independent-results.md) |
| 研究工作台、项目资料接入、成果审查 | 首版有工程与代表流程证据；不宣称全面语义验收，冻结旧场景扩展 | [功能清单](feature-inventory.md) |
| 导入并持续阅读 | 已提交基础功能及启动测试修复，远程 PR 状态以 Git/平台为准 | [验证记录](../evaluation/reading-library-verification.md) |
| 明确已读范围并查阅原文 | 已提交 `86f4579`，离线工程与代表页面证据已记录 | [范围验证记录](../evaluation/reading-scope-verification.md) |
| 回顾前情并查证人物 | 已提交离线工程链路，真实适配/语义验收未开放 | [任务验证记录](../evaluation/reading-task-verification.md) |
| 确认保存回顾笔记 | 已提交 `42686ca`，PR #34 已合并 | [笔记确认验证](../evaluation/reading-note-verification.md) |
| 验证续读体验并准备展示 | 离线资产/展示准备已提交；本轮补齐本地 HTTP 适配与协议证据，整项验收未完成 | [离线准备](../evaluation/reading-experience-verification.md)、[协议验证](../evaluation/reading-provider-verification.md) |

统一 50 项核心任务已有首次实跑；升级研究新 DEBUG、新 TEST 与 8×2 代表运行也已采集。语义复核暂缓、录屏未完成，终态匹配不是任务质量成绩。最新事实与缺口集中在功能清单，历史失败、Prompt、评估输入和原始结果不删除、不改写。

## 必须保留的边界

- 单用户 `LOCAL_OWNER` 仅提供归属，不是认证；客户端不能指定用户 ID，部署限本机或可信网络。首版规划不含账号，公开部署需另行确认认证与完整资源授权。
- 既有聊天路由保持 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION`；简单交互只使用确定性白名单。
- 保留知识库隔离、证据/引用校验、拒答、逻辑删除、SSE、历史、trace，以及强类型动作的持久提案、独立确认和原子幂等执行。
- V4 确定性编排、V5 呈现偏好、升级研究工具循环、任务工作台规划及专用 MCP 分别遵守既有合同；例外不扩展为通用 Agent 权限。
- 新小说业务不能直接把文档当章节、偏好当进度；检索/摘要/缓存/历史结果均需设计阅读范围约束，“不剧透”仍须验证。

## 技术与入口

Java 21 / Spring Boot 4.1.x / Spring AI 2.0.x / MyBatis-Plus / Flyway；Vue 3 / TypeScript / Vite / Element Plus；PostgreSQL + pgvector。生成默认 DeepSeek `deepseek-v4-flash`，DashScope `qwen3.7-text-embedding` 为 1024 维。维护模块化单体、有界后台任务及 SSE，不提前搭建通用平台。

- [文档导航](../README.md)：当前规划、实现参考与历史档案分开查阅。
- [技术架构](../architecture/technical-architecture.md)、[数据模型](../data/data-model.md)、[API](../api/api-design.md)：既有实现合同，不代表新业务已落地。
- [本地开发](local-development.md)：启动、配置与验证；日常分支 `develop`，不得直接提交 `main`。
- [评估入口](../../evals/README.md)：按改动范围验证，不自动重跑付费模型采集。
