# V4 多文档比对生产实施验收报告

> 验收日期：2026-08-17（Asia/Shanghai）
>
> 生产基线：`document-comparison-fixed-v1`
>
> 结论：Slices 2-6 生产实现与 Slice 7 本地工程门禁通过；DeepSeek/DashScope 生产端到端调用本轮未重新执行，因此当前结论是“工程验收通过、外部提供商发布门待补”，不把 V4 描述为已完成外部发布验收。

## 实施范围

- 显式 `DOCUMENT_COMPARISON` 请求，选择 2 至 5 份同知识库、处理成功、当前索引兼容且具有向量的文档；普通聊天不自动触发研究运行。
- `DeterministicResearchPlanner` 使用版本化固定维度目录生成最多 4 个步骤，每一步逐文档检索；Planner 模型调用、结构修复、重规划和 Agent 循环均为 0。
- Flyway V15 创建 `research_run`、`research_step`、`research_evidence`、`research_step_evidence`，并把聊天引用关联到真实研究证据。
- 研究运行持久化幂等请求、计划、预算、步骤、证据、状态、取消、重启失败收敛和关联重试；数据库状态是 SSE 与历史恢复的事实来源。
- 有界研究执行器与独立有界 I/O 执行器落实逐文档检索、覆盖优先裁剪、单文档失败降级、90 秒硬截止、取消轮询、拒答、综合和引用范围校验。
- 综合提示词允许对真实已选文档使用固定的“本次检索未找到相关内容”说明，其他事实陈述仍须通过引用校验；研究综合调用最多输出 3000 Token，最终回答正文最多保留 1800 字符。
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

## 尚未计入通过的发布门

- Slice 1 已有 DeepSeek 代表性对照，但它使用评估侧确定性检索，不等于当前生产运行时的真实端到端调用。
- 本轮没有重新消费 DeepSeek/DashScope 额度，未执行“真实 DashScope Embedding + pgvector 检索 + 当前生产 Synthesizer + SSE”的完整链路。
- 阻塞调用硬截止已用缩短测试时限验证状态能及时收敛；未进行真实等待 90 秒的外部网络故障演练。

补齐上述外部提供商链路并记录模型、Embedding profile、Token、延迟、引用与 90 秒终态后，才可把本报告升级为 V4 完整发布验收通过。该发布门不影响当前生产代码、Flyway、API/SSE 与前端实现已经整体授权并完成本地工程验收的事实。
