# 日常阅读模型开关

2026-10-23 用户明确要求以 true/false 启用模型调用，不设置金额预算。此模式使用 DIRECT_LIVE，不要求试读批准文件、期限、计价记录或额度。历史试读和固定评测保持各自原规则及停止证据。

本机已经在 Git 忽略的 `backend/config/application-local-secrets.yml` 配置：

```yaml
contextpilot:
  reading:
    ai-enabled: true
```

改为 `false` 关闭，改回 `true` 启用，修改后重启后端。原文件中的两个 API Key 保持不变。仓库默认配置 `backend/src/main/resources/application.yml` 为 `${READING_AI_ENABLED:false}`；本地 profile 中的显式值优先，日常直接编辑上述本地文件即可。无需修改 Spring AI 的 chat/embedding 开关，阅读使用专用客户端。

用户自行启动，助手不代启、不自动调用模型。在 `backend/` 使用 JDK 21：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-secrets'
```

在 `frontend/`：

```powershell
npm run dev
```

使用原日常数据库和上传目录，默认页面 `http://localhost:5200/books`、后端 18080。原试读的书与账本仍在独立数据库 15434，不会自动搬入日常库。若已在 IDE 配置 local-secrets，沿用 IDE 启动方式即可。不要用旧 `reading-trial.ps1 Start` 启动日常模式。

IDEA 用户可选择仓库共享的 **ContextPilot 本地阅读** 启动项（[配置文件](../../.run/ContextPilot-local.run.xml)），使用 JDK 21。它固定工作目录为 `backend`、激活 `local-secrets`，从 `backend/config/application-local-secrets.yml` 加载本地开关和密钥，不在共享配置中保存密钥。先停止旧后端，再启动该配置，避免 18080 端口冲突。仅刷新前端不能让运行中的后端重新加载配置。

若沿用自己的启动项，设置工作目录 `$PROJECT_DIR$/backend`，环境变量 `SPRING_PROFILES_ACTIVE=local-secrets`；其他覆盖配置也须核对。仅在本地文件写 `ai-enabled: true` 并不意味着该 profile 已经加载。

2026-10-23 现场定位：页面已加载新入口，但按钮禁用。18080 后端及 5200 前端代理的只读状态接口均返回 `liveAllowed=false / LIVE_AI_NOT_AUTHORIZED`；本地开关为 true，当前 IDEA Application 配置未激活 profile。该问题发生在创建任务之前，与模型回答校验失败不同。新增共享启动项经 XML、模块名、工作目录和配置文件路径检查；未替用户重启，是否 READY 必须在用户重启后通过下方只读接口确认。

页面确认任务后才会调用模型；人物查证先按已读范围建立索引。保留单任务生成/查询次数、超时、输出校验、范围及笔记确认，不自动重试。新模式不创建金额账本；实际 usage 继续保存，金额未计价用 null 表示，不伪造零费用。某个任务失败只保留该任务失败状态，不将日常开关关闭。旧批准任务不能转入该模式恢复。

只读检查 `/api/reading/evaluation/status` 应返回 `executionScope=DIRECT_LIVE`、`budgetEnforced=false`、`liveAllowed=true`；缺密钥会返回未就绪。该检查不调用供应商，因此不能替代真实连通性和质量验收。
