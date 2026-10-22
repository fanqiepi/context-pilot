# 阅读 DEBUG 拒答修复真实重测

2026-10-22，本轮按用户“进行下一轮的任务”执行此前明确的 DEBUG 重测范围，基于本地 develop/d01f5d6。已核实远程 PR #38 合并；未将 d01f5d6 声称为已合并。**12 题完成采集，原 debug-008 正确拒答；另发现两题完整性判断过于保守，DEBUG 复核未放行，TEST 未执行。**

## 范围与授权衔接

Prompt 保持 reading-evidence-v2，全部 v1 语料、问题、参考事实及评分不变。本轮新增 V34、DEBUG 专用允许清单和索引复用前置检查，因此冻结为 acceptance-v7；保留 v1–v6 配置/锁和 v5 失败证据。没有使用 TEST 调参或追加题目/重试。

具体金额、次数和到期时间由[唯一路线](../development/novel-reading-roadmap.md)维护。新计划只有 12 条 DEBUG、一次运行；服务端拒绝 TEST/INDEX 请求，debug-reviewed 开关无法扩大范围。采集前确认 island-debug-v1 的既有索引完整覆盖第 1–12 段，随后每题独立应用自己的边界；复用不等于允许查询未读段落。本轮新增索引为零，旧索引费用没有重复计入。

V34 为每个供应商账本增加唯一 predecessor_id。新额度严格等于旧额度减已结算金额，要求旧账本已停止、主体/摘要/币种/计价匹配且无预留；同一旧账本不能创建多个后继，逻辑删除也不释放唯一约束。原 v5 两个账本的停止原因、消耗与次数均未改变。

2026-10-22 14:42 +08:00 重新核实 [DeepSeek 官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)与[百炼同步 embedding 价格](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)：预算口径仍为高峰未命中输入 2 元/百万、输出 8 元/百万、向量输入 0.5 元/百万。原人民币/北京/按量/模型开通确认沿用，无需重复确认。DeepSeek 请求名保持 deepseek-v4-flash，返回 model 为 deepseek-flash，返回别名不独立证明权重版本。

新批准于 14:50:39 +08:00 安装，沿用原最晚到期时间，实际已提前停止。授权文件、凭据和数据库备份仅保存在本地忽略目录。

| 绑定 | 值 |
| --- | --- |
| 新 authorizationId | 17e7ed10-a1d8-48b0-9c40-8ac18d9f41bd |
| 前置 authorizationId | 1507e934-3649-4cc6-914e-322887debb10 |
| 新 plan SHA-256 | 8269bf5e3113cad014b2676e46aec2a4969340833ef3572e1d48be69b712750f |
| 新 approval SHA-256 | fa927dd8be495a5c8d4ed7ef915504bf5f26b7aa1cdfb7bb09eeaae9cbccdffa |
| 新 lock SHA-256 | a33bff6c76f15cb5a67eb2033a991230016348f31b289da84ede61203361adeb |

## 真实结果与问题

12 个任务：8 COMPLETED、2 PARTIAL、2 INSUFFICIENT_EVIDENCE；无技术失败，29 次 HTTP 均 SUCCEEDED 且 usage 已结算。两个无依据题 debug-008、debug-012 本次均正确拒答。

- **debug-008**：“明天的实际风力是多少？”本次空 facts、partial=false、INSUFFICIENT_EVIDENCE；没有将纸条的要求替代实际天气。taskId 为 c98dba1b-7e9b-41d4-872d-1cba3e7d676b，traceId 为 5a7622a0-7441-4a77-9048-ae9f319789ab。
- **debug-009**：分别回答纸条要求九级、不用观察，以及陶禾实际只抄录仪器当时读数，分别引用第 7、8 段；没有把要求当事实。
- **debug-006**：“罗澄怎样处理测风仪故障？”回答“罗澄换好记录线后，测风仪重新出纸”，却标 PARTIAL；单一处理方式问题已有直接依据。没有复述参考事实中的断线/暂停背景；检索 evidenceRanges 已含第 4、5 段，不能归因于检索缺失。
- **debug-011**：“花盆布条是孟秋系的吗，已读内容怎样说明？”只返回孟秋说明是陶禾系的、不是自己系的，标 PARTIAL。第 3 段直接叙述及第 9 段澄清都已检索到，却没有完整组织相互印证的信息。

