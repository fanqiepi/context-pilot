# 研究成果审查运行指南

合同见[设计](../architecture/research-result-review-design.md)。当前能力附属于研究任务工作台，使用相同 `TASK_WORKSPACE_ENABLED=true` 开关；模型凭据仍由后端环境变量或本地未跟踪配置加载。默认模型为 `deepseek-v4-flash`，响应 metadata 可能返回 `deepseek-flash`，记录两者，不静默覆盖。

1. 启动 PostgreSQL 与后端，Flyway 应用 V27；开启工作台并配置模型及 Embedding。
2. 在 `/research-tasks` 创建并完成有证据的研究；点击“审查当前结果”。
3. 查看被审查的原文、每条断言与对应证据、问题和缺口。没有发现问题不等于事实已验证。
4. 若有补充建议，点击“填入补充框，编辑后提交”，自行编辑，再通过“补充并重新规划”开始新轮次。旧审查保留为历史，不会自动修改答案或成为证据。
5. 可取消活动审查，或逻辑删除终态审查。每个快照最多三次尝试，失败和取消也计入；网络重放同一创建键不会重复执行。刷新会恢复数据库状态。

来源变更后原文与审查内容隐藏；任务删除后审查接口不可读。重启不会重放模型请求，未完成审查以 `REVIEW_SERVER_RESTARTED` 收敛失败。取消或超时仅阻止本地写回，不保证供应商停止计费。

## 验证命令

从 `backend/`（先设置 JDK 21）：

```powershell
.\mvnw.cmd '-Dtest=Review*Tests' test
.\mvnw.cmd '-Dtest=ReviewLiveTests' '-Dcontextpilot.evaluation.review-live=true' test
.\mvnw.cmd package
```

第二条显式开启真实模型调用，读取 `local-secrets`，只使用冻结公开样本；数据库采用隔离 Testcontainers。输出位于 `backend/target/research-review-live.json`，不打印密钥。前端执行 `npm run typecheck` 与 `npm run build`。
