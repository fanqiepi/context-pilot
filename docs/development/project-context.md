# ContextPilot 项目背景

> 每次开始新的开发对话时先读本文，再核对代码、配置和 Git 状态。工作规则见 [AGENTS.md](../../AGENTS.md)。

## 定位与状态

ContextPilot 是基于 Spring AI 的单用户知识工作助手，通过有依据的问答、可追溯研究和经确认的业务操作练习 AI 应用工程。

| 版本 | 当前结论 | 详情 |
| --- | --- | --- |
| V1–V3 | 知识问答、可控动作、知识库维护已完成 | [功能清单](feature-inventory.md)、[V3 验收](../evaluation/v3-acceptance-report.md) |
| V4 第一阶段 | 确定性多文档比对已验收 | [V4 验收](../evaluation/v4-implementation-acceptance-report.md) |
| V5 第一阶段 | 显式回答偏好记忆于 2026-10-15 验收 | [V5 验收](../evaluation/v5-acceptance-report.md) |
| V6 | 已批准，首版已实现但验收未通过；新建研究默认关闭 | [批准设计](../architecture/v6-upgrade-research-design.md)、[实现记录](../evaluation/v6-implementation-review.md) |
| V7+ | 方向候选，未授权实施 | [唯一详细路线](../requirements/product-requirements.md) |

统一 50 项核心任务已冻结，完整执行、V6 最终验收及展示交付尚未完成。待办和功能状态在[功能清单](feature-inventory.md)维护；测试次数及实际成绩以验收报告为准，不在本文重复登记。

## 开发边界

- 保持 V1–V5 已验收行为：知识库隔离、证据与引用校验、拒答、SSE、历史、逻辑删除、trace 和原子幂等确认。
- 顶层路由保持 `SIMPLE_CHAT`、`KNOWLEDGE_QA`、`BUSINESS_ACTION`。简单交互仅使用确定性白名单；其余默认安全回落到知识问答。
- 业务动作使用静态分派和强类型参数；先持久化提案，再由独立请求确认。模型不能确认或直接访问 SQL、Shell、任意文件或 URL。
- V4 保持确定性维度与逐文档编排：一次模型综合、90 秒硬超时、1800 字正文上限，不发送请求级 `max_tokens`。
- V5 只保存经确认的全局回答偏好，最多注入 10 条／2000 字符，仅影响呈现；禁止自动提取、改变证据或控制工具。
- `LOCAL_OWNER` 只提供持久归属，不是认证边界；客户端不能指定用户 ID，部署限本机或可信网络。
- 2026-10-15 已整体批准 V6 Slices 1–7，切片不是重复授权门。唯一工具循环例外为独立 `UPGRADE_IMPACT`、三个静态只读工具和固定 Spring AI 公开仓库；`SAVE_RESEARCH_REPORT` 仅由用户发起提案并独立确认。
- MCP、多 Agent、工作流引擎、混合检索／Rerank、自动记忆、登录与多租户仍未授权；具体范围以已批准版本合同为准。

## 技术与工作入口

后端为 Java 21 / Spring Boot 4.1.x / Spring AI 2.0.x / MyBatis-Plus / Flyway；前端为 Vue 3 / TypeScript / Vite / Element Plus。存储使用 PostgreSQL 17 + pgvector（HNSW、余弦距离）；生成默认 DeepSeek `deepseek-v4-flash`，Embedding 为 DashScope `qwen3.7-text-embedding`、1024 维。

日常集成分支为 `develop`，不得直接提交 `main`。按业务能力组织模块，保留已有未提交修改；密钥、上传资料和敏感评估数据不得提交。

- [文档导航](../README.md)：按用途定位文档及历史档案。
- [技术架构](../architecture/technical-architecture.md)：模块职责、数据流和版本编排。
- [本地开发](local-development.md)：环境、配置、启动与验证命令。
- [评估入口](../../evals/README.md)：冻结资产、运行方式和证据边界。
