# 阅读固定批次采集入口

本工具只操作本机 `http://127.0.0.1:端口` 的阅读 API，供应商凭据仍只在后端。默认不开启真实调用，没有批准文件时不能采集。所有命令从仓库根目录执行，Node.js 22，无新依赖。v5 已获用户批准并执行 DEBUG，但因语义失败持久停止，见[执行记录](../../docs/evaluation/reading-live-v5-results.md)。当前 v7 仅接受用户明确发起的 DEBUG 重测，不能用模板额度重置此前消耗。

## 准备（不调用模型）

先运行默认关闭阅读 AI 的后端；使用隔离评测数据库，避免修改日常书架。`bind` 导入并独立确认四份固定语料，读取实际段落核验 hash；这些是持久化操作，重复导入由现有服务去重，不调用生成或 embedding。不会修改用户其他书籍。

```powershell
node evals/tools/reading-live.mjs bind http://127.0.0.1:8080 backend/target/reading-bindings.json
node evals/tools/reading-live.mjs prepare backend/target/reading-bindings.json backend/target/reading-live-plan.json
```

`prepare` 校验 acceptance-v7 资产和源码锁，写新 plan 及 `reading-live-plan.json.approval.json`。两个文件均拒绝覆盖。executionScope 固定为 DEBUG_RETEST。批准模板为 `NOT_AUTHORIZED`，时间、金额、前置账本信息均为空；不是创建授权的接口。计划绑定版本锁、语料文件与段落哈希、真实 book/version/paragraph UUID、固定供应商端点。题目/repeat 只从冻结数据集 DEBUG 派生；不允许新增索引，不允许文件中注入任意新任务。

服务端与脚本使用相同的确定性 clientRequestId（batchId + entryId + repeat），数据库现有唯一键保证重复请求不新建任务。修改源码、数据、端点或绑定之后须重新准备并审核，不能修改旧锁让校验通过。

## 独立授权后才启用

将最终 plan 哈希、实现锁、case/repeat、索引清单、价格依据、费用预留方式和准确生效/到期时间交给用户确认。获明确付费授权后，由操作者填写本地批准文件：

- `status=APPROVED`、对应 `planSha256` 和唯一 `authorizationId`；记录用户同意的 `providerLimitsCny.DEEPSEEK` 和 `providerLimitsCny.DASHSCOPE`；`limitCny` 必须等于二者之和，各自不得超过[路线](../../docs/development/novel-reading-roadmap.md)批准额度，不能挪用。
- `authorizedAt/expiresAt/pricingVerifiedAt` 使用含时区的 ISO 时间；授权与计价快照有效期均有硬限制，见[路线](../../docs/development/novel-reading-roadmap.md)。价格必须在实际授权时重新核实，不能沿用过期网页核对日期。
- `accountConditionsConfirmed=true` 仅在账户按量方案、模型权限等适用条件确认后填写；固定 `pricingVersion=reading-cny-20261022-v1`、`maxCalls=32`。公开价格变更必须更新实现、版本和批准材料，不直接改表中单价。

还必须填写 previousAuthorizationId（旧 DeepSeek 账本 ID）和 previousApprovalDigest（旧批准文件原字节 SHA-256）；新额度严格等于两个旧账本各自的 limit_amount - settled_amount。服务端要求它们已停止、reserved_amount=0、主体/计价/币种匹配，且没有其他后继。V34 唯一约束包含已逻辑删除的后继，不能通过删除或换 ID 重复承接。

在后端进程设置 `READING_LIVE_ENABLED=true`、`READING_LIVE_REPOSITORY`（本检出绝对路径）、`READING_LIVE_PLAN` 和 `READING_LIVE_APPROVAL`（两个文件的绝对路径），重新编译/启动。应用启动验证当前源码/资产锁，要求运行刚从该检出构建的后端；不支持脱离源码目录的线上部署。批准文件属于受信任本地运维输入，不是认证机制，不提供 HTTP 创建或增额入口。开关、批准文件、凭据和批次约束缺一不可。

`GET /api/reading/evaluation/status` 展示授权 ID、plan/lock 哈希、金额/次数、到期时间、DEBUG 复核状态、环境和费用口径，不返回密钥或题目。数据库中同一批准 ID 的重复安装不会重置金额、已发次数或冻结时间。删除本地开关/重新启动可停止后续执行，不能撤回已经发出的请求。

## 顺序采集与停止

```powershell
node evals/tools/reading-live.mjs run http://127.0.0.1:8080 backend/target/reading-live-plan.json backend/target/reading-live-plan.json.approval.json backend/target/reading-debug.json DEBUG
```

采集前在固定 DEBUG 语料最大边界验证已有完整索引；缺失即停止，不创建索引。之后按原边界升序运行 12 个任务，每次输出最大 HTTP 数与保守预留，引用和查询仍受该题范围限制。索引复用元数据记录在 reusedIndexes，不把旧索引费用重复计入。仅 DEBUG 一次；CLI 在 HTTP 访问前拒绝 TEST，服务端允许清单也不包含 TEST/INDEX，debug-reviewed 不能覆盖这一限制。结束后复核原始事实/引用/usage，持久停止两个新账本并保存状态与账本快照。本轮不自动重试、不继续 TEST。

无 HTTP 重试或重定向。技术失败、UNKNOWN、超额或过期停止新调用；UNKNOWN 保留预留并冻结该批次。取消/暂停/重启中断也不自动恢复该批次。若提交响应丢失，记录原 clientRequestId 与未解析状态；不生成假 taskId、不重新 POST，不把未知用量按零结算。需人工根据数据库/供应商记录核对，现版不提供自动恢复采集。

输出文件拒绝覆盖。每个检查点向 `.journal.jsonl` 追加完整状态并 fsync；正常/异常退出均保存最终 JSON，强制杀进程时可从最后完整 journal 行人工恢复证据。成功索引费用只归集一次；尚未归集的索引单独计入报告。未采集题保留 NOT_RUN，提交/观测不确定的尝试标 FAILED/UNRESOLVED，并使报告保持不完整。输出可能包含固定语料与供应商正文，保存在 gitignored `backend/target/`，审核后仅提交脱敏证据。

## 费用口径

新增本地 80% 日志提醒、持久停止原因与供应商控制台配置说明见[预算预警与停止](../../docs/evaluation/reading-budget-alerts.md)。当前 v7 需要 V34；旧 v3–v6 plan/lock 保持原字节且不适用于 v7，复用已绑定的语料 UUID 重新 prepare，不重复导入。

字节估算仅用于历史预测，不能当作已验证 token 上界。实际预留改用供应商公开上下文上限：生成每次按完整上下文上限加已限制的输出预留；单查询 embedding 按单行上限，索引每批按最多行数上限预留。没有匹配 tokenizer 的证明也不会低估输入。具体参数、算术及假设见[工程验证](../../docs/evaluation/reading-live-gates-verification.md)。

供应商实际 usage 返回后按高峰未命中单价作保守预算结算并释放差额；这不等于峰谷、缓存或赠金后的真实账单。费用或 token 超过预留边界会冻结并保留证据。全部请求最大预留之和高于拟议停止额度，因此不能保证跑完；只在每次原子预留可满足时发送，不自动追加预算。
