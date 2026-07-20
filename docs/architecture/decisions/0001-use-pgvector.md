# ADR 0001：使用 PostgreSQL 和 pgvector 存储向量

- 状态：已接受
- 日期：2026-07-13

## 背景

MVP 同时需要保存知识库、文档、会话、调用记录和向量。项目规模较小，主要目标是学习完整的 RAG 工程闭环，而不是运维多个存储系统。

## 决策

使用 PostgreSQL 保存业务数据，并通过 pgvector 保存和检索 1024 维文档向量。业务表使用 MyBatis-Plus，向量表只通过 Spring AI `PgVectorStore` 操作。

向量表结构由 Flyway 管理，Spring AI 的自动建表保持关闭；距离度量使用 Cosine Distance，索引使用 HNSW。

## 结果

- 本地只需维护一个数据库和一套备份策略。
- 业务事务与向量数据可以共享 PostgreSQL 基础设施。
- 需要确保所有环境预先允许安装 `vector` 扩展。
- embedding 维度变化必须通过显式迁移和重新索引处理，MVP 期间固定为 1024。

## 暂不选择

- 独立向量数据库：增加部署、同步和学习成本，当前数据规模没有证明其必要性。
- Spring AI 自动建表：难以审查和追踪生产数据库结构变化。
