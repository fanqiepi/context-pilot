# 小说阅读开发交接

2026-10-23 回顾长度与 SSE 补修：任务 09f93b3d-45ad-43f7-9bce-bda9db7353dc 已确认 FACT_TEXT_LIMIT，正常结束生成，非 JSON_SCHEMA。用户随后明确只需百字回顾：Prompt v6 改为约 100 字、2–3 要点，服务端总字数上限 160（人物查证不变）。修复发送失败后 complete 再刷损坏响应及全局 ApiExceptionHandler 追加 JSON 的遗漏路径，隔离单连接异常。当前 acceptance-v17，未真实调用或代启；下一步先由用户重启并查看已有任务验证连接，后续手动日常回顾验证长度改善，见 docs/evaluation/reading-recap-length-stream-verification.md。

2026-10-23 多章回顾格式修复：现场两章/三章任务既有成功也有 JSON_SCHEMA 失败，三次报告均 finishReason=stop；旧回答未保留，具体结构错误不可追溯。Prompt v5 明确统一扁平 facts/partial 合同，回顾按类型检查并持久化细分原因，未放宽校验或自动重试。当前 acceptance-v16，真实改善尚未验证。下一步用户自行重启并手动验证相同范围的多章回顾，按具体错误码继续定位，见 docs/evaluation/reading-recap-schema-verification.md。

2026-10-23 SSE 修复：用户截图显示回顾完成，数据库最近三次回顾也均 COMPLETED。日志是 ReadingHttpAdvice 将已提交 text/event-stream 再写为 JSON 引发的二次异常，原始触发原因未留在所给日志中。现识别断连并禁止对已提交响应追加错误 JSON，未改变任务结果或重跑模型。实现 acceptance-v15 / Prompt v4；下一步用户重启后仅查看已有结果、开关助手验证连接，见 docs/evaluation/reading-sse-error-verification.md。


2026-10-23 回顾失败诊断修复：用户任务 1c5f4739-d33d-4945-8bb4-bcab28a4135c 返回实际 6097/979 token 后 OUTPUT_INVALID；旧数据缺少结束原因，不能确诊。现统一记录停止日志、持久化 failureReason 与白名单 finishReason，页面展示具体类别。当前 acceptance-v14 / Prompt v4；未重试或代启。下一步由用户重启并手动发起日常任务，用明确诊断定位实际故障，详见 docs/evaluation/reading-failure-diagnostics-verification.md。


2026-10-23 最新用户纠正：撤销新增的 IDEA Application 启动项，改为现有 application.yml 自动导入本地配置（默认/local-secrets profile，兼容仓库根目录及 backend 工作目录）。继续使用原 ContextPilotApplication，日常开关仍在被 Git 忽略的 backend/config/application-local-secrets.yml 中。普通测试默认 offline，避免继承本机密钥和开关。当前冻结 acceptance-v13，Prompt v4 不变；用户自行重启后先确认 DIRECT_LIVE/按钮可用，再发起实际回顾验证。未代启或调用供应商，见[日常开关](reading-direct-models.md)。

2026-10-23 阅读反馈修复：位置自动保存与 AI 范围保持独立，首次回顾可在助手内直接确认保存位置；Prompt v4 补齐既有输出上限，日志增加不含正文的校验分类。离线回归通过，当前真实失败原因尚待用户错误码/任务编号确认，不宣称回顾已真实修复。实现冻结 acceptance-v12；下一阶段为用户自行启动后的单次回顾现场验证，详见[本轮记录](../evaluation/reading-recap-usability-verification.md)。


2026-10-23 最新用户决定覆盖以下历史试读规则：日常模型只由 `contextpilot.reading.ai-enabled` true/false 控制，不设金额预算/批准文件/期限。当前本地 `application-local-secrets.yml` 已设 true，仓库默认 false；DIRECT_LIVE 仍记录 usage、执行单任务范围/次数/超时/输出校验，与旧试读/评测模式互斥。原停止账本不动。试读 5201/18081 已按用户要求关闭，后续由用户自行启动，不代启。用法见[日常开关](reading-direct-models.md)。当前交付合并了停止状态查询修复，统一冻结 acceptance-v11；没有自动真实调用。

本轮完成标准：单开关 true/false 与缺密钥门禁、本地 HTTP 索引/查证/回顾、无金额账本写入、实际 usage 保存及旧模式回归通过。下一阶段继续根据用户自行运行反馈定位回顾输出不合规，补充不含正文的分项诊断并作离线修复；真实调用由用户在页面发起，无需再套用旧日常试读预算授权。固定 DEBUG/TEST 仍需独立指令，语义验收未通过。

2026-10-23 现场更新：首次真人回顾已到达 DeepSeek，返回 5877/1307 输入/输出 token，随后因 `OUTPUT_INVALID` 失败；保守结算 0.02221000 元，向量调用及未知预留为零。两份试读账本已停止，不得沿用下方旧 READY 状态或启动说明自动恢复。用户报告的 `READING_RETRYABLE` 另由停止状态查询的事务回滚异常造成，当前进行有界离线修复，详见[失败与修复记录](../evaluation/reading-trial-failure-verification.md)。下方启动现场为 10 月 22 日历史记录。

