# 研序 项目背景

> 每次开始新的开发对话时先读本文，再核对代码、配置和 Git 状态。工作规则见 [AGENTS.md](../../AGENTS.md)。

## 定位与状态

任务和目标使用具体功能名称；下表版本号只用于关联历史合同与验收证据，新目标不再按版本号命名。2026-10-16 用户确认项目展示名称为“研序”，简历标题为“研序｜技术研究 Agent”。原名称 ContextPilot 继续用于既有仓库、包名、配置及历史证据等技术标识；版本化 Prompt 中的原内部身份标识不随展示名称变更。

研序 是基于 Spring AI 的单用户知识工作助手，通过有依据的问答、可追溯研究和经确认的业务操作练习 AI 应用工程。

| 功能 | 当前结论 | 详情 |
| --- | --- | --- |
| 知识问答、受控动作、知识库维护 | 已完成 | [功能清单](feature-inventory.md)、[维护验收](../evaluation/v3-acceptance-report.md) |
| 多文档比对 | 已验收 | [比对验收](../evaluation/v4-implementation-acceptance-report.md) |
| 回答偏好记忆 | 2026-10-15 已验收 | [记忆验收](../evaluation/v5-acceptance-report.md) |
| 升级影响研究 | 首版已实现但质量验收未通过；新建研究默认关闭，本地显式开启 | [设计](../architecture/v6-upgrade-research-design.md)、[实现记录](../evaluation/v6-implementation-review.md) |
| 研究任务工作台 | 已实现有限规划、检查点与补充继续；工程和公开样例验证见记录 | [设计](../architecture/research-task-workspace-design.md)、[验证](../evaluation/research-task-workspace-verification.md) |
| 项目资料接入 | 首版已交付，工程与公开流程验证通过 | [设计](../architecture/project-source-integration-design.md)、[验证](../evaluation/project-source-integration-verification.md) |
| 研究成果审查 | 首版已实现，工程与公开流程验证通过 | [设计](../architecture/research-result-review-design.md)、[验证](../evaluation/research-result-review-verification.md) |
| 检索优化 | 后续候选 | [唯一详细路线](../requirements/product-requirements.md) |

统一 50 项核心任务已冻结并于 2026-10-19 完成首次实跑；拒答／部分完成状态已修正，普通问答已完成独立资料调试，人工语义评分和最终独立验收仍待完成。升级研究正在独立调试结果范围问题，最终验收及展示交付尚未完成。待办和功能状态在[功能清单](feature-inventory.md)维护；测试次数及实际成绩以验收报告为准，不在本文重复登记。

2026-10-16 用户调整开发优先级：先推进有独立用户价值的大功能、形成整体架构和业务主线，再集中完善非阻断细节与优化。用户随后要求按技术路线开始开发，首项为研究任务工作台；其他候选不自动授权。保留必要安全／回归验证及升级影响研究的待验收状态，具体规则以[产品需求](../requirements/product-requirements.md)为准。

## 开发边界

- 保持 V1–V5 已验收行为：知识库隔离、证据与引用校验、拒答、SSE、历史、逻辑删除、trace 和原子幂等确认。
- 顶层路由保持 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION`。简单交互仅使用确定性白名单；其余默认安全回落到知识问答。
- 业务动作使用静态分派和强类型参数；先持久化提案，再由独立请求确认。模型不能确认或直接访问 SQL、Shell、任意文件或 URL。
- V4 保持确定性维度与逐文档编排：一次模型综合、90 秒硬超时、1800 字正文上限，不发送请求级 `max_tokens`。
- V5 只保存经确认的全局回答偏好，最多注入 10 条／2000 字符，仅影响呈现；禁止自动提取、改变证据或控制工具。
- `LOCAL_OWNER` 只提供持久归属，不是认证边界；客户端不能指定用户 ID，部署限本机或可信网络。
- 2026-10-15 已整体批准 V6 Slices 1–7，切片不是重复授权门。唯一工具循环例外为独立 `UPGRADE_IMPACT`、三个静态只读工具和固定 Spring AI 公开仓库；`SAVE_RESEARCH_REPORT` 仅由用户发起提案并独立确认。
- 通用 MCP、多 Agent、工作流引擎、混合检索／Rerank、自动记忆、登录与多租户仍未授权；专用只读 MCP 已在项目资料接入合同中批准；具体范围以已批准版本合同为准。
- 研究任务工作台另行授权有限模型计划与应用执行：仅检索用户选定文档，计划整体校验，执行令牌阻止过期结果写回；不扩展升级研究允许列表，不自动执行业务动作。合同见[工作台设计](../architecture/research-task-workspace-design.md)。

## 技术与工作入口

后端为 Java 21 / Spring Boot 4.1.x / Spring AI 2.0.x / MyBatis-Plus / Flyway；前端为 Vue 3 / TypeScript / Vite / Element Plus。存储使用 PostgreSQL 17 + pgvector（HNSW、余弦距离）；生成默认 DeepSeek `deepseek-v4-flash`，Embedding 为 DashScope `qwen3.7-text-embedding`、1024 维。

日常集成分支为 `develop`，不得直接提交 `main`。按业务能力组织模块，保留已有未提交修改；密钥、上传资料和敏感评估数据不得提交。

- [文档导航](../README.md)：按用途定位文档及历史档案。
- [技术架构](../architecture/technical-architecture.md)：模块职责、数据流和版本编排。
- [本地开发](local-development.md)：环境、配置、启动与验证命令。
- [收尾交接](closeout-handoff.md)：2026-10-19 当前工作树、最近修正与未完成验收项；继续工作前核对最新证据。
- [评估入口](../../evals/README.md)：冻结资产、运行方式和证据边界。