后两题的已输出事实有依据、核心答案可用，不能描述为幻觉或完全拒答；但与 v2 对 FULL/PARTIAL 的定义及完整组织依据的目标不一致。本轮保留这个判断问题，未在采集中改 Prompt、补跑或放宽评分。仅凭两题 PARTIAL 终态本身不能证明失败；上述结论来自具体问题、输出与已检索原文的逐条核对。

四个回顾覆盖对应窗口事实。执行代理核对中未见未读泄露，30 个被引用来源的版本、段落 ID、文本/哈希及边界逐项匹配；这不是“绝对不剧透”保证。逐题分析见[执行代理复核](../../evals/results/reading/acceptance-v7/debug-operational-review.json)。review 字段仍为 null，没有伪造独立人工评分，正式语义与体验验收均未完成。

## 调用与费用

| 供应商 | 本轮 HTTP | 输入 token | 输出 token | 本轮保守结算（元） | 含 v5 累计（元） | 原批准剩余（元） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DeepSeek | 16（12 回答、4 规划） | 5611 | 1019 | 0.01937400 | 0.03676800 | 19.96323200 |
| 阿里向量模型 | 13（全部查询） | 301 | 0 | 0.00015050 | 0.00048750 | 9.99951250 |

本轮合计 0.01952450 元，含 v5 累计 0.03725550 元。逐调用汇总与持久账本一致；UNKNOWN=0、未释放预留=0。未触发 80% 金额预警，停止原因为 DEBUG 复核问题，并非预算耗尽。上述均为保守预算记账，不是云端最终账单。全部调用上界预留的累计和不是同时占用金额，也不能作为本轮实际费用。

操作者在采集结束、核对输出后，仅将两个新账本按既有停止语义设置 DEBUG_REVIEW_FAILED，并提前到期；没有伪称自动语义识别或自动告警。状态接口确认 liveAllowed=false、debugReviewed=false。TEST 96 次保留 NOT_RUN。本轮预算剩余不自动授权恢复停止批次。

## 验证与恢复材料

- Reading*Tests 首次运行 101 项，新增测试因短摘要夹具不符合 CHAR(64) 合同失败；修正为真实长度摘要后，ReadingLiveAuthorizationTests/ReadingTaskPersistenceTests 31 项重跑通过、1 条件跳过，其余阅读测试此前通过。整个覆盖包含 2 项既有浏览器夹具条件跳过，不构成浏览器验收。中间一次重跑因工作目录错误未应用夹具修正，仍失败，最终修正后才启用真实调用。
- Node 27 项通过，覆盖 DEBUG/TEST 隔离、旧索引完整性前置检查、成功复用不创建索引及不重复记旧费用；JDK 21 package 成功。V34 在 Testcontainers 空库及隔离评测库 V33→V34 迁移成功，覆盖已停止/无未知预留/额度准确/后继唯一等门禁。
- acceptance-v7 资产/源码锁核验通过。未改前端、业务结果、阅读笔记合同或生产依赖；未运行无关旧付费实验。
- [原始观测](../../evals/results/reading/acceptance-v7/debug-v7.json)、[未评分报告](../../evals/results/reading/acceptance-v7/debug-v7-report.json)、[执行摘要与哈希](../../evals/results/reading/acceptance-v7/execution-summary.json)、[停止状态](../../evals/results/reading/acceptance-v7/status-v7-stopped.json)、[四个账本的审计快照](../../evals/results/reading/acceptance-v7/ledger-v7-stopped.json)。原始 providerBudgets 是采集开始快照，结束金额看停止状态。

Windows 11 / JDK 21.0.3 / PostgreSQL 17.10 / pgvector；仅 localhost:18081。后端及隔离容器已停止，命名卷 context_pilot_reading_eval_20261022 保留。本机 backend/target/reading-batch-20261022 下保存 plan-v7.json、批准文件、原始 journal、backend-v7 日志、database-before-v7.sql 与 database-after-v7.sql；前者已经包含 v5 消耗，**不得恢复旧付费前 database.sql 清零账本，也不得回滚新账本来重复承接余额**。

下一轮优先离线修正 FULL/PARTIAL 判断与已检索依据的完整组织，保留本轮两题及旧拒答正反例。完成修正和回归后再准备新的明确重测范围；不自动解锁 TEST、不自动再次付费。实际读者试用、正式语义评分、冷/热性能与新演示录屏继续保持未完成。
