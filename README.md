# 研序｜技术研究 Agent

基于 Spring AI，围绕项目资料提供知识问答、可追溯研究与经确认的业务操作，采用 Java / Vue 前后端分离的模块化单体架构。

项目展示名称为“研序”（原 ContextPilot）；仓库、Java 包名、数据库及历史证据沿用原技术标识。

## 产品方向（2026-10-20）

当前按长篇小说阅读路线推进，首个场景为“中断后快速接着读”。**基础阅读和已读范围已实现；本轮新增回顾、人物查证、专用索引与有界任务的离线工程链路，待提交 PR。真实 AI 适配及语义验收尚未开放，AI 笔记确认保存仍待下一能力。** 新产品名称未定。详见[任务工程验证记录](docs/evaluation/reading-task-verification.md)。

旧研究路线及转向初稿已弃用，升级研究未完成的质量收尾和旧录屏仍暂停；代码、历史证据与必要回归保留。唯一当前路线为[长篇小说阅读应用开发路线](docs/development/novel-reading-roadmap.md)，新窗口从[当前交接](docs/development/continuation-handoff.md)继续。

## 当前能力

- **知识问答与受控维护已验收**：文档摄取、知识库隔离检索、引用与拒答、SSE、历史与反馈；经确认创建知识库、重试文档和重建索引。
- **多文档比对已验收**：2–5 份文档比对、确定性多步骤检索、证据追溯和有界执行。
- **回答偏好记忆已验收**：经确认保存回答偏好、版本化管理、受限注入、单次忽略和历史追溯。
- **升级影响研究已实现，质量验收未通过**：受控升级研究与报告确认保存；新建研究默认关闭，见[实现记录](docs/evaluation/v6-implementation-review.md)。
- **研究任务工作台已实现**：目标输入、有限模型规划、计划预览、澄清补充、逐步检索、暂停恢复、轮次历史和带引用结果；见[验证记录](docs/evaluation/research-task-workspace-verification.md)。
- **项目资料接入与成果审查已有首版验证**：固定 Spring AI 仓库资料经专用只读 MCP 预览和确认导入；任务结果可进行一次独立模型证据审查。不代表全面语义验收，详见[功能清单](docs/development/feature-inventory.md)。

逐项状态、证据与简历素材见[功能清单](docs/development/feature-inventory.md)。产品目标与范围见[产品需求](docs/requirements/product-requirements.md)，实施顺序与完成条件只在[新开发路线](docs/development/novel-reading-roadmap.md)维护。

当前没有登录认证与多用户隔离，仅适用于本机或可信网络。业务动作必须经过持久化提案、独立确认和幂等执行；V4 保持确定性编排，V6 的三个只读工具仅用于固定范围的升级研究。

## 本地运行

需要 Java 21、Node.js 22.12+ 和 Docker Compose；后端使用仓库自带 Maven Wrapper。

1. 在仓库根目录复制 `.env.example` 为 `.env`，已有文件则保留；配置数据库密码后执行 `docker compose up -d`。
2. 在后端终端设置对应 `POSTGRES_PASSWORD`；Spring Boot 不自动读取根目录 `.env`。
3. 从 `backend/` 执行 `.\mvnw.cmd spring-boot:run`。
4. 在另一终端从 `frontend/` 执行 `npm ci` 和 `npm run dev`。

默认模型集成和文档自动处理关闭。无密钥演示使用 `offline` Profile，真实问答需配置 DeepSeek、DashScope 和 pgvector；完整步骤、JDK 切换、端口及验证命令见[本地开发](docs/development/local-development.md)。Unix 使用 `./mvnw`。

后端默认 `18080`，PostgreSQL 映射端口 `15432`；接口文档为 `http://localhost:18080/doc.html`。上传文件存于 Git 忽略的 `data/uploads/`；真实模式下文档片段会发送至模型服务，凭据只保存在后端。

## 文档入口

| 要做什么 | 入口 |
| --- | --- |
| 浏览分类文档 | [文档导航](docs/README.md) |
| 开始开发或继续会话 | [项目背景](docs/development/project-context.md)、[仓库规则](AGENTS.md)、[新开发路线](docs/development/novel-reading-roadmap.md) |
| 了解实现与数据流 | [技术架构](docs/architecture/technical-architecture.md) |
| 运行评估、查看证据 | [评估入口](evals/README.md)、[版本验收导航](docs/README.md#评估与验收) |
