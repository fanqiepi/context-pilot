# 项目资料接入运行与演示

此能力默认关闭。只读取固定公开仓库 `spring-projects/spring-ai` 的指定 tag／完整 commit SHA，以及用户指定的 1–5 个 TXT／Markdown 相对路径。两个固定 MCP 工具不会注册到聊天模型。

## 启动

先按[本地开发](local-development.md)准备 JDK 21、PostgreSQL、前端依赖和模型配置。在 `backend/` 的独立 PowerShell 终端启动只读 MCP 服务：

```powershell
$env:JAVA_HOME = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\start-project-source-mcp.ps1
```

脚本会编译、生成运行 classpath，再启动 `ProjectSourceMcpServer`；仅监听 `127.0.0.1:8091/mcp`，不会启动数据库或模型。可用 `-Port 8092` 改端口。GitHub 公共访问通常不需要凭据；如遇匿名限流，通过本机环境变量 `GITHUB_PROJECT_SOURCE_TOKEN` 注入只读凭据，不写入脚本或版本库。

在另一个终端按现有配置启动主后端，并添加：

```powershell
$env:PROJECT_SOURCE_ENABLED = 'true'
$env:PROJECT_SOURCE_MCP_URL = 'http://127.0.0.1:8091'
$env:TASK_WORKSPACE_ENABLED = 'true'
$env:DOCUMENT_PROCESSING_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

端点只接受本地 HTTP 主机与端口，路径固定 `/mcp`，不能由页面或资料提供。主后端继续使用既有本地数据库和模型配置；公开真实流程需要 DashScope Embedding 与 DeepSeek。无模型环境可预览、确认登记，但不能将未处理文档用于真实研究。

启动前端后打开 `/project-sources`。默认前端端口 5200，后端端口 18080。全部组件仍限本机或可信网络，不提供用户认证。

## 代表演示

1. 创建专用演示知识库；项目资料页输入 `v1.0.0`、`README.md`，点击读取并预览。
2. 检查完整提交 SHA、内容哈希、获取时间和正文；刷新页面，预览通过 URL 中的 ID 从数据库恢复。
3. 选择演示知识库，生成导入提案。此时没有知识库文档或向量副作用。
4. 刷新后点击“确认导入知识库”。登记成功与索引处理状态分别展示；重复确认不会生成重复文档。
5. 处理成功后进入研究任务，选择该知识库和 README，询问“只根据 README 简要说明 Spring AI 的目标和主要功能”。预览计划后开始执行，检查引用结果。
6. 在资料库的文档“来源”中查看固定提交、路径、SHA-256 和原文件链接。本地上传文档显示未关联外部来源。

## 失败与恢复

- 服务未启动、协议／工具定义不匹配、GitHub 限流或文件不支持：预览变为 FAILED，展示安全错误码，单份文件读取失败时同时显示路径；点击读取可创建新的预览，不隐藏重试。
- 预览最长激活 90 秒，超时和启动恢复会持久化失败；之后返回的外部结果不能覆盖失败状态。重启不自动抓取远端。
- 预览有效期 24 小时；提案的到期时间不晚于预览。确认只使用被冻结的字节，不重新解析 tag。
- 确认事务提交前崩溃或数据库异常：整批登记和动作执行抢占回滚到待确认，用户可重放独立确认。已提交的文档由既有文档处理恢复扫描继续索引。
- 稳定文档 ID、存储键及 `project_import_staged_file` 将暂存文件关联到提案；失败重试先校验已有文件，不覆盖或重复生成。暂存文件与业务文件均不做普通物理删除；未来清理需单独维护流程。
- 文档处理失败沿现有确认维护流程重试；同一导入身份已逻辑删除时不自动复活。

## 验证命令

```powershell
# 离线协议、真实 PostgreSQL 与回归；不触发付费测试
.\mvnw.cmd package

# 单独触发公开 GitHub + 真实模型流程（需要 local-secrets 配置）
.\mvnw.cmd '-Dtest=ProjectSourceLiveTests' '-Dcontextpilot.evaluation.project-source-live=true' test
```

真实流程的完整公开样例结果写入 gitignored `backend/target/project-source-live.json`。报告只保留安全摘要；一次公开流程通过不等于独立语义质量验收。前端执行 `npm run typecheck` 和 `npm run build`。

新增依赖为官方 `io.modelcontextprotocol.sdk:mcp:2.0.0`（协议及 Jackson 3）与 Spring AI BOM 管理的 `mcp-spring-webflux:2.0.0`（复用已有 WebFlux，限制响应缓冲）；协商协议固定 `2025-11-25`。API 使用以本地依赖和真实 HTTP 测试为准，参考[官方 SDK](https://java.sdk.modelcontextprotocol.io/latest/quickstart/)。
