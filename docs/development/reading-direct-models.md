# 日常阅读模型开关

2026-10-23 用户明确要求以 true/false 启用模型调用，不设置金额预算。此模式使用 DIRECT_LIVE，不要求试读批准文件、期限、计价记录或额度。历史试读和固定评测保持各自原规则及停止证据。

本机已经在 Git 忽略的 `backend/config/application-local-secrets.yml` 配置：

```yaml
contextpilot:
  reading:
    ai-enabled: true
```

改为 `false` 关闭，改回 `true` 启用，修改后重启后端。原文件中的两个 API Key 保持不变。现有 `backend/src/main/resources/application.yml` 已配置自动导入本地文件，兼容从仓库根目录或 `backend` 启动；无需新建 Application 或手动设置 profile。没有本地文件时仍默认关闭。无需修改 Spring AI 的 chat/embedding 开关，阅读使用专用客户端。

用户自行启动，助手不代启、不自动调用模型。在 `backend/` 使用 JDK 21：

```powershell
.\mvnw.cmd spring-boot:run
```

在 `frontend/`：

```powershell
npm run dev
```

使用原日常数据库和上传目录，默认页面 `http://localhost:5200/books`、后端 18080。原试读的书与账本仍在独立数据库 15434，不会自动搬入日常库。IDEA 直接重启原来的 `ContextPilotApplication` 即可；不要用旧 `reading-trial.ps1 Start` 启动日常模式。

自动导入适用于默认 profile 和原有显式 `local-secrets` profile。其他显式 profile（如 `offline`）不自动导入。普通测试在测试配置中默认使用 `offline`，不继承本机密钥和日常 AI 开关；已有主动选择 local-secrets 的受控评测仍遵守原规则。仅刷新前端不能让运行中的后端重新加载配置。

2026-10-23 现场定位：页面已加载新入口，但按钮禁用。18080 后端及 5200 前端代理的只读状态接口均返回 `liveAllowed=false / LIVE_AI_NOT_AUTHORIZED`；本地开关为 true，原 Application 未加载它。用户要求配置直接写入配置文件，已撤销新增的 IDEA 启动项，改由 application.yml 导入。配置加载测试覆盖两种工作目录、缺文件默认关闭、显式 false、local-secrets 兼容及 offline 隔离；未替用户重启，是否 READY 仍需在用户重启后通过下方只读接口确认。

页面确认任务后才会调用模型；人物查证先按已读范围建立索引。保留单任务生成/查询次数、超时、输出校验、范围及笔记确认，不自动重试。新模式不创建金额账本；实际 usage 继续保存，金额未计价用 null 表示，不伪造零费用。某个任务失败只保留该任务失败状态，不将日常开关关闭。旧批准任务不能转入该模式恢复。

只读检查 `/api/reading/evaluation/status` 应返回 `executionScope=DIRECT_LIVE`、`budgetEnforced=false`、`liveAllowed=true`；缺密钥会返回未就绪。该检查不调用供应商，因此不能替代真实连通性和质量验收。

本次验证：6 项配置加载/测试隔离检查与 37 项本地 HTTP 协议检查通过；无真实模型调用。
