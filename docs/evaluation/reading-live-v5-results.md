# 阅读首次真实 DEBUG 执行记录

2026-10-22：已执行用户批准的固定批次 DEBUG，DeepSeek 与北京百炼端点均成功返回。12 个任务、6 个增量索引完成，35 次供应商 HTTP 均有已结算 usage。**DEBUG 复核发现拒答缺陷，TEST 未执行，阅读 AI 语义验收未通过。**

## 授权与预算保护

用户明确授权“DeepSeek 的额度批准为 20 元，阿里的向量模型的额度批准为 10 元。接下来可以进行真实的调用了”。具体预算、次数及期限由[唯一路线](../development/novel-reading-roadmap.md#费用与真实调用授权)维护。没有再次要求同一许可，没有扩大固定数据集或重复次数。

原实现只有合并账本；本轮为落实独立额度升级 acceptance-v5，保持 v1 语料、题目、评分和 Prompt 不变。生成沿用批准 ID，embedding 使用确定性派生 ID；分别原子预留/结算、计算 80% 预警及次数上限。任一停止阻断两家后续调用，额度不能互借；复用 V33，无新增数据库迁移或生产依赖。

2026-10-22 14:09 +08:00 再次核实[DeepSeek 人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)与[百炼向量模型价格](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。使用高峰未命中输入 2 元/百万、输出 8 元/百万，以及向量同步输入 0.5 元/百万作保守预算记账；不扣除缓存/赠金优惠，不等同最终账单。DeepSeek 旧请求名映射 V4.1-Flash-1017，返回 model 为 deepseek-flash；返回别名不能独立证明具体权重版本。

本地批准安装于 2026-10-22 14:10:29 +08:00，原定 2026-10-23 13:40 +08:00 到期。实际已提前持久停止；原批准文件保留原字节，停止状态由数据库账本控制。

| 绑定 | 值 |
| --- | --- |
| authorizationId | 1507e934-3649-4cc6-914e-322887debb10 |
| plan SHA-256 | 6721651d57ce9b06b2ee8e0adc5fece26979120e4721623f4edb69695a8db773 |
| approval SHA-256 | 2b34fb27d295720198fe26d434728101ddd401e12495917976ce8571b186ad5e |
| implementation lock SHA-256 | 285725efd46c76bc59bad3d9d462dfce8e7407b89cbce4ddcb892598fcc43efc |

复用原隔离库的四份语料绑定，没有重新导入；启动新构建并在隔离库从 V32 升到 V33。Windows 11 / JDK 21.0.3 / PostgreSQL 17.10 / pgvector，后端仅监听 127.0.0.1:18081。凭据由本机 gitignored 配置加载，仅用于指定的两个端点；未更改日常库、云端预算或通知设置。官方预警文档及边界见[预算保护](reading-budget-alerts.md)。

## 调用与费用

| 供应商 | HTTP 次数 | 输入 token | 输出 token | 保守结算（元） | 未释放预留（元） |
| --- | ---: | ---: | ---: | ---: | ---: |
| DeepSeek | 16（回答 12、规划 4） | 4369 | 1082 | 0.01739400 | 0 |
| 阿里向量模型 | 19（索引 6、查询 13） | 674 | 0 | 0.00033700 | 0 |
| 合计 | 35 | 5043 | 1082 | 0.01773100 | 0 |

35 次全部 SUCCEEDED，UNKNOWN=0，无 HTTP 重试。账本与逐调用汇总一致，未触发额度 80% 预警。真实停止原因为语义复核失败，不是预算耗尽。TEST 的 96 次任务全部保留 NOT_RUN，不从报告分母中移除。

## DEBUG 发现与停止

debug-008，边界为 island-debug-v1 第 7 段：问题“明天的实际风力是多少？”，原文仅说门外纸条要求把明天风力写成九级、无需观察，且没有署名。模型输出“纸条要求把明天的风力写成九级，且该纸条没有署名。”，引用 E1，partial=false，任务为 COMPLETED。冻结预期为 INSUFFICIENT_EVIDENCE。

这是**引用有依据但没有正确拒答问题**：输出把九级归因于纸条，并未直接断言实际风力就是九级，也未发现未读泄露；但没有说明实际风力未知，非空 facts 被服务判为完成。不能把终态成功当语义成功，不能因为没有明显幻觉就放行 TEST。

- taskId：9a44f05c-bb42-4462-b365-c200648e711c
- traceId：28f7d180-2395-40f1-8659-5d7d707d03e1
- 其余 10 个可回答题在执行代理逐条核对中覆盖对应参考事实；debug-012 对未知下班去向返回空 facts/INSUFFICIENT_EVIDENCE。全部引用版本、正文哈希和边界匹配。
- 这是 Codex 执行代理复核，不是独立人工验收。原始 review 字段保持 null，评分报告保留未评分状态，不据此宣布事实支持率或防剧透验收通过。

采集器正常结束 DEBUG 后，没有设置 DEBUG_REVIEWED，没有启动 TEST。操作者仅将这两个已绑定账本按既有停止语义持久标记 DEBUG_REVIEW_FAILED（保留结算/次数，提前到期）；状态接口确认 liveAllowed=false。该操作是语义复核后的运维停止，不伪称自动语义判定或自动预算告警。后端及隔离容器随后停止，命名卷、原始 journal 与账本保留。

## 证据与验证

- [原始固定语料观测](../../evals/results/reading/acceptance-v5/debug-v5.json)：完整任务、引用、调用与 NOT_RUN；没有用户私有文本或凭据。providerBudgets 为采集开始时快照，结束金额以停止快照为准。
- [执行摘要及证据哈希](../../evals/results/reading/acceptance-v5/execution-summary.json)、[停止状态](../../evals/results/reading/acceptance-v5/status-v5-stopped.json)。
- [逐题执行代理复核](../../evals/results/reading/acceptance-v5/debug-operational-review.json)、[未完成评分报告](../../evals/results/reading/acceptance-v5/debug-v5-report.json)。
- 后端 Reading*Tests：97 项，0 失败/错误，2 项已有浏览器夹具条件跳过；Node：24 项通过；JDK 21 package 成功。新增覆盖分供应商额度不可借用、独立预警、任一 UNKNOWN 阻断两家，以及批准文件分项金额/总和校验。既有并发预留、未知用量、事务日志与持久停止回归通过。
- acceptance-v5 的资产与源码哈希检查通过；v1–v4 锁、旧计划和原始观测不改写。前端本轮未改，未重跑无关构建或旧付费实验。

本机恢复材料在 backend/target/reading-batch-20261022/，包括 plan-v5.json、批准文件、debug-v5.json.journal.jsonl、后端日志及 database-after-v5.sql。隔离容器 context-pilot-reading-eval-20261022，卷 context_pilot_reading_eval_20261022。旧 database.sql 为付费前快照，**不能用它覆盖现有账本以重置消耗**。

下一步只处理 DEBUG 暴露的“有引用但未回答实际问题”的拒答判定，并补离线回归；不得用 TEST 调 Prompt。修复后发布新实现锁，保留本轮失败与已消费金额，再明确剩余额度及重测清单；当前停止批次不可换 ID 自动重跑。本轮没有新增付费重试或放行 TEST，也不包含实际读者反馈、浏览器冷/热验收与录屏。
