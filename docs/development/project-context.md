# ContextPilot 项目背景

> 每次开始新的开发对话时，应先阅读本文，再结合当前代码、配置和 Git 状态开展工作。

## 项目定位

ContextPilot 是一个基于 Spring AI 的学习型、可控、可评估的个人知识工作助手。对用户而言，它以单用户知识库为工作空间，提供有依据的问答和安全的业务操作；对工程实践而言，它通过连续、可验收的真实场景逐步验证现代 Agent 能力，而不是以堆叠框架作为目标。

项目按大版本演进。V1 功能型 RAG 基线、V2 可控行动助手和 V3“知识库健康检查与维护助手”均已完成；V2 完成了首个需人工确认的 `CREATE_KNOWLEDGE_BASE` 操作，V3 完成了可恢复、可审计的健康检查与单文档维护闭环。V4 受限 Plan-and-Execute 第一阶段合同已确认，当前只授权 Slice 1 建立评估集和固定基线，生产实现仍未授权；显式记忆、MCP 等外部工具互操作和多 Agent 模式仍属于更远期方向。远期路线和设计草案都不自动代表开发授权。

## 当前状态

- V1 已完成知识库与文档管理、PostgreSQL/pgvector 隔离检索、grounded RAG、引用、拒答、SSE、历史、反馈、固定顶层路由和 Embedding profile 治理；离线与真实模型链路均已验证。
- V2 已完成 `CREATE_KNOWLEDGE_BASE` 的强类型提案、独立确认/拒绝、原子幂等执行、审计和可恢复操作卡片，并通过专项评估与页面验收。
- V3 已完成不可变健康报告、失败文档重试、异常索引重建、强类型动作分派和后台任务可靠派发，并通过固定评估、全量回归、真实接口和页面验收。
- 2026-08-17 V4 第一阶段合同已确认：只做显式 `DOCUMENT_COMPARISON`，选择 2 至 5 份合格文档，计划自动执行并可取消；最多 4 个顺序步骤、按文档检索、90 秒硬超时，使用四张研究表和可信证据引用，断线继续、重启失败收敛。当前已授权 Slice 1 建立公开或合成评估语料、现有单轮 RAG 基线、仅用于评估的候选编排原型和报告；Slice 2 及生产实现仍需依据评估证据另行授权。
- 日常开发和集成分支为 `develop`，不得直接提交到 `main`。

## 大版本路线

- **V1：知识库问答助手（已完成）**。完成文档摄取、隔离检索、引用、拒答、SSE、历史、反馈、固定能力路由和 Embedding 索引治理。
- **V2：可控行动助手（已完成）**。完整实现 `CREATE_KNOWLEDGE_BASE` 的强类型提案、持久化、确认/拒绝、过期、原子幂等执行、审计和可恢复前端卡片。
- **V3：知识库维护助手（已完成）**。面向“检查知识库健康状态并协助修复”场景，固定能力包括健康检查、失败文档重试和过期或缺失索引重建；完成范围和验收证据见 `docs/architecture/v3-knowledge-base-maintenance-design.md` 与 `docs/evaluation/v3-acceptance-report.md`。
- **V4：知识研究助手（Slice 1 评估已授权）**。第一阶段只做显式多文档比对；当前先建立评估集和固定基线，在 `KNOWLEDGE_QA` 内落地生产级受限 Plan-and-Execute 仍需后续切片授权，范围与进入条件见 `docs/architecture/v4-knowledge-research-design.md`。
- **V5：显式记忆的个人助手（方向）**。区分会话历史、任务状态和经用户控制的长期偏好。
- **V6：外部工具互操作（方向）**。只在明确场景驱动下评估 MCP 等协议；多 Agent 和工作流框架继续作为更远期探索项。

## 当前开发边界

- V1、V2 与 V3 均为已完成基线。任何维护或后续获批能力不得破坏已有 RAG、知识库隔离、拒答、引用、SSE、历史、反馈、健康报告和人工确认行为。
- 路由先使用确定性规则：简单交互白名单优先，明确业务意图次之，其余安全回落到 `KNOWLEDGE_QA`。在没有歧义数据支撑前，不增加每次请求都调用模型的分类器。
- 业务操作必须先生成经过强类型校验并持久化的提案，再等待用户通过独立请求确认；实际工具不能在确认前执行，重复确认不能造成重复副作用。
- V2 已实现的创建知识库操作和 V3 获准新增的文档重试、索引重建操作都必须使用静态注册、强类型参数和显式分派。通用 Skill 注册表、通用工具网关、动态工具、任意 SQL、Shell、文件或 HTTP 工具仍未授权。
- 项目 PostgreSQL 业务数据仍只能通过领域服务、Mapper 或后续明确批准的只读 DataPort 访问，模型不能直接获得数据库执行能力。
- V3 已按详细设计完成并通过验收；其单文档动作、不可变报告、实时资格复核、强类型静态分派和可靠派发边界继续有效，任何扩展都需要新版本授权。
- V4 当前授权详细设计第 18 节的 Slice 1：可以新增版本化评估语料、配置、测试或评估专用候选编排原型和脱敏报告，但不得把研究能力加入生产 `src/main`、Flyway、正式 API/SSE 或前端入口。Slice 2 及以后必须依据 Slice 1 证据逐片讨论、更新文档并授权；Agent 循环、LangGraph、MCP 和动态工具继续禁止。

## 技术栈

- 后端：Java 21、Spring Boot 4.1.x、Spring AI 2.0.x、Maven、MyBatis-Plus、Flyway。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router。
- 数据库：PostgreSQL 17 + pgvector，数据库名和用户名均为 `context_pilot`。
- 聊天模型：DeepSeek `deepseek-v4-flash`。
- Embedding：DashScope `qwen3.7-text-embedding`，固定 1024 维。
- 向量存储：Spring AI `PgVectorStore`，使用 `public.vector_store`、UUID、HNSW 和余弦距离。
- 接口文档：Knife4j Next 5.2.x 的 Spring Boot 4 OpenAPI3 starter。

## 开发入口

本地依赖、启动命令、端口、Profile 和模型密钥配置见 [本地开发](local-development.md)。具体默认值以当前应用配置为准；密钥、`.env`、私密文档、上传数据和敏感评估数据不得提交。
