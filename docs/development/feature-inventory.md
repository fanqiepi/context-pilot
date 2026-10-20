# 研序 功能与证据索引

核对：2026-10-20。本文只维护已有功能和证据状态；实施顺序、旧计划处置和恢复条件统一见[新开发路线](novel-reading-roadmap.md)。本次执行确认保存笔记的工程验证及相关回归；旧语义验收仍引用历史报告，不代表本次语义复验或已发布。

V1–V5 已验收；升级研究最终质量验收未通过、默认关闭。工作台、项目接入和审查有首版工程/代表流程证据，不等于全面语义验收。旧研究路线停止扩展，专项收尾暂停；暂停不能改写为通过。小说基础阅读、范围及阅读任务离线链路已实现，本轮补齐确认保存笔记；生产真实适配及语义验收仍未开放。

2026-10-20 用户批准 `reading-contract-v1-draft1`，随后要求每个会话只完成一个大功能点并提醒提交 PR。本轮实施范围与证据如下；没有运行付费模型实验。

## 小说基础阅读

| 用户能力 | 实现与边界 | 证据 |
| --- | --- | --- |
| 导入小说 | 严格 UTF-8 TXT/Markdown、固定分章规则、异步预览/更换规则、确认发布、重复版本识别、原文件保留 | [验证记录](../evaluation/reading-library-verification.md)、Flyway V29 |
| 书架与续读 | 书架、独立版本、章节/段落分页、字体/行距/主题、自动保存与 CAS 冲突处理；不依赖模型 | 同上 |
| 书签与笔记 | 书签、手写与段内摘录、编辑/逻辑删除；摘录修改显示“已编辑”并保留原选文 | 同上 |
| 明确已读范围并查阅原文 | 统一范围服务、段落/整章显式确认、回退 epoch 与事务发布门禁、状态 SSE、目录/笔记可见性、受限原文预览、独立“从这里继续读” | [范围工程验证](../evaluation/reading-scope-verification.md)；不等于阅读 AI 任务或语义防剧透验收 |
| 回顾前情并查证人物 | 专用索引、有界任务、回顾/人物查证、引用、任务 SSE、调用/费用账本与助手页面的离线工程实现 | [验证记录](../evaluation/reading-task-verification.md)；生产真实适配器保持关闭，尚无语义验收 |
| 确认保存回顾笔记 | 持久不可变预览、替代/拒绝/过期、独立确认、同任务唯一笔记、事务回滚、完整输入范围继承、冻结出处及编辑/逻辑删除 | [笔记确认工程验证](../evaluation/reading-note-verification.md)；固定任务验证，不代表真实模型语义验收 |

## 已验收能力

保留固定编号，便于代码、合同与历史证据互查。后续改动仍须执行相关回归。

