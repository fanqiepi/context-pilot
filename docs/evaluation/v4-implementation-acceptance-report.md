# V4 多文档比对生产实施验收报告

> 验收日期：2026-08-17（Asia/Shanghai）
>
> 生产基线：`document-comparison-fixed-v1`
>
> 结论：Slices 2-6 生产实现、Slice 7 本地工程门禁与 2026-08-21 DeepSeek/DashScope 生产端到端验证均已通过。2026-08-24 已根据真实 Thinking Token 结果批准最终生成预算，V4 第一阶段完整发布验收通过。

## 实施范围

- 显式 `DOCUMENT_COMPARISON` 请求，选择 2 至 5 份同知识库、处理成功、当前索引兼容且具有向量的文档；普通聊天不自动触发研究运行。
- `DeterministicResearchPlanner` 使用版本化固定维度目录生成最多 4 个步骤，每一步逐文档检索；Planner 模型调用、结构修复、重规划和 Agent 循环均为 0。
- Flyway V15 创建 `research_run`、`research_step`、`research_evidence`、`research_step_evidence`，并把聊天引用关联到真实研究证据。
- 研究运行持久化幂等请求、计划、预算、步骤、证据、状态、取消、重启失败收敛和关联重试；数据库状态是 SSE 与历史恢复的事实来源。
- 有界研究执行器与独立有界 I/O 执行器落实逐文档检索、覆盖优先裁剪、单文档失败降级、90 秒硬截止、取消轮询、拒答、综合和引用范围校验。
- 综合提示词允许对真实已选文档使用固定的“本次检索未找到相关内容”说明，其他事实陈述仍须通过引用校验；研究综合不发送请求级 `max_tokens`，实际 usage 持续记录，最终回答正文最多保留 1800 字符。
- 正式聊天 API/SSE、运行查询/取消 OpenAPI、运行内 SSE 序号、Vue 显式入口、2-5 文档选择、计划/步骤卡片、取消与刷新轮询恢复均已实现。

## 自动化证据

| 验证项 | 结果 |
| --- | --- |
| `backend/.\\mvnw.cmd package` | 297 项通过，0 失败、0 错误、3 项条件跳过；可执行 JAR 打包成功 |
| `backend/.\\mvnw.cmd -Pv4-evaluation test` | 112 项，0 失败、0 错误、3 项条件跳过；V4 固定语义案例 35 项通过 |
| Flyway/Testcontainers | PostgreSQL 17 + pgvector 上从空库完整迁移至 V15；四张研究表、JSONB、引用外键和检查约束断言通过 |
| 研究专项 | 固定计划、资格、幂等冲突、原子取消、逐步骤×文档执行、局部检索失败、固定无证据表述、准确 PARTIAL 原因、研究输出 Token 限制、回答长度裁剪、无效引用删除和阻塞调用硬超时均有自动化覆盖 |
| OpenAPI | `/api/research-runs/{id}`、`/api/research-runs/{id}/cancel`、`ResearchRequest` 和 `ResearchRunResponse` schema 断言通过 |
| `frontend/npm run build` | TypeScript 与 Vite 生产构建通过；仅保留既有大 chunk 警告 |
| 后端打包 | Spring Boot 可执行 JAR 生成成功 |

## 页面验收

使用本地 PostgreSQL/Flyway V15、当前后端与 Vite 前端进行浏览器目检：

- 桌面宽度下研究模式开关、文档选择器、输入区与历史内容布局正常；
- 历史 PARTIAL 运行会按 `errorCode` 显示准确原因并同时展示原始错误码；模型 Markdown 经清洗后可正确渲染标题、列表和行内代码；
- 390×844 窄屏首次发现研究标签被选择器挤成竖排，已将窄屏研究控制区改为上下布局；
- 修正后重新加载并验证关闭与展开状态，标签、开关、文档选择器、输入区和发送按钮无水平溢出。

## 2026-08-21 外部提供商端到端补充

当前生产运行时已使用真实 DashScope `dashscope_qwen3_7_1024_v1` 完成文档入库与逐文档 pgvector 检索，并由 DeepSeek `deepseek-v4-flash` 完成研究综合。运行通过正式研究持久化与引用链路保存；本地数据库中的脱敏元数据提供以下证据：

| 结果 | 运行数 | 端到端耗时 | 检索调用 | 证据/引用 | DeepSeek 调用 | `completion_tokens` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `SUCCEEDED/ANSWERED` | 4 | 10.002-62.916 秒 | 4-10 | 4-14 / 4-14 | 每次 1 次 | 1257-9025 |
| `PARTIAL/RESEARCH_TIMEOUT` | 1 | 90.058 秒 | 10 | 14 / 14 | 1 次失败调用 | 无可用 usage |
| `PARTIAL/RESEARCH_ANSWER_BUDGET_REACHED` | 1 | 23.258 秒 | 10 | 14 / 14 | 1 次成功调用 | 3119 |

这些运行关闭了此前“真实 DashScope Embedding + pgvector 检索 + 当前生产 Synthesizer + SSE/持久化”的执行缺口，并真实验证运行会在约 90 秒收敛为稳定终态。早期诊断失败及后续成功运行均保留了状态、脱敏错误码、模型、Embedding profile、Token、延迟、证据和引用元数据。

## 生成预算确认与版本完成

真实结果确认 DeepSeek V4 默认 Thinking 模式会把推理 Token 计入 `completion_tokens`：保留 3000 Token 请求级 `max_tokens` 时曾出现空正文或 `RESEARCH_ANSWER_BUDGET_REACHED`，不发送该选项后连续获得成功回答，但成功运行的 `completion_tokens` 最高为 9025。

2026-08-24 正式批准当前“无请求级 `max_tokens` + 单次调用 + 90 秒硬超时 + 1800 字正文上限”组合，实际 Token usage 继续记录用于成本和稳定性评估。外部提供商链路、生成预算和所有工程门禁均已闭合，V4 第一阶段自本报告起标记为完整发布验收通过。该结论不重新开放 Slices 2-7，也不授权模型 Planner、Agent 循环或其他范围扩张。

## 2026-08-21 维护验证补充

- 研究日志调整为 `INFO` 记录步骤和运行汇总，逐文档及底层向量检索成功明细降为 `DEBUG`；汇总字段覆盖 `traceId`、`runId`、步骤、文档数、证据字符数、截断状态和总耗时。
- 步骤保持顺序执行，同一步骤的逐文档检索改为有界并行，默认并行度为 4、可配置范围为 1 至 5；异步完成结果仍按冻结文档顺序进入证据账本。
- 聚焦自动化覆盖并行重叠、逆序完成后的固定顺序持久化、阻塞超时实际调用计数，以及无效持久化计划下执行器仍不超过 20 次检索调用。
- `backend/.\mvnw.cmd test` 完整回归为 299 项通过、0 失败、0 错误、3 项条件跳过；最后补充取消复核后，`ResearchExecutorServiceTests` 6 项再次通过。
- 步骤日志生命周期进一步校正为 `research.step.started -> research.step.retrieval.completed` 紧邻配对；跨步骤证据去重、字符预算和截断状态只在 `research.run.completed` 汇总，避免下一步骤已开始后才输出上一条容易误解的 `step.completed`。
