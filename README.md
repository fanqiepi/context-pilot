# ContextPilot

基于 Spring AI 的可追溯知识研究与受控行动助手，采用 Java / Vue 前后端分离的模块化单体架构。

## 当前能力

- **V1–V3 已验收**：文档摄取、知识库隔离检索、引用与拒答、SSE、历史与反馈；经确认创建知识库、重试文档和重建索引。
- **V4 第一阶段已验收**：2–5 份文档比对、确定性多步骤检索、证据追溯和有界执行。
- **V5 第一阶段已验收**：经确认保存回答偏好、版本化管理、受限注入、单次忽略和历史追溯。
- **V6 首版已实现，验收未通过**：受控升级研究与报告确认保存；新建研究默认关闭，见[实现记录](docs/evaluation/v6-implementation-review.md)。

逐项状态、证据与简历素材见[功能清单](docs/development/feature-inventory.md)。未来范围只在[产品需求](docs/requirements/product-requirements.md)维护。

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
| 开始开发或继续会话 | [项目背景](docs/development/project-context.md)、[仓库规则](AGENTS.md) |
| 了解实现与数据流 | [技术架构](docs/architecture/technical-architecture.md) |
| 运行评估、查看证据 | [评估入口](evals/README.md)、[版本验收导航](docs/README.md#评估与验收) |
