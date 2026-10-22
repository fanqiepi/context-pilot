# 研序 项目背景

> 新会话先读本文，再读 [AGENTS.md](../../AGENTS.md)、[新开发路线](novel-reading-roadmap.md)和[当前交接](continuation-handoff.md)，并核对代码、配置和 Git 状态。更新：2026-10-22。

## 当前方向

当前代码的展示名称仍为 **研序｜技术研究 Agent**，技术标识仍为 ContextPilot。已有单用户知识工作区具备 RAG、受控动作和有限研究任务框架；小说基础阅读、范围、任务和确认笔记离线链路已实现，本轮推进“验证续读体验并准备展示”的离线准备，真实 AI 调用尚未开放。

用户已认可转向长篇小说阅读应用，以“中断后快速接着读”为首个核心场景：按已读进度回顾剧情、查证人物、跳转原文，并选择保存笔记。2026-10-20 用户批准具体阅读合同，2026-10-22 补充提交规则：**每个会话只完成一个大功能点，相关检查通过并审阅 diff 后主动创建本地提交，使用中文提交信息，再提醒发起 PR、开新窗口继续**。具体规则见 [AGENTS.md](../../AGENTS.md)，本地提交不自动授权推送或创建 PR。新名称待定。

**[长篇小说阅读应用开发路线](novel-reading-roadmap.md)是唯一当前开发路线；旧研究路线及转向初稿已弃用。** 既有能力与验收事实保留，未完成收尾仍暂停，不默认恢复升级研究调优、录屏或检索优化。产品目标和参考见[产品需求](../requirements/product-requirements.md)，实施顺序、复用、预算和验收条件只在新路线维护。

已批准[业务合同包](../architecture/novel-reading-design.md) `reading-contract-v1-draft1`。2026-10-22 核对 PR #31–#35 均已合并，本轮起点为干净的 `develop/4f13128`，与远程一致。固定语料、评分工具、容量证据和[演示脚本](reading-demo.md)已提交；本轮按用户授权补齐阅读专用 HTTP 客户端、完整请求包装计量、embedding usage 传递和本地协议测试。生产网关仍无条件拒绝真实调用，未获得付费授权；价格、真实供应商可用性及语义/体验验收仍待完成。当前验收实现版本为 `reading-acceptance-v2`，保留 v1 数据集及历史锁，见[协议验证记录](../evaluation/reading-provider-verification.md)。

## 已有能力与证据

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
