# V3 知识库健康检查与维护助手版本验收报告

> 验收日期：2026-08-15（Asia/Shanghai）
>
> 审阅范围：`f5e9b80..a8d73e9` 及当前 Slice 8 评估资产
>
> 结论：通过；V3 正式完成

## 验收范围

本次验收覆盖 V3 八个切片已经交付的健康只读核心、不可变报告、确定性聊天路由、SSE `health_report`、报告查询、强类型动作核心、失败文档重试、索引重建、事务提交后可靠派发，以及 Slice 8 固定评估资产与回归入口。

实现保持 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION` 三类顶层能力；维护动作仍限定单文档、单提案、显式确认和静态分派，没有引入通用工具网关、动态工具、工作流引擎、消息队列或多 Agent。

## 自动化验证

使用 Microsoft OpenJDK 21.0.3、Docker Desktop 29.6.1、PostgreSQL 17.10 与 pgvector 0.8.2 执行：

| 验证项 | 结果 |
| --- | --- |
| `backend/.\mvnw.cmd test` | 245 项测试，0 失败，0 错误，2 项预期跳过，构建成功 |
| `backend/.\mvnw.cmd -Pv3-evaluation test` | 118 项测试，0 失败，0 错误，0 跳过，构建成功 |
| `frontend/npm run typecheck` | 通过 |
| `frontend/npm run build` | 通过；保留主包超过 500 kB 的非阻断性能提示 |

普通 Maven Profile 中的 2 项跳过为 V2/V3 环境门槛测试的预期行为；V3 专项 Profile 强制要求 Docker，并以 0 跳过完成真实 PostgreSQL/pgvector 生命周期验证。

固定基线见 `evals/reports/2026-08-15-v3-health-maintenance-baseline.md`：16 条路由案例、12 条健康分类/完整性/修复资格案例及 9 类生命周期覆盖全部达到阈值。

## 真实接口链路

使用独立 `offline` 后端和本地验收知识库构造 1 个失败 PDF 文档，验证结果如下：

- `POST /api/chat/stream` 按 `message → route → delta → health_report → done` 固定顺序返回。
- 路由为 `KNOWLEDGE_QA/v2 + EXPLICIT_KNOWLEDGE_BASE_HEALTH`，没有触发模型调用。
- 报告为 `ATTENTION_REQUIRED / COMPLETE`，包含 1 条 `DOCUMENT_PROCESSING_FAILED` 明细和可用的 `RETRY_DOCUMENT_PROCESSING` 建议。
- 报告 GET、可信 `reportId + issueId` 提案生成、动作 GET 与拒绝接口均通过；客户端没有提交或覆盖动作参数。
- 重新读取会话历史后，助手消息恢复原始健康报告快照，动作卡片恢复服务端 `REJECTED` 最终状态。

验收提案被明确拒绝，未执行文档重试副作用；验收数据随后按精确 ID 逻辑删除。

## 页面验收

用户在真实后端链路上完成页面目检并确认通过：

- 桌面视口正确展示健康概览、问题明细与“生成重试提案”按钮。
- 生成后正确展示动作卡片与取消状态，刷新后健康报告卡片和动作卡片均能恢复。
- 窄视口下无水平溢出，按钮和长文件名不会遮挡或截断关键状态。

页面结果与本报告记录的真实 SSE/API 合同一致，前端类型检查和生产构建同时通过。

## 版本结论

V3 核心正确性、安全边界、数据库迁移、真实生命周期、固定评估、全量回归、前端构建和真实页面交互均已通过。八个切片及版本完成标准全部闭合，因此 V3 自本报告起标记为正式完成。该结论不授权 V4；后续版本仍须满足路线图进入条件并单独批准范围。