| 编号 | 版本 | 功能 | 用户可获得的能力与边界 | 状态 | 依据 |
| --- | --- | --- | --- | --- | --- |
| V1-01 | V1 | 知识库与文档管理 | 在知识库中管理资料，文档和业务记录采用逻辑删除 | 已验收 | [背景](project-context.md)、[接口](../api/api-design.md) |
| V1-02 | V1 | 文档摄取 | TXT、Markdown、文本型 PDF 上传、解析、切分与异步处理；不含扫描件 OCR | 已验收 | [背景](project-context.md)、[架构](../architecture/technical-architecture.md) |
| V1-03 | V1 | 向量检索与隔离 | DashScope Embedding + pgvector，按知识库限定检索范围 | 已验收 | [架构](../architecture/technical-architecture.md) |
| V1-04 | V1 | 有依据的知识问答 | 根据检索证据生成回答，展示引用；证据不足时拒答 | 已验收 | [背景](project-context.md)、[评估计划](../evaluation/evaluation-plan.md) |
| V1-05 | V1 | 流式回答与历史 | SSE 回答、会话历史、调用记录和 trace ID；支持正向“有帮助”反馈 | 已验收 | [接口](../api/api-design.md) |
| V1-06 | V1 | 固定能力路由 | 区分简单交互、知识问答、业务动作；简单交互仅固定白名单回复 | 已验收 | [背景](project-context.md) |
| V1-07 | V1 | Embedding 索引治理 | 记录索引配置与状态，防止不兼容或过期索引被当作有效证据 | 已验收 | [数据模型](../data/data-model.md)、[V3 验收](../evaluation/v3-acceptance-report.md) |
| V2-01 | V2 | 对话创建知识库 | 明确意图生成 `CREATE_KNOWLEDGE_BASE` 提案，用户另行确认后执行 | 已验收 | [V2 验收](../evaluation/v2-acceptance-report.md) |
| V2-02 | V2 | 可恢复操作卡片 | 展示待确认、成功、失败、拒绝、过期等状态；刷新后可恢复 | 已验收 | [V2 验收](../evaluation/v2-acceptance-report.md) |
| V2-03 | V2 | 原子幂等动作执行 | 持久化状态机、参数校验、静态分派，重复确认不重复产生副作用 | 已验收 | [V2 验收](../evaluation/v2-acceptance-report.md) |
| V3-01 | V3 | 知识库健康检查 | 检查文档处理及索引健康情况，提供维护入口 | 已验收 | [V3 验收](../evaluation/v3-acceptance-report.md) |
| V3-02 | V3 | 失败文档重试 | `RETRY_DOCUMENT_PROCESSING` 经提案和独立确认后执行 | 已验收 | [V3 设计](../architecture/v3-knowledge-base-maintenance-design.md)、[验收](../evaluation/v3-acceptance-report.md) |
| V3-03 | V3 | 索引重建 | `REINDEX_DOCUMENT` 修复缺失或过期索引，复用确认与审计机制 | 已验收 | [V3 验收](../evaluation/v3-acceptance-report.md) |
| V4-01 | V4 | 多文档比对 | 显式选择 2–5 份文档，按确定性维度和逐文档检索完成比对 | 已验收 | [V4 设计](../architecture/v4-knowledge-research-design.md)、[验收](../evaluation/v4-implementation-acceptance-report.md) |
| V4-02 | V4 | 可追溯研究结果 | 以受限证据生成综合结论和引用，暴露资料缺口 | 已验收 | [V4 验收](../evaluation/v4-implementation-acceptance-report.md) |
| V4-03 | V4 | 有界研究执行 | 有界并行检索；一次模型综合、90 秒硬超时、1800 字正文上限和 usage 记录 | 已验收 | [V4 设计](../architecture/v4-knowledge-research-design.md) |
| V5-01 | V5 | 显式偏好记忆 | `CREATE_USER_MEMORY` 只在用户确认后保存全局回答偏好，不自动提取聊天或文档 | 已验收 | [V5 设计](../architecture/v5-explicit-memory-design.md)、[验收](../evaluation/v5-acceptance-report.md) |
| V5-02 | V5 | 记忆管理 | 用户通过专用入口更新、启停、设置过期和逻辑删除；版本化处理更新 | 已验收 | [V5 验收](../evaluation/v5-acceptance-report.md) |
| V5-03 | V5 | 受限个性化回答 | 普通 RAG 与 V4 综合最多注入 10 条／2000 字活动偏好，仅影响呈现 | 已验收 | [V5 设计](../architecture/v5-explicit-memory-design.md) |
| V5-04 | V5 | 记忆透明与降级 | 单次忽略、SSE 提示、历史 revision 追溯；读取失败安全降级 | 已验收 | [V5 验收](../evaluation/v5-acceptance-report.md) |

## 升级研究：已实现，未通过最终质量验收

以下编号和实现保留；质量收尾暂停。初次结果见[实现报告](../evaluation/v6-implementation-review.md)，采用 Prompt 和调试过程见[范围调试](../evaluation/upgrade-scope-debug.md)，最新独立运行见[独立收尾结果](../evaluation/upgrade-closeout-independent-results.md)。不要把较早失败数当作当前完整质量成绩，也不要只凭最近终态匹配认定修复完成。

| 编号 | 功能 | 当前实现范围 | 验收状态 | 保留的质量检查范围（收尾暂停） |
| --- | --- | --- | --- | --- |
| V6-01 | 升级影响研究 | 结合 1–3 份选定文档和固定 Spring AI 仓库的 1–3 个指定发布标签生成迁移草稿 | 已实现／待验收 | 研究范围、缺口判断与事实完整性 |
| V6-02 | 原生模型工具调用 | 三个静态只读工具：选定文档检索、证据读取、发布记录读取 | 已实现／待验收 | 真实调用失败归因及稳定性 |
| V6-03 | 工具执行边界 | 整批参数预校验、固定来源、缓存、预算、来源快照与账本 | 已实现／待验收 | 生命周期合同逐项证据映射 |
| V6-04 | 运行管理 | 幂等启动、1 执行槽 + 2 排队槽、取消、超时、重启收敛、历史及逻辑删除 | 已实现／待验收 | 独立验收集与完整故障覆盖 |
| V6-05 | 报告确认保存 | 用户主动发起 `SAVE_RESEARCH_REPORT` 提案，独立确认；报告与动作成功同事务写入 | 已实现／待验收 | 结合最终研究质量完成端到端验收 |
| V6-06 | 研究工作区 | 研究页面、SSE 快照、刷新恢复待确认提案、报告列表及详情；已检查桌面和窄屏 | 已实现／待验收 | 真实生产代表场景验收 |

