# ContextPilot 项目背景

> 每次开始新的开发对话时，应先阅读本文，再结合当前代码、配置和 Git 状态开展工作。

## 项目定位

ContextPilot 是一个基于 Spring AI 的学习型、可控、可评估的个人知识工作助手。对用户而言，它以单用户知识库为工作空间，提供有依据的问答和安全的业务操作；对工程实践而言，它通过连续、可验收的真实场景逐步验证现代 Agent 能力，而不是以堆叠框架作为目标。

项目按大版本演进。V1 功能型 RAG 基线、V2 可控行动助手、V3“知识库健康检查与维护助手”和 V4 第一阶段“知识研究助手”均已完成。V5 第一阶段显式记忆范围与 Slices 1-7 已批准，Slices 1-6 已完成，当前只剩 Slice 7 的专项评估、真实模型与最终版本验收；V6 的外部工具互操作和更远期多 Agent 模式仍只是方向。远期路线和设计草案都不自动代表开发授权。

## 当前状态

- V1-V3 已完成 RAG、受控业务动作和知识库维护基线，均通过专项评估、全量回归及页面验收；详细范围与证据由对应设计和版本验收报告维护。
- V4 第一阶段已完成显式多文档比对、固定维度/逐文档确定性编排、有界并行、结构化日志和 DeepSeek/DashScope 生产验证。研究综合不发送请求级 `max_tokens`，仍强制单次模型调用、90 秒硬超时和 1800 字正文上限；证据见 `docs/evaluation/v4-implementation-acceptance-report.md`。
- V5 Slices 1-6 已完成固定评估、隐式主体与持久化、可确认的 `CREATE_USER_MEMORY`、版本化管理，以及普通 RAG/V4 综合的受限偏好注入、消息 revision 关联、单次忽略、SSE/历史透明度与读取失败安全降级。后端全量 372 项测试为 0 失败、0 错误、3 项既有条件跳过，前端类型检查/构建与 `/chat` 桌面、390×844 窄屏检查通过；分片证据见 `evals/reports/2026-08-24-v5-explicit-memory-slice1.md` 至 `slice6.md`。下一步只执行 Slice 7 最终验收。
- 日常开发和集成分支为 `develop`，不得直接提交到 `main`。

## 大版本路线

- **V1：知识库问答助手（已完成）**。完成文档摄取、隔离检索、引用、拒答、SSE、历史、反馈、固定能力路由和 Embedding 索引治理。
- **V2：可控行动助手（已完成）**。完整实现 `CREATE_KNOWLEDGE_BASE` 的强类型提案、持久化、确认/拒绝、过期、原子幂等执行、审计和可恢复前端卡片。
- **V3：知识库维护助手（已完成）**。面向“检查知识库健康状态并协助修复”场景，固定能力包括健康检查、失败文档重试和过期或缺失索引重建；完成范围和验收证据见 `docs/architecture/v3-knowledge-base-maintenance-design.md` 与 `docs/evaluation/v3-acceptance-report.md`。
- **V4：知识研究助手（第一阶段已完成）**。第一阶段只做显式多文档比对；Slices 1-7、固定维度/逐文档确定性编排、DeepSeek/DashScope 生产端到端验证和最终生成预算决定均已闭合，范围与证据见 `docs/architecture/v4-knowledge-research-design.md` 和 `docs/evaluation/v4-implementation-acceptance-report.md`。
- **V5：显式记忆的个人助手（第一阶段实施中，Slices 1-6 已完成）**。首版只交付隐式单用户下经确认的全局回答偏好、管理入口和透明受限注入；事实型记忆、自动提取和登录不在范围内。
- **V6：外部工具互操作（方向）**。只在明确场景驱动下评估 MCP 等协议；多 Agent 和工作流框架继续作为更远期探索项。

## 当前开发边界

- V1-V4 均为已完成基线。后续开发不得破坏 RAG 隔离、拒答、引用、SSE、历史、反馈、健康报告、人工确认及 V4 固定研究编排行为；更细的维护约束以对应版本设计为准。
- 路由先使用确定性规则：简单交互白名单优先，明确业务意图次之，其余安全回落到 `KNOWLEDGE_QA`。在没有歧义数据支撑前，不增加每次请求都调用模型的分类器。
- 业务操作必须先生成经过强类型校验并持久化的提案，再等待用户通过独立请求确认；实际工具不能在确认前执行，重复确认不能造成重复副作用。
- V2 已实现的创建知识库操作和 V3 获准新增的文档重试、索引重建操作都必须使用静态注册、强类型参数和显式分派。通用 Skill 注册表、通用工具网关、动态工具、任意 SQL、Shell、文件或 HTTP 工具仍未授权。
- 项目 PostgreSQL 业务数据仍只能通过领域服务、Mapper 或后续明确批准的只读 DataPort 访问，模型不能直接获得数据库执行能力。
- V5 Slices 1-7 已整体批准，Slices 1-6 已完成；仅剩 Slice 7 最终验收，并继续保留独立测试和验收证据。`LOCAL_OWNER` 不是登录或安全边界，客户端不得提交用户 ID；当前部署仍限定本机或可信网络。
- V5 第一阶段只允许 `CREATE_USER_MEMORY` 新动作和 `RESPONSE_PREFERENCE/GLOBAL` 记忆。普通聊天、文档或模型输出不得自动写入记忆；事实型记忆、任务记忆、向量记忆、登录、多用户和自动工具仍未授权。

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
