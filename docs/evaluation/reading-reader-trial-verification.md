# 真人试读接线验证

日期：2026-10-22。实现 acceptance-v10 / reading-evidence-v3。目标是让读者通过真实页面手动使用现有阅读 AI；此前固定评测生产门禁无法支持自由提问，不能只改密钥或 enabled 开关。

## 交付

- 默认关闭的 READER_TRIAL_LOCAL 独立授权；启动验证 localhost、模型/端点、源码/资产锁、独立金额/次数、计价及期限，不接受原评测前置预算。
- 沿用独立供应商持久账本、原子预留/结算、80% 预警及首次停止原因；与固定评测配置互斥。授权摘要绑定任务，重启不清除已用金额或停止状态。
- 页面开放人工回顾、查证及索引入口，展示各供应商额度、已计价/预留、授权期限。范围和原文传输在用户确认时说明。
- 单独启动脚本：localhost 5201/18081、持久数据库 15434，沿用本地密钥；日常环境、历史评测库/账本保持原样。没有新增依赖、表或迁移。

## 工程结果

后端执行 ReadingReaderTrialTests、ReadingLiveAuthorizationTests、ReadingTaskPersistenceTests、ReadingProviderProtocolTests、ReadingTaskPolicyTests、ReadingLookupCompletenessTests、ReadingNoteProposalPersistenceTests，共 93 项，其中 2 个需显式启用的浏览器夹具跳过，其余最终通过。首轮新测试包含泛型断言编译问题以及将数据库 NULL 预期为 Map 真实键的断言问题；修正测试表达后，对失败的完整试读协议流程单独复验通过，其余已通过项目未重复执行。

新增覆盖：未批准/过期/计价失效/超额/改变端点/实现变化/非 localhost/重复 JSON/双模式拒绝；自由人物问题经本地 HTTP 替身完成索引→检索→回答→分供应商结算；范围回退隐藏旧答案；重建授权对象不重置用量；响应截断停止两个供应商，随后创建或重启不能再次调用。原预算并发、未知用量、金额上限、来源隔离和笔记确认回归继续通过。

前端 `npm run build`（含 vue-tsc）通过；后端在上述测试后 `-DskipTests package` 成功。31 项评测/采集/性能工具测试通过。工具首轮在新锁尚未生成时出现缺文件，完成新版本冻结后全套复验通过。PowerShell 语法、Compose 配置、diff 空白与当前源码/资产锁校验通过。保留 v1–v9 锁原文件，不覆盖历史证据。

日志：backend/target/reading-trial-tests.log、reading-trial-recheck.log、reading-trial-frontend-build.log、reading-trial-package.log、reading-trial-tools.log（本地忽略目录）。

## 启用现场

已按本轮明确授权安装独立批准文件（本地忽略路径 data/reading-trial/approval.json）。授权 ID 为 7b12f55d-0f04-4851-955a-22cc24e7f4bd，2026-10-22 16:49:11.952 +08:00 至 2026-10-23 16:49:11.952 +08:00；DeepSeek 20 元，阿里 10 元。原 API Key 未修改或输出。启用时重新核对[DeepSeek 价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)和[北京向量同步接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)，保留原已确认账户条件。

只读状态接口经前端代理和后端均返回 READY / READER_TRIAL_LOCAL，两个账本 callsStarted、reservedCny、budgetValuedCny 全为零。浏览器打开 http://127.0.0.1:5201/books，确认空书架和“导入小说”入口；可直接复制到本机 Chrome/Edge，不依赖内置浏览器。实际参与者尚未操作，未导入私人小说，未代替用户发送收费请求。

此结果证明本地接线与授权就绪，不证明供应商账号当前连通性、最新 Prompt 语义正确性或真人阅读效果。旧 DEBUG 失败、四个停止账本和 TEST96 未运行状态保持不变；不自动运行付费验证。后续操作参见[试读说明](../development/reading-reader-trial.md)和[交接](../development/continuation-handoff.md)。
