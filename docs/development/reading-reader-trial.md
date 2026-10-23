# 本机真人阅读试读

2026-10-23 用户已改用[日常单开关模式](reading-direct-models.md)，不设置金额预算或批准文件。下文仅保留历史受控试读用法；试读前后端已经关闭，不要把旧脚本当作日常启动入口。

2026-10-23 当前试读账本已因首次回顾 `OUTPUT_INVALID` 停止，不能使用下方启动命令恢复付费。停止状态查询已作离线修复，详见[失败记录](../evaluation/reading-trial-failure-verification.md)；原始回顾不合规原因尚未确定。下文为初次启用说明，不构成续期、重置或重试授权。

本入口用于实际读者自行导入小说、确认范围并操作真实回顾/人物查证。它与固定 DEBUG/TEST 评测、固定离线演示分开；不把试读结果自动写成验收成绩。

2026-10-22 用户已单独授权：DeepSeek 最多 20 元、北京阿里向量最多 10 元、启用后 24 小时，仅本机人工操作。旧四个停止账本不恢复、不转移余额。实现为 acceptance-v10 / reading-evidence-v3。

## 启动和停止

前置条件：Docker、Java 21、已安装前端依赖；`backend/config/application-local-secrets.yml` 中保留原有两个 API Key，禁止提交密钥。阅读客户端使用固定 DeepSeek 和北京业务空间专属端点，不使用旧知识库的端点选择。

本轮的批准文件保存在忽略目录 `data/reading-trial/approval.json`。它绑定实现锁、模型/端点、计价、独立授权 ID、两个金额/次数上限和绝对到期时间。模板 `evals/configs/reading/reader-trial.example.json` 默认未批准，不能直接启用。新的期限或预算必须先得到用户明确授权，再由运维准备；启动脚本不生成、续期或覆盖批准文件。

在仓库根目录运行：

```powershell
.\tools\reading-trial.ps1 Start
# 另一个终端查看状态（不调用模型）：
.\tools\reading-trial.ps1 Status
# 先等任务结束再停止，避免中断产生未知用量：
.\tools\reading-trial.ps1 Stop
```

首次启动前，需在 backend 使用 JDK 21 执行 `./mvnw.cmd package`。修改源码后须完成相应检查并发布新的实现锁和相应授权绑定，不能复用旧批准文件。

地址：<http://127.0.0.1:5201/books>，后端 `127.0.0.1:18081`。已有日常端口 `5200/18080` 不变。此入口只绑定 localhost，不支持多人同时从远程接入。

数据库使用 Compose 项目 `contextpilot-reader-trial`、本机端口 15434、库名 `context_pilot_reader_trial` 和持久卷 `reader_trial_data`；上传正文在 `data/reading-trial/uploads`。服务停止保留数据和预算账本，**不要执行 down -v 或删除卷来恢复费用额度**。运行日志和进程信息在 `data/reading-trial/runtime`；日常库和旧评测库不受影响。

## 试读流程

1. 导入有权使用的 UTF-8 TXT/Markdown，核对分章，再确认导入。
2. 进入正文，明确设置已读边界。阅读位置和跳转不会自动授予 AI 更多已读内容。
3. 从“回顾后继续”或阅读助手选择最近 1–3 章，确认后生成真实回顾。
4. 人物查证前先查看“准备范围与费用”，确认建立已读范围索引；较长内容可分次补齐，范围未准备完不会开始查证。问题和必要正文会发给已配置供应商。
5. 自由输入人物问题，核对引用。可选创建并独立确认 AI 笔记，再继续阅读。
6. 记录帮助程度、遗漏/错误/剧透、任务 traceId 和操作情景；不将私人小说正文提交到仓库。真实反馈完成标准仍为唯一路线规定的至少 5 人中 4 人认为回顾有助接读。

页面显示供应商上限、已计价、预留和期限。预留采用供应商上下文上限，显著高于短文本通常实际用量；总预算不等于账户充值或供应商账单。单次预留不足会在 HTTP 前停止，即使仍有少量余额。整个试读最多 100 次生成和 200 次向量请求；80% 预警，任一供应商到期、额度/次数不足、用量未知或任务调用失败后停止，不能通过刷新或重启清除。健康检查、导入、基础阅读及启动本身不调用模型。

计价复核来源：[DeepSeek 人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)、[百炼北京同步向量接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。保守单价为每百万 token 输入 2 元、输出 8 元、向量输入 0.5 元；不使用赠金、缓存或低峰折扣计算预留。计价有效期覆盖批准窗口，过期不自动沿用。

本轮接线与工程测试不证明真实模型已通过质量验收。固定评测的 DEBUG 失败证据保留，TEST 96 仍未执行；录屏已取消。