下一阶段目标：定位真实回顾 `OUTPUT_INVALID` 的具体校验原因，补充不含正文的分项诊断并按证据修复；完成标准为停止原因可查询、无效输出仍被拒绝、离线关键流程回归通过。真实复测依赖用户另行明确发起及停止试读的授权处理；本次修复不重启试读、不创建新预算，也不等同真实语义验收。

更新：2026-10-22。本轮从 develop/7b833e1 开始，用户要求配置真人阅读真实链路，并明确批准独立 DeepSeek 20 元、阿里向量 10 元、启用后 24 小时、仅本机人工操作。用户不需要自行重启日常后端。

## 本轮交付

补齐此前缺失的真人自由任务接线：READER_TRIAL_LOCAL 本地批准文件、与固定评测互斥、独立持久供应商账本、页面真实任务入口和预算/期限展示。复用已读范围、回顾、人物查证、引用与确认笔记合同，没有新表或依赖。过去“核心功能已齐”只指工程流程；此前生产真实调用只接受固定评测，页面只开放离线演示。

实现 acceptance-v10 / reading-evidence-v3，保留 v1–v9 锁和旧失败观测。完整使用方法见[真人试读](reading-reader-trial.md)，验证见[试读接线验证](../evaluation/reading-reader-trial-verification.md)。此前的[长篇性能证据](../evaluation/reading-performance-verification.md)保持原状，录屏已由用户取消。

## 当前现场

- 已启动页面 http://127.0.0.1:5201/books、后端 127.0.0.1:18081；保留日常 5200/18080。
- 独立持久 PostgreSQL：Compose 项目 contextpilot-reader-trial、本机 15434、库 context_pilot_reader_trial；上传目录 data/reading-trial/uploads。重启沿用原数据和账本，不删除卷。
- 本轮授权 ID 7b12f55d-0f04-4851-955a-22cc24e7f4bd；启用 2026-10-22 16:49:11.952 +08:00，到期 2026-10-23 16:49:11.952 +08:00。DeepSeek 20 元/最多 100 次，阿里 10 元/最多 200 次。金额不可互借，80% 预警，失败/到期/预算或次数不足停止，不自动重启。
- 本地批准文件 data/reading-trial/approval.json 被 Git 忽略；密钥沿用 backend/config/application-local-secrets.yml，没有改写或提交。
- 启动核对状态 READY、两个账本用量/费用/预留为零，没有发送真实模型请求。后续读者人工操作会改变状态，续接会话须实时核对。
- 旧 v5/v7 四个评测账本保持 DEBUG_REVIEW_FAILED；TEST96 仍未运行。这次试读不借用旧余额，也不授权自动评测。
- 停止：tools/reading-trial.ps1 Stop；启动：tools/reading-trial.ps1 Start；查看：tools/reading-trial.ps1 Status。停止前等任务结束；中断产生未知用量时保留预留并停止，不能靠重启清除。

## 验证及未完成项

相关阅读授权/协议/持久化/范围/笔记测试通过，前端类型检查与构建、后端打包、31 项评测/采集/性能工具测试、当前源码/资产锁校验通过。浏览器确认空书架与导入入口可用；没有替用户点击收费任务。首次新测试遇到测试断言将 SQL NULL 当作 Map 实际键的问题，修正后该完整流程复验通过；没有修改生产门禁来迁就断言。

真实供应商端到端调用、最新 Prompt 语义表现和读者反馈尚待人工试读；启动 READY 只证明配置和本地授权就绪，不能当作账号连通性或模型质量证明。

## 下一阶段具体做什么

用户目标：验证实际小说回顾和人物查证是否有助继续阅读。范围：用户在上述页面导入小说、确认已读范围、手动回顾/索引/查证/引用/保存笔记，并记录遗漏、错误、剧透或操作阻碍及 traceId。按实际反馈集中修复现有流程，不增加新大功能。

完成标准：保留真实反馈与失败，按唯一路线至少 5 人中 4 人认为回顾有助接读；正式语义和 AI 性能评分另按原门禁验收，不能以试读通过替代 DEBUG/TEST。依赖实际参与者及当前授权窗口；过期、停止或新增费用范围须另行明确授权，不自动续期或恢复。固定 DEBUG 重测仍需用户主动发起并按其独立前置账本规则准备。

本大功能点集中交付后提醒用户发起 PR；不自动推送、创建 PR 或合并。

本次失败诊断验证：后端 70 通过、1 项既有夹具跳过；前端构建与 5 项交互测试通过。首次回归测试失败源于旧夹具字段缺失，修正夹具后复验通过。见本轮诊断记录；不代表旧真实回顾根因已确诊。

本轮 SSE 修复验证：异常处理、阅读库与任务回归合计 46 通过、1 项既有夹具跳过；新增测试包含 4 类提交后异常及发送前 JSON 错误。未改前端，不运行无关前端构建。