## 其他已实现的研究能力（旧场景冻结）

| 能力 | 现有范围 | 合同与验证 |
| --- | --- | --- |
| 研究任务工作台 | 选定文档内有限规划、计划预览、澄清/补充、SEARCH_DOCUMENTS 步骤、引用综合、暂停/取消/检查点恢复和历史；不执行任意工具 | [设计](../architecture/research-task-workspace-design.md)、[验证](../evaluation/research-task-workspace-verification.md) |
| 项目资料接入 | 固定 Spring AI 仓库 TXT/Markdown 经专用 MCP 生成不可变预览，用户确认后导入；记录 commit/路径/哈希 | [设计](../architecture/project-source-integration-design.md)、[验证](../evaluation/project-source-integration-verification.md) |
| 研究成果审查 | 已完成任务的不可变快照、固定一次模型证据审查、历史/取消及用户编辑补充；无多 Agent、自动修订或保存 | [设计](../architecture/research-result-review-design.md)、[验证](../evaluation/research-result-review-verification.md) |

## 已完成采集与仍未完成事项

| 项目 | 当前事实 | 依据 |
| --- | --- | --- |
| 统一核心任务 | 50 项首次实跑已完成；拒答/PARTIAL 状态已修正并有独立调试；人工语义评分及修复后的最终独立验收未完成 | [核心结果](../evaluation/core-workspace-first-run.md) |
| 升级生命周期 | 22 项合同已有映射和补充专项；工程覆盖不等于模型质量通过 | [证据映射](../evaluation/upgrade-lifecycle-evidence.md) |
| Prompt 与 HTTP 超时修正 | 生产登记为 upgrade-research-v6，显式读取上限保持既有预算；有专项回归，非当前全量验证 | [调试与采用](../evaluation/upgrade-scope-debug.md) |
| 新独立采集 | DEBUG 4 项、TEST 24 项、代表场景 8×2 已采集；终态匹配 3/4、18/24、15/16，均不是语义成绩；11 次草稿经确认保存 | [最新结果](../evaluation/upgrade-closeout-independent-results.md) |
| 工程及环境检查 | 已有全量回归、同机干净源码/新依赖缓存/新数据库离线复跑及依赖审计快照；不代表全新机器验证或本次代码复验 | [回归](../evaluation/closeout-engineering-regression.md)、[复跑](../evaluation/clean-workspace-replay.md)、[依赖](../evaluation/frontend-dependency-closeout.md) |
| 人工复核与最终启用 | 用户暂缓人工复核，未生成语义成绩；升级研究继续默认关闭，最终验收与启用决定未完成，现暂停 | [最新结果](../evaluation/upgrade-closeout-independent-results.md) |
| 录屏 | 仅有脚本，界面自动化中止，未生成视频；现暂停 | [脚本](closeout-demo-script.md)、[最新结果](../evaluation/upgrade-closeout-independent-results.md) |

本次整理修正了旧清单将已采集的新测试和代表运行继续列作待办的问题；没有修改原始结论、评分或历史数据。过程数字保留在报告中，不重复维护多套时间线。

## 对外表述与维护规则

现有成果仍可介绍 RAG 全链路、确定性多文档比对、确认/幂等动作、显式偏好和有限任务执行，依据上表选择。升级研究只能称为质量验收未通过且暂停收尾的原型。小说基础阅读可按新验证记录描述；阅读 AI、完整防剧透与真实用户体验成绩仍不能作为完成经历。

当前没有登录、多用户隔离、通用工具、自动事实记忆、OCR、混合检索/Rerank 或多 Agent。专用 MCP 已实现，不能笼统说“未实现 MCP”，也不能扩称支持任意来源。量化结论须附日期、范围与分母，不将工程测试数当业务任务数或终态匹配当准确率。

新增/改动能力时同步本表、对应合同与验证报告；保持固定编号和原始证据。状态、默认开关和输入范围据实际代码与报告更新。下一步只按[新开发路线](novel-reading-roadmap.md)与[当前交接](continuation-handoff.md)；产品需求维护目标与范围。
