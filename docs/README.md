# ContextPilot 文档导航

按用途查阅；当前状态看功能清单，未来范围看产品需求，验收结论看版本报告。

## 入门与日常开发

| 文档 | 用途 |
| --- | --- |
| [项目首页](../README.md) | 项目介绍与启动入口 |
| [项目背景](development/project-context.md) | 新会话必读：当前状态和开发边界 |
| [功能清单与简历素材](development/feature-inventory.md) | 固定编号的能力清单、证据、待办及对外表述 |
| [本地开发](development/local-development.md) | 环境、配置、启动、验证和日志排查 |
| [仓库规则](../AGENTS.md) | 开发约定、授权范围与安全要求 |

## 需求与设计

| 分类 | 文档 |
| --- | --- |
| 产品需求 | [产品定位与大版本路线](requirements/product-requirements.md)（唯一详细路线） |
| 总体架构 | [技术架构与数据流](architecture/technical-architecture.md) |
| 版本设计 | [V3 知识库维护](architecture/v3-knowledge-base-maintenance-design.md)、[V4 多文档比对](architecture/v4-knowledge-research-design.md)、[V5 显式记忆](architecture/v5-explicit-memory-design.md)、[V6 受控升级研究](architecture/v6-upgrade-research-design.md) |
| 架构决策 | [ADR 0001：PostgreSQL / pgvector](architecture/decisions/0001-use-pgvector.md)、[ADR 0002：隐式单用户主体](architecture/decisions/0002-use-implicit-single-user-subject.md) |
| 数据与接口 | [数据模型](data/data-model.md)、[REST / SSE / 操作确认](api/api-design.md) |

## 评估与验收

- [评估计划](evaluation/evaluation-plan.md)：指标、数据划分和验收口径。
- [评估执行入口](../evals/README.md)：数据、语料、运行方式和逐次报告；[统一基线工具](../evals/tools/README.md)。
- 已验收：[V2](evaluation/v2-acceptance-report.md)、[V3](evaluation/v3-acceptance-report.md)、[V4 第一阶段](evaluation/v4-implementation-acceptance-report.md)、[V5 第一阶段](evaluation/v5-acceptance-report.md)。
- 未通过验收：[V6 实现验证与剩余问题](evaluation/v6-implementation-review.md)。

## 历史档案

[档案索引](archive/README.md)收录已被后续决定取代的评估快照，保留原结论及日期，不作为当前任务清单。版本验收报告和 ADR 继续保留在各自目录，便于追溯。

## 维护分工

| 位置 | 只维护这些内容 |
| --- | --- |
| `requirements/` | 场景、范围、版本路线与进入／退出条件 |
| `architecture/`、`data/`、`api/` | 实现合同、决策、数据和接口语义 |
| `development/` | 当前背景、功能索引和操作指南 |
| `evaluation/` | 验收口径、版本结论和未关闭问题 |
| `../evals/` | 可执行评估资产、原始结果及逐次证据 |
| `archive/` | 带日期的历史规划或审阅快照 |

概览文档用摘要和链接引用详细合同，不重复复制版本过程或测试数字。修改行为时同步其所属设计及功能清单；历史证据不改写为当前结果。接口字段需核对 OpenAPI，已实施数据库结构需核对 Flyway；出现冲突应记录并明确处理，不能静默选择。根目录被 Git 忽略的早期本地规划笔记不属于正式文档，也不是构建或开发前提。
