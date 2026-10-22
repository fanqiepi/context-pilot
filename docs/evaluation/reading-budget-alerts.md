# 阅读预算日志与停止控制

最新补充：用户随后明确批准固定批次的分供应商额度，见[路线](../development/novel-reading-roadmap.md)。acceptance-v5 将预警和停止落实到各自独立账本，任何一个停止阻断整批；下文 v4 未授权状态是历史，不代表仍需重复许可。[本轮执行记录](reading-live-v5-results.md)记录实际调用。云端控制台设置仍未改动。

日期：2026-10-22。用户要求增加日志提醒、预算超限停止，并查找 DeepSeek/阿里云官方预警说明。本轮只推进这项费用保护；没有付费批准、账户配置变更或真实模型调用。

## 官方能力与配置说明

- **阿里云百炼**：[预算管理](https://help.aliyun.com/zh/model-studio/budget-management)支持账号、业务空间、API Key 三个粒度的自然月按量推理预算。控制台路径为“费用用量 → 预算管理”，配置预算金额、80% 告警阈值与通知方式，并开启“达到预算后立即停止”。达到预算会限制调用并返回 429；停服有延迟，延迟期费用仍收取。北京属于支持地域。通知可选站内信、短信、邮件或机器人。应优先使用本评测专用 Key 的预算，避免影响同账号其他业务；云端月预算与本地跨供应商批次预算分别配置，不能把两家各设 20 元视为合计 20 元。
- **DeepSeek**：[余额查询](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)提供 GET /user/balance，返回 is_available 及分币种余额；[错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)说明余额不足为 402、速率/并发受限为 429。本轮检索未找到官方公开的同等“自定义预算阈值通知 + 达额停用”配置文档，不把余额查询当作已存在的通知服务。账户余额包含其他调用与赠金，不能代替本应用的批次账本。
- 限流控制单位时间内的请求/token 速度；预算限制累计金额，二者不同。阿里云 429 可能是限流或预算耗尽，本应用保留安全 HTTP 状态码，不读取错误正文来猜测具体原因。

以上是官方文档核实和操作说明，**未进入控制台设置通知、未改变任何云端预算、未调用余额接口或添加自动余额轮询**。供应商侧告警是补充，本地调用前金额/次数原子预留仍是每次请求的执行门禁。

## 已实现行为

- 以“保守预算结算 + 当前预留”衡量额度占用，首次达到批次限额 80% 时输出 WARN；阈值含预留，所以不是已扣款 80%。[V33](../../backend/src/main/resources/db/migration/V33__record_reading_budget_alerts.sql)增加 warning_emitted，使回落再上升、重启及同批准重复安装都不会反复告警。数据库事务回滚不会留下告警标记，也不会输出已提交的提醒。
- 下一次请求的上限预留若无法满足预算，调用前拒绝；任务运行器在失败处理事务中永久停止该批次。已结算金额恰好达到上限也停止。金额、次数的原子约束及 UNKNOWN 不释放预留保持，不能通过重启或重装批准文件恢复额度。
- stop_reason 保存首次停止原因；缺失用量为 USAGE_UNKNOWN，token 超预留为 TOKEN_BOUND_EXCEEDED，金额超预留为 COST_BOUND_EXCEEDED，预算拒绝/用尽为 BUDGET_EXHAUSTED。停止即禁止后续预留/执行，不承诺撤回已发出的请求。
- 状态接口新增 warningThresholdPercent、warningEmitted、stopReason；未启用真实调用时继续只返回 liveAllowed=false 和关闭原因。没有新增授权写入口或用户通知服务。
- 402/429 输出供应商拒绝日志并沿用现有无重试与停止路径；不因供应商返回建议自动充值、提高预算或重试。

| 日志事件 | 含义 |
| --- | --- |
| READING_BUDGET_WARNING | 已提交的额度占用达到 80%，每批只提醒一次 |
| READING_BUDGET_BLOCKED | 本次预留被拒绝；即使工作事务回滚，这次拒绝仍是真实事件 |
| READING_BUDGET_STOPPED | 首次停止状态已提交，带安全原因码 |
| READING_PROVIDER_REJECTED | 收到 402/429，保留 workId/安全 HTTP 码，retry=false |

预算日志仅记录授权/调用 ID、预留/保守结算/限额、累计次数及原因，不包含密钥、问题、原文、模型正文或账户余额。预算类的 WARNING/STOPPED 在数据库提交后输出；进程崩溃边界下日志不提供必达保证，状态接口与数据库标记用于审计。预算金额继续明确标记 PEAK_UNCACHED_BUDGET_VALUE_NOT_INVOICE。

可在后端日志中检索 READING_BUDGET_ 或 READING_PROVIDER_REJECTED。无需开启 DEBUG 级别，也不用打印完整请求。真实调用仍受独立授权和默认关闭开关控制。

## 工程验证与版本

后端相关回归 95 项，0 失败/错误，2 项已有浏览器夹具条件跳过；Node 24 项通过。V33 在 Testcontainers PostgreSQL 成功迁移。新增用例覆盖：提醒事务回滚后不输出/不留标记、达到阈值只输出一次、重复安装不重置、预算不足时供应商调用为零且批次持久停止、usage 超界保留预留和首个原因、恰好用尽阻止下一次调用；原有并发金额/调用数约束与 UNKNOWN 冻结回归通过。HTTP 参数用例补充 402，验证与既有 429 相同的不重试、不暴露正文行为。

执行：backend 下以 JDK 21 运行 ReadingProviderProtocolTests、ReadingLiveAuthorizationTests、ReadingLibraryPersistenceTests、ReadingTaskPolicyTests、ReadingTaskPersistenceTests、ReadingNoteProposalPersistenceTests；根目录运行 node --test evals/tools/reading-live.test.mjs evals/tools/reading-eval.test.mjs。测试仅使用隔离 PostgreSQL 和本地 HTTP/离线模型夹具；未迁移日常库或发出付费请求。

本轮发布 acceptance-v4，保留 v1/v2/v3 的配置与锁。上一轮[最终批次](reading-live-batch-preparation.md)的 v3 plan 与拟议授权材料已被本次实现变更取代，不能用于 v4。复用已导入语料的 bindings，生成新的 plan/批准模板；不重复导入、不改写旧 plan，不把用户对开发的要求解释为付费批准。

原隔离评测容器和命名卷保持停止状态；后续需用新构建应用 V33 后再执行真实授权前核对。已经确认的币种、北京地域、模型开通和按量计费事实保留。任何有效批准文件仍须单独确认固定计划、金额和具体期限。

## 当前待授权计划

复用原 bindings，prepare 已生成 backend/target/reading-batch-20261022/plan-v4.json 与未批准模板；旧 plan.json 未改。

- batchId：304de522-aa8d-44f9-aa46-656d67e92e89
- plan SHA-256：beddebc52e1728d0ff37240753e3dbc5b53a6130d6b48b83c930d0205991292a
- implementation lock SHA-256：6d3b9cda0c19d4f641b173a0b9289c1ed311f6a06460546144220aec6a8064e9
- 批次内容仍为 108 次任务 + 27 个索引作业，最多 346 次 HTTP；拟议停止额度仍为 20 元，没有获批。新模板状态 NOT_AUTHORIZED，时间与账户条件开关仍为空/未设置，不把历史账户确认自动转为执行授权。
- 新批准前需展示本计划与准确到期时间；原 v3 的待确认请求已被替代。
