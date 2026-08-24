# ContextPilot 项目文档

- [新会话项目背景](development/project-context.md)
- [产品定位与大版本路线图](requirements/product-requirements.md)
- [技术架构](architecture/technical-architecture.md)
- [V3 知识库健康检查与维护助手详细设计](architecture/v3-knowledge-base-maintenance-design.md)
- [V4 知识研究助手详细设计（第一阶段已完成）](architecture/v4-knowledge-research-design.md)
- [V5 显式记忆的个人助手详细设计（第一阶段实施中，Slices 1-4 已完成）](architecture/v5-explicit-memory-design.md)
- [ADR 0001：使用 PostgreSQL 和 pgvector](architecture/decisions/0001-use-pgvector.md)
- [ADR 0002：V5 使用隐式单用户主体](architecture/decisions/0002-use-implicit-single-user-subject.md)
- [数据模型](data/data-model.md)
- [REST API、SSE 与操作确认约定](api/api-design.md)
- [ContextPilot 评估计划](evaluation/evaluation-plan.md)
- [V2 版本验收报告](evaluation/v2-acceptance-report.md)
- [V3 版本验收报告](evaluation/v3-acceptance-report.md)
- [V4 多文档比对生产实施验收报告](evaluation/v4-implementation-acceptance-report.md)
- [本地开发](development/local-development.md)

接口实现后的字段细节以 OpenAPI 为主要事实来源，已实施的数据库结构以 Flyway 迁移脚本为事实来源。
