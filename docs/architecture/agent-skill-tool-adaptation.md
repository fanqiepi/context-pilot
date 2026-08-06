# Agent Skill 与工具调用时序图适配评估

> 评估对象：包含聊天页面、ChatController、ChatApplicationService、SkillRouter/Registry、LLM、ToolExecutionGateway、QueryTool、DataPort 和数据库的参考时序图；其中数据库在 ContextPilot 中映射为项目自己的 PostgreSQL。
>
> 结论：该图的可靠性思想与 ContextPilot 高度相关，但完整组件结构与当前 MVP 仅部分适配。MVP 应吸收上下文校验、澄清、证据校验、引用和 trace；Skill、通用工具网关及模型驱动的结构化查询放入后续受控演进。

## 总体适配度

- 业务目标适配度：高。自然语言问答、上下文、来源和可追踪结果与知识库助手一致。
- MVP 直接实现适配度：中。聊天编排、LLM 边界和结果校验可直接采用，Agent/Skill/Tool 主链路不能直接照搬。
- 后续扩展适配度：高。作为固定能力路由和白名单工具调用的参考结构具有价值。
- 数据源适配度：高。图中的数据库可直接映射为 ContextPilot 的 PostgreSQL 业务表和 pgvector，但模型不能绕过现有业务边界直接查询。

## 组件映射

| 参考组件或步骤 | 适配度 | MVP 决策 | 后续决策 |
| --- | --- | --- | --- |
| 独立聊天页面 | 高 | 建设 Vue 聊天页、历史消息和引用展示 | 可增加能力选择和工具执行状态 |
| `ChatController` | 高 | 使用现有 `/api/chat/stream`，负责校验、SSE 协议和 request/trace ID | 不因增加工具而另起不兼容的 `/api/v1/chat` |
| `ChatApplicationService` | 高 | 编排会话、检索、证据校验、模型调用和持久化 | 后续仍是总入口，避免控制器直接驱动工具 |
| 会话上下文 | 高 | 保存 conversation/message 和选定 knowledgeBase；不保存 `activeSkill` | 阶段 A 可保存显式能力 ID 和版本 |
| 候选 Skill 匹配 | 低 | 不实现；MVP 只有知识库 RAG 主流程 | 有两个以上稳定能力后引入固定能力路由 |
| `SkillDefinition`/版本 | 低 | 提示词和评估配置先作为版本化资产 | 阶段 A 定义输入、输出、数据源和允许工具 |
| `LlmClient` | 高 | 通过 Spring AI `ChatModel` 或小型应用边界调用 DeepSeek | 工具调用仍不得绕过应用网关 |
| 模型返回澄清问题 | 中 | 先用固定规则处理缺少知识库、空问题和证据不足；模型可生成受控文案 | 能力参数缺失时由 Schema 和路由器生成结构化澄清 |
| Tool Call | 低 | 不进入 MVP，检索是应用内部服务调用而非模型工具调用 | 阶段 B 仅允许白名单、强类型、可审计工具 |
| `ToolExecutionGateway` | 低 | 不创建空抽象 | 首个真实工具出现时统一权限、校验、超时、次数、错误和审计 |
| `QueryTool` | 低 | pgvector 检索保持 `RetrievalService`，不包装成通用工具 | 阶段 C 只提供领域化只读查询，不开放任意 SQL |
| `DataPort` | 高 | 当前由 Repository/Mapper/VectorStore 承担内部数据端口职责 | 工具查询项目数据时增加专用只读领域端口 |
| 项目 PostgreSQL | 高 | 继续保存业务数据和向量，不新增数据库 | 只能通过领域查询服务或只读视图提供受控数据 |
| 参数化只读 SQL | 高 | 现有业务查询继续使用参数绑定 | 工具查询必须参数化、限行、超时且禁止模型生成 SQL |
| `ToolResult` 来源/时间/质量 | 高 | 映射为 RAG 检索上下文和 citation；保留文档、页码、分段和分数 | 外部结果增加 `dataAsOf`、质量标记和来源系统 |
| `ResultValidator` | 高 | 实现证据存在性、知识库隔离、引用支持和无依据拒答 | 后续增加每个工具的结果合同与质量校验 |
| `AnswerComposer` | 高 | 使用版本化提示词组装问题、检索证据和引用约束 | 多来源回答必须保留来源边界，禁止伪造引用 |
| trace ID | 高 | 贯穿 HTTP、SSE、message 和 model_call | 后续贯穿每次路由、工具执行和结构化查询 |

## MVP 应采用的简化时序

```text
用户
  -> Vue 独立聊天页面
  -> ChatController：校验请求、建立 request/trace ID
  -> ChatApplicationService：读取会话和 knowledgeBase
  -> RetrievalService：按 knowledgeBase 强制隔离检索
  -> EvidenceValidator：判断证据是否足够，必要时澄清或拒答
  -> Prompt/AnswerComposer：组装问题、证据和引用约束
  -> Spring AI ChatModel：调用 DeepSeek
  -> SSE：message、delta、citation、usage、done/error
  -> PostgreSQL：保存消息、引用、模型调用状态和反馈
```

检索调用是后端确定性编排的一部分，不由模型选择是否调用，因此不需要 SkillRouter 或 ToolExecutionGateway。

## 后续受控时序

```text
ChatApplicationService
  -> CapabilityRouter：只从固定、版本化能力中匹配
  -> 缺少参数：返回结构化澄清
  -> LLM：只能从当前能力允许的工具 Schema 中提出调用
  -> ToolExecutionGateway：白名单、DTO/Schema、权限、次数、超时、trace
  -> DomainQueryTool
  -> DataPort
  -> ContextPilot PostgreSQL 只读业务数据
  -> ResultValidator
  -> AnswerComposer
```

## 关键差异与风险

1. 参考图把 Skill 作为主路由，但 ContextPilot 当前只有一个核心 RAG 场景，过早引入会形成空抽象。
2. 图中的数据库就是项目 PostgreSQL；工具不得绕过业务服务、逻辑删除和知识库隔离直接读取底层表。
3. 模型生成的工具参数不可信，必须经过强类型校验；模型不能生成或执行任意 SQL。
4. 自动重试只适用于明确幂等的只读调用，有副作用操作必须默认不重试并提供人工确认。
5. `traceId` 用于关联和诊断，不应被当作身份认证或权限依据。
6. 来源、`dataAsOf` 和质量字段必须由后端适配器产生，不能接受模型自报。
7. 后续扩展保持模块化单体；Skill 或工具数量本身不是拆分微服务、引入队列或工作流引擎的理由。

## 决策

- MVP：采用聊天编排、澄清、证据校验、引用、SSE 和 trace，不采用 Agent/Skill/Tool。
- MVP 后阶段 A：在真实多能力需求出现后评估固定能力路由。
- 阶段 B：首个必要工具出现后建设统一执行网关，不提前创建通用框架。
- 阶段 C：有稳定项目业务查询合同后增加领域化只读数据端口。
- 阶段 D：只有可观测数据证明固定编排不足时才评估 Agent 或工作流。

具体优先级和进入条件以 [产品需求与路线图](../requirements/product-requirements.md) 为准。
