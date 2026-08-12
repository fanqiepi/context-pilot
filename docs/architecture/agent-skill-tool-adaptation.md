# Agent Skill 与工具调用时序图适配评估

> 评估对象：包含聊天页面、ChatController、ChatApplicationService、SkillRouter/Registry、LLM、ToolExecutionGateway、QueryTool、DataPort 和数据库的参考时序图；其中数据库在 ContextPilot 中映射为项目自己的 PostgreSQL。
>
> 结论：该图的可靠性思想与 ContextPilot 高度相关，但完整组件结构不能直接照搬。功能型 MVP 已完成；下一阶段已授权固定能力路由和首个需人工确认的创建知识库操作，仍不采用 Agent、LangGraph、通用工具网关或模型驱动的结构化查询。

## 总体适配度

- 业务目标适配度：高。自然语言问答、上下文、来源和可追踪结果与知识库助手一致。
- MVP 直接实现适配度：中。聊天编排、LLM 边界和结果校验可直接采用，Agent/Skill/Tool 主链路不能直接照搬。
- 已授权扩展适配度：高。固定能力路由、持久化操作提案、人工确认和白名单业务调用可以采用其中的可靠性思想。
- 数据源适配度：高。图中的数据库可直接映射为 ContextPilot 的 PostgreSQL 业务表和 pgvector，但模型不能绕过现有业务边界直接查询。

## 组件映射

| 参考组件或步骤 | 适配度 | MVP 决策 | 后续决策 |
| --- | --- | --- | --- |
| 独立聊天页面 | 高 | 已建设 Vue 聊天页、历史消息、引用和反馈 | 下一阶段增加可恢复的操作确认卡片 |
| `ChatController` | 高 | 使用现有 `/api/chat/stream`，负责校验、SSE 协议和 request/trace ID | 不因增加工具而另起不兼容的 `/api/v1/chat` |
| `ChatApplicationService` | 高 | 编排会话、检索、证据校验、模型调用和持久化 | 仍是总入口，并调用固定 `CapabilityRouter`；控制器不能直接驱动操作 |
| 会话上下文 | 高 | 保存 conversation/message 和选定 knowledgeBase | 下一阶段为新消息记录能力 ID 和版本，并关联操作提案 |
| 候选 Skill 匹配 | 中 | MVP 未实现 | 下一阶段只实现三个固定能力，不建设动态 Skill 匹配或注册表 |
| `SkillDefinition`/版本 | 中 | 提示词和评估配置已作为版本化资产 | 使用代码内固定能力合同，包含输入、输出、数据源和是否允许业务操作 |
| `LlmClient` | 高 | 通过 Spring AI `ChatModel` 或小型应用边界调用 DeepSeek | 工具调用仍不得绕过应用网关 |
| 模型返回澄清问题 | 中 | 已用固定规则处理缺少知识库、空问题和证据不足 | 业务参数缺失或歧义时由能力合同返回结构化澄清，不猜测关键参数 |
| Tool Call | 中 | 不属于 MVP | 首个操作只允许静态 `CREATE_KNOWLEDGE_BASE`，确认后调用现有业务服务 |
| `ToolExecutionGateway` | 低 | MVP 未创建 | 不建设通用网关；首个操作仅实现确认、幂等、状态和审计所需的最小执行边界 |
| `QueryTool` | 低 | pgvector 检索保持 `RetrievalService`，不包装成通用工具 | 阶段 C 只提供领域化只读查询，不开放任意 SQL |
| `DataPort` | 高 | 当前由 Repository/Mapper/VectorStore 承担内部数据端口职责 | 工具查询项目数据时增加专用只读领域端口 |
| 项目 PostgreSQL | 高 | 继续保存业务数据和向量，不新增数据库 | 只能通过领域查询服务或只读视图提供受控数据 |
| 参数化只读 SQL | 高 | 现有业务查询继续使用参数绑定 | 工具查询必须参数化、限行、超时且禁止模型生成 SQL |
| `ToolResult` 来源/时间/质量 | 高 | 映射为 RAG 检索上下文和 citation；保留文档、页码、分段和分数 | 外部结果增加 `dataAsOf`、质量标记和来源系统 |
| `ResultValidator` | 高 | 实现证据存在性、知识库隔离、引用支持和无依据拒答 | 后续增加每个工具的结果合同与质量校验 |
| `AnswerComposer` | 高 | 使用版本化提示词组装问题、检索证据和引用约束 | 多来源回答必须保留来源边界，禁止伪造引用 |
| trace ID | 高 | 贯穿 HTTP、SSE、message 和 model_call | 下一阶段贯穿路由、操作提案、确认和执行结果 |

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

## 已授权的下一阶段时序

```text
ChatApplicationService
  -> CapabilityRouter：只从 SIMPLE_CHAT、KNOWLEDGE_QA、BUSINESS_ACTION 中匹配
  -> SIMPLE_CHAT：固定回答
  -> KNOWLEDGE_QA：复用现有检索、验证、模型和引用链路
  -> BUSINESS_ACTION：解析并强类型校验 CREATE_KNOWLEDGE_BASE 参数
     -> 缺少或歧义参数：返回澄清
     -> 保存 PENDING_CONFIRMATION 操作提案
     -> SSE 返回 action_required 并结束本次流

用户独立确认
  -> ActionConfirmationService：原子取得执行权
  -> 静态 CreateKnowledgeBaseAction
  -> KnowledgeBaseService
  -> 保存 SUCCEEDED/FAILED 和安全结果摘要
  -> 前端操作卡片恢复最终状态
```

这里的“调用工具”是确认后的应用服务调用。模型或路由器只能提出候选操作，不能代替用户确认，也不能在 `PENDING_CONFIRMATION` 之前执行任何副作用。

## 关键差异与风险

1. 参考图把 Skill 作为动态主路由，但 ContextPilot 当前只需要三个固定能力；实现动态注册表会形成无用抽象。
2. 图中的数据库就是项目 PostgreSQL；工具不得绕过业务服务、逻辑删除和知识库隔离直接读取底层表。
3. 模型生成的工具参数不可信，必须经过强类型校验；模型不能生成或执行任意 SQL。
4. 有副作用操作不能在确认前执行，也不能自动重试；确认必须通过原子状态迁移实现幂等，避免并发重复执行。
5. `traceId` 用于关联和诊断，不应被当作身份认证或权限依据。
6. 来源、`dataAsOf` 和质量字段必须由后端适配器产生，不能接受模型自报。
7. 后续扩展保持模块化单体；能力或操作数量本身不是拆分微服务、引入队列、LangGraph 或其他工作流引擎的理由。

## 决策

- MVP：采用聊天编排、澄清、证据校验、引用、SSE 和 trace，不采用 Agent/Skill/Tool。
- 下一阶段 A：固定能力路由已授权，能力集合限定为 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION`。
- 下一阶段 B：首个 `CREATE_KNOWLEDGE_BASE` 操作已授权；只建设该操作所需的提案、人工确认、幂等和审计，不建设通用工具平台。
- 阶段 C：有稳定项目业务查询合同后增加领域化只读数据端口。
- 阶段 D：只有可观测数据证明固定编排不足时才评估 Agent 或工作流；LangGraph 当前不引入。

具体优先级和进入条件以 [产品需求与路线图](../requirements/product-requirements.md) 为准。
