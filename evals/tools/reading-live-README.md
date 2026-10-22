# 阅读固定批次采集入口

本工具只操作本机 `http://127.0.0.1:端口` 的阅读 API，供应商凭据仍只在后端。默认不开启真实调用，没有批准文件时不能采集。所有命令从仓库根目录执行，Node.js 22，无新依赖。**本轮开发未生成有效批准文件，也未执行付费调用。**

## 准备（不调用模型）

先运行默认关闭阅读 AI 的后端；使用隔离评测数据库，避免修改日常书架。`bind` 导入并独立确认四份固定语料，读取实际段落核验 hash；这些是持久化操作，重复导入由现有服务去重，不调用生成或 embedding。不会修改用户其他书籍。

```powershell
node evals/tools/reading-live.mjs bind http://127.0.0.1:8080 backend/target/reading-bindings.json
node evals/tools/reading-live.mjs prepare backend/target/reading-bindings.json backend/target/reading-live-plan.json
```

`prepare` 校验 acceptance-v4 资产和源码锁，写新 plan 及 `reading-live-plan.json.approval.json`。两个文件均拒绝覆盖。批准模板为 `NOT_AUTHORIZED`，时间为空；不是创建授权的接口。计划绑定版本锁、语料文件与段落哈希、真实 book/version/paragraph UUID、固定供应商端点。题目/repeat 和增量索引只从冻结数据集派生，不允许文件中注入任意新任务。

服务端与脚本使用相同的确定性 clientRequestId（batchId + entryId + repeat），数据库现有唯一键保证重复请求不新建任务。修改源码、数据、端点或绑定之后须重新准备并审核，不能修改旧锁让校验通过。

## 独立授权后才启用

将最终 plan 哈希、实现锁、case/repeat、索引清单、价格依据、费用预留方式和准确生效/到期时间交给用户确认。获明确付费授权后，由操作者填写本地批准文件：

- `status=APPROVED`、对应 `planSha256` 和唯一 `authorizationId`；记录用户同意的 `limitCny`，不得扩大路线拟议停止额度。
- `authorizedAt/expiresAt/pricingVerifiedAt` 使用含时区的 ISO 时间；授权与计价快照有效期均有硬限制，见[路线](../../docs/development/novel-reading-roadmap.md)。价格必须在实际授权时重新核实，不能沿用过期网页核对日期。
- `accountConditionsConfirmed=true` 仅在账户按量方案、模型权限等适用条件确认后填写；固定 `pricingVersion=reading-cny-20261022-v1`、`maxCalls=346`。公开价格变更必须更新实现、版本和批准材料，不直接改表中单价。

在后端进程设置 `READING_LIVE_ENABLED=true`、`READING_LIVE_REPOSITORY`（本检出绝对路径）、`READING_LIVE_PLAN` 和 `READING_LIVE_APPROVAL`（两个文件的绝对路径），重新编译/启动。应用启动验证当前源码/资产锁，要求运行刚从该检出构建的后端；不支持脱离源码目录的线上部署。批准文件属于受信任本地运维输入，不是认证机制，不提供 HTTP 创建或增额入口。开关、批准文件、凭据和批次约束缺一不可。

`GET /api/reading/evaluation/status` 展示授权 ID、plan/lock 哈希、金额/次数、到期时间、DEBUG 复核状态、环境和费用口径，不返回密钥或题目。数据库中同一批准 ID 的重复安装不会重置金额、已发次数或冻结时间。删除本地开关/重新启动可停止后续执行，不能撤回已经发出的请求。

## 顺序采集与停止

```powershell
node evals/tools/reading-live.mjs run http://127.0.0.1:8080 backend/target/reading-live-plan.json backend/target/reading-live-plan.json.approval.json backend/target/reading-debug.json DEBUG
```

按语料与边界升序推进固定测试书的已读范围，每次任务/索引前输出最大 HTTP 数与保守预留。两次 TEST 复用成功索引但重新生成回答。`DEBUG` 单独结束，不自动继续 TEST。人工复核 DEBUG 的真实协议、用量、失败和语义后，再由操作者设置后端 `READING_LIVE_DEBUG_REVIEWED=true` 并重启；服务端仍检查全部 DEBUG 任务/索引成功，开关不是语义评分。若需修改 Prompt/实现或重跑，应停止并重新冻结、确认剩余授权，不使用 TEST 调参。

```powershell
node evals/tools/reading-live.mjs run http://127.0.0.1:8080 backend/target/reading-live-plan.json backend/target/reading-live-plan.json.approval.json backend/target/reading-test.json TEST backend/target/reading-debug.json
node evals/tools/reading-eval.mjs report backend/target/reading-test.json
```

无 HTTP 重试或重定向。技术失败、UNKNOWN、超额或过期停止新调用；UNKNOWN 保留预留并冻结该批次。取消/暂停/重启中断也不自动恢复该批次。若提交响应丢失，记录原 clientRequestId 与未解析状态；不生成假 taskId、不重新 POST，不把未知用量按零结算。需人工根据数据库/供应商记录核对，现版不提供自动恢复采集。

输出文件拒绝覆盖。每个检查点向 `.journal.jsonl` 追加完整状态并 fsync；正常/异常退出均保存最终 JSON，强制杀进程时可从最后完整 journal 行人工恢复证据。成功索引费用只归集一次；尚未归集的索引单独计入报告。未采集题保留 NOT_RUN，提交/观测不确定的尝试标 FAILED/UNRESOLVED，并使报告保持不完整。输出可能包含固定语料与供应商正文，保存在 gitignored `backend/target/`，审核后仅提交脱敏证据。

## 费用口径

新增本地 80% 日志提醒、持久停止原因与供应商控制台配置说明见[预算预警与停止](../../docs/evaluation/reading-budget-alerts.md)。当前 v4 需要 V33；旧 v3 plan 保持原字节且不适用于 v4，复用已绑定的语料 UUID 重新 prepare，不重复导入。

字节估算仅用于历史预测，不能当作已验证 token 上界。实际预留改用供应商公开上下文上限：生成每次按完整上下文上限加已限制的输出预留；单查询 embedding 按单行上限，索引每批按最多行数上限预留。没有匹配 tokenizer 的证明也不会低估输入。具体参数、算术及假设见[工程验证](../../docs/evaluation/reading-live-gates-verification.md)。

供应商实际 usage 返回后按高峰未命中单价作保守预算结算并释放差额；这不等于峰谷、缓存或赠金后的真实账单。费用或 token 超过预留边界会冻结并保留证据。全部请求最大预留之和高于拟议停止额度，因此不能保证跑完；只在每次原子预留可满足时发送，不自动追加预算。
