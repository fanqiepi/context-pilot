# 研序 文档导航

更新：2026-10-20。当前进入小说阅读产品设计；新路线已建立，旧研究路线及转向初稿已弃用。按下面顺序阅读，历史过程不再充当当前待办。

## 当前工作入口

| 文档 | 唯一职责 |
| --- | --- |
| [项目背景](development/project-context.md) | 新会话入口：当前方向、实现状态摘要与边界 |
| [长篇小说阅读应用开发路线](development/novel-reading-roadmap.md) | 唯一开发路线：实施顺序、依赖、复用、预算、验收与旧路线处置 |
| [产品需求](requirements/product-requirements.md) | 目标用户、需求与范围、产品参考；不维护第二份路线 |
| [小说阅读开发交接](development/continuation-handoff.md) | 下一次从哪里继续，以及可直接复制的新窗口提示 |
| [阅读业务合同](architecture/novel-reading-design.md) | `reading-contract-v1-draft1` 已获批准；页面/范围/编排/失败语义，实施状态另列 |
| [阅读数据](data/novel-reading-data-model.md) / [阅读 API](api/novel-reading-api.md) / [阅读评估](evaluation/novel-reading-evaluation.md) | 已批准合同的字段、接口与验证方法；数值预算和门槛引用唯一新路线 |
| [基础阅读验证记录](evaluation/reading-library-verification.md) | 首个大功能点的实际工程证据、范围与限制 |
| [续读体验验证](evaluation/reading-experience-verification.md) / [演示与试用脚本](development/reading-demo.md) | 冻结评测准备、离线容量/页面证据及尚未完成的真实验收 |

工作规则见 [AGENTS.md](../AGENTS.md)。详细合同已批准；每个会话只完成一个大功能点，验证后提醒用户提交 PR，下一窗口再继续。不要把未实施的 AI 规划写成已交付。

## 既有实现：按需查阅

| 用途 | 入口 |
| --- | --- |
| 当前能力、验收事实及质量缺口 | [功能清单](development/feature-inventory.md) |
| 运行与排错 | [本地开发](development/local-development.md)、[项目资料接入](development/project-source-integration.md)、[成果审查](development/research-result-review.md) |
| 总体设计 | [技术架构](architecture/technical-architecture.md)、[数据模型](data/data-model.md)、[API / SSE](api/api-design.md) |
| 已验收能力合同 | [知识库维护](architecture/v3-knowledge-base-maintenance-design.md)、[多文档比对](architecture/v4-knowledge-research-design.md)、[显式偏好](architecture/v5-explicit-memory-design.md) |
| 研究能力合同（旧场景冻结） | [升级研究](architecture/v6-upgrade-research-design.md)、[任务工作台](architecture/research-task-workspace-design.md)、[项目接入](architecture/project-source-integration-design.md)、[成果审查](architecture/research-result-review-design.md) |
| 重要取舍 | [pgvector 决策](architecture/decisions/0001-use-pgvector.md)、[隐式主体决策](architecture/decisions/0002-use-implicit-single-user-subject.md)、[技术复盘](development/technical-retrospective.md) |

## 评估与验收

- [评估计划](evaluation/evaluation-plan.md)定义既有合同的评价方法；[执行入口](../evals/README.md)提供可复现资产。相关代码变更仍需必要回归，旧专项收尾暂停不代表通过。
- 已验收：[V2](evaluation/v2-acceptance-report.md)、[V3](evaluation/v3-acceptance-report.md)、[V4](evaluation/v4-implementation-acceptance-report.md)、[V5](evaluation/v5-acceptance-report.md)。
- 首版工程/代表流程：[工作台](evaluation/research-task-workspace-verification.md)、[项目接入](evaluation/project-source-integration-verification.md)、[成果审查](evaluation/research-result-review-verification.md)。
- 尚未完成质量验收：[升级研究初次结果](evaluation/v6-implementation-review.md)、[最新独立采集](evaluation/upgrade-closeout-independent-results.md)、[核心任务首次实跑](evaluation/core-workspace-first-run.md)。
- 专项过程按需查阅：[生命周期证据](evaluation/upgrade-lifecycle-evidence.md)、[范围调试](evaluation/upgrade-scope-debug.md)、[工程回归](evaluation/closeout-engineering-regression.md)、[干净工作区复跑](evaluation/clean-workspace-replay.md)、[依赖检查](evaluation/frontend-dependency-closeout.md)、[密钥扫描误报](evaluation/secret-scan-false-positive.md)。

## 历史档案与维护

[档案索引](archive/README.md)保存旧研究路线、旧收尾交接和早期职业评估；[旧录制脚本](development/closeout-demo-script.md)仅供恢复时参考。历史设计、验收报告、ADR 和原始数据保留路径，不为减少文件数量而破坏证据链。

需求只维护在 `requirements/`，开发顺序只维护在 `development/novel-reading-roadmap.md`；实现合同在 `architecture/`、`data/`、`api/`；操作指南和当前索引在 `development/`；结论在 `evaluation/`，可执行资产及原始结果在 `evals/`。概览用链接引用，不复制执行日志或测试数字。数据库结构核对 Flyway，接口核对代码/OpenAPI；未跟踪的本地笔记不是开发前提。
