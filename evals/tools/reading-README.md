# 阅读验收工具

2026-10-23 多章回顾格式修复：现场两章/三章任务既有成功也有 JSON_SCHEMA 失败，三次报告均 finishReason=stop；旧回答未保留，具体结构错误不可追溯。Prompt v5 明确统一扁平 facts/partial 合同，回顾按类型检查并持久化细分原因，未放宽校验或自动重试。当前 acceptance-v16，真实改善尚未验证。下一步用户自行重启并手动验证相同范围的多章回顾，按具体错误码继续定位，见 docs/evaluation/reading-recap-schema-verification.md。

2026-10-23 SSE 修复：用户截图显示回顾完成，数据库最近三次回顾也均 COMPLETED。日志是 ReadingHttpAdvice 将已提交 text/event-stream 再写为 JSON 引发的二次异常，原始触发原因未留在所给日志中。现识别断连并禁止对已提交响应追加错误 JSON，未改变任务结果或重跑模型。实现 acceptance-v15 / Prompt v4；下一步用户重启后仅查看已有结果、开关助手验证连接，见 docs/evaluation/reading-sse-error-verification.md。


2026-10-23 回顾失败诊断修复：用户任务 1c5f4739-d33d-4945-8bb4-bcab28a4135c 返回实际 6097/979 token 后 OUTPUT_INVALID；旧数据缺少结束原因，不能确诊。现统一记录停止日志、持久化 failureReason 与白名单 finishReason，页面展示具体类别。当前 acceptance-v14 / Prompt v4；未重试或代启。下一步由用户重启并手动发起日常任务，用明确诊断定位实际故障，详见 docs/evaluation/reading-failure-diagnostics-verification.md。


2026-10-23 用户要求沿用原 Application：已删除新增 IDEA 启动项，在现有 application.yml 内自动导入本地配置，支持仓库根目录/backend 启动。日常仍只修改本地 ai-enabled true/false 并重启原后端；普通测试默认 offline。当前冻结 acceptance-v13，Prompt v4 不变，详见 docs/development/reading-direct-models.md。


2026-10-23 当前默认配置/锁为 acceptance-v12，Prompt reading-evidence-v4。回顾入口与输出约束修复仅有离线证据，历史 v1–v11 保留。前端交互回归：在 frontend 执行 node --test tests/reading-assistant.test.mjs。

同日后续用户批准 DIRECT_LIVE 日常单开关模式，不设金额预算。当前 acceptance-v11 同时包含该模式和停止查询修复；固定评测采集规则不变。日常使用见[开关说明](../../docs/development/reading-direct-models.md)，不使用旧评测/试读脚本。

2026-10-23 当前实现为 acceptance-v11，离线修复试读停止后的状态查询事务异常；Prompt 不变。v10 首次真人回顾已连通 DeepSeek，但结果校验失败，两份试读账本停止。未安装新批准、未恢复或重试。固定评测仍为 DEBUG_RETEST，旧账本保持停止。使用当前配置和锁，下面历史说明保留原范围。详见[失败记录](../../docs/evaluation/reading-trial-failure-verification.md)和[试读入口](../../docs/development/reading-reader-trial.md)。

Node.js 22；从仓库根目录执行，无新增依赖，无 HTTP、模型或 embedding 调用。

```powershell
node --test evals/tools/reading-eval.test.mjs
node evals/tools/reading-eval.mjs validate
node evals/tools/reading-eval.mjs template LIVE_END_TO_END | Out-File -Encoding utf8 backend/target/reading-not-run.json
node evals/tools/reading-eval.mjs report backend/target/reading-not-run.json
```

`template` 永远生成 NOT_RUN，占满冻结的 DEBUG 与两轮 TEST；它不授予付费权限。`report` 读取采集者填写的原始 JSON，不运行应用，不把参考答案填成观测。未运行模板的结论应为 INCOMPLETE，事实/引用率为 null，不能作为验收成绩。

## 固定资产

- [数据集](../datasets/reading/acceptance-v1.json)同时记录语料、段落哈希、边界、问题、参考事实/证据、不可使用事实与风险标签。
- 当前[配置](../configs/reading/acceptance-v9.json)、[冻结清单](../configs/reading/acceptance-v9.lock.json)、[评分协议](../configs/reading/scoring-v1.md)记录模型、Prompt、代码与资产指纹及未就绪条件。v9 的 `datasetVersion` 指向未修改的 v1 数据集，运行版本必须为 v9，不能混用旧观测。本轮仅续读工作台与离线演示，尚无 v9 真实观测或批准文件。
- v1–v8 配置、锁与真实结果保持原字节。复核旧版工具/文档应在对应 Git 提交的独立检出中执行，不能用新工作树覆盖旧锁。当前 CLI 默认使用 v9；旧版源码哈希与当前不同是预期行为。
- [来源与许可](../corpora/reading/acceptance-v1/provenance.md)区分项目自创短篇、改写版和英文原作片段。短语料不能证明真实长篇语义质量。
- 数字预算和质量门槛以[唯一路线](../../docs/development/novel-reading-roadmap.md)为准。分组数量/重复次数是该版本数据集的结构约束，变更要发布新资产版本。

`reading-assets.mjs` 是第一版的作者脚本，使用 `wx` 拒绝覆盖已有语料/配置/题目。普通验证不要重跑它。`freeze <新路径>` 也拒绝覆盖；不能重写旧锁来掩盖变化。修改 Prompt、生产实现、资料或评分方法后保留旧版结果、建立新版本并核对全部引用。`validate` 默认核对生产源码；`report` 只核对资产锁，让历史运行仍能对照其冻结源码清单阅读。锁不含自身哈希；运行文件保存整份锁的 SHA-256。

源码指纹是文件字节 SHA-256，不只依赖 HEAD（本次冻结时存在未提交改动）。LF 由 `.gitattributes` 固定；发现哈希漂移先看 diff 和换行，不直接重冻。

## 采集与人工复核格式

每条 `{caseId,repeat,status,observation,review}` 保留原始失败；`status` 为 NOT_RUN/OBSERVED/FAILED。OBSERVED 仅允许 COMPLETED/PARTIAL/INSUFFICIENT_EVIDENCE。技术失败填 FAILED、`errorCode`，不能伪造拒答评分。

```json
{
  "terminal": "COMPLETED",
  "answer": "实际响应全文，含任何拒答或说明",
  "citations": [{"id": "E1", "text": "实际原文", "sources": []}],
  "calls": [{"kind": "GENERATION", "status": "SUCCEEDED", "inputTokens": null, "outputTokens": null, "reservedCny": null, "settledCny": null}],
  "elapsedMs": 123,
  "taskId": "实际任务 UUID",
  "traceId": "实际 trace UUID"
}
```

`calls` 按实际 HTTP 调用逐条记录（含索引 embedding），不能把一次 Java 包装调用当作一次 HTTP。索引若由多题共享，另外保留独立索引审计附件，在首次使用该索引的运行中归集一次，并记录 jobId/authorizationId，避免漏算或重复计费。未知 usage/结算用 null，UNKNOWN 调用不得填零结算。时间从成功入队起算，失败也保留时长。

LIVE_END_TO_END 已执行记录必须有：

- `environment` 的 codeFingerprint、model、embeddingProfile、os、java、database；可另记浏览器、CPU、内存、队列时间等。
- `bindings[]`：corpusId、corpusSha256、真实 bookId/versionId，以及逐段 `{id:真实UUID,ordinal,sha256}`；不能把数据集逻辑 ID 当数据库定位。
- 单条 observation 的 taskId/traceId。采集者仍须保存实际请求、来源和审计附件；工具不能证明输入 JSON 来自真实服务。

从模块导入 `reviewTemplate(observation)` 可生成未填写评分表；填写[人工协议](../configs/reading/scoring-v1.md)后才能得到指标。复核与整条 observation 的 JSON 序列化哈希绑定，字段内容或顺序变化后也须重验。支持率同时给 pooled/macro 和空答数；可用率与拒答使用固定题数分母；回顾列每题召回；性能按回顾/直接/多线索及每次运行分开。汇总不自动宣告达标。

## 固定批次执行入口

[reading-live.mjs](reading-live.mjs) 和[操作说明](reading-live-README.md)已实现本机绑定、默认未批准模板、DEBUG/TEST 分离采集；后端默认关闭。v2 配置与锁也保持原字节，历史复现使用 d6f3f05 的检出。历史 v3 增加 V32 和本地批准门禁，不代表付费已授权。

## 当前真实验收缺口

v5/v7 已获用户主动授权并执行 DEBUG，两轮均因复核问题停止；保留[最新真实结果](../../docs/evaluation/reading-live-v7-results.md)。v9 保留 v8 的[完整性离线修复](../../docs/evaluation/reading-completeness-verification.md)，新增[续读工作台与隔离演示](../../docs/evaluation/reading-workspace-verification.md)，不能重放旧批准文件。

| 前置项 | 当前状态 | 需提交的证据 |
| --- | --- | --- |
| 阅读专用 DeepSeek / DashScope 适配 | 已有 v5/v7 真实协议及 usage 观测，默认关闭 | 固定模型不变，历史观测不证明 v9 提示词语义效果 |
| HTTP 协议门禁 | 本地通过 | 捕获序列化请求、无自动重试/重定向、整体限时与取消、embedding 不隐藏拆批；见[协议验证](../../docs/evaluation/reading-provider-verification.md) |
| 当前价格版本 | 账户条件已确认，价格快照有时效 | 新执行前复核当时价格和有效期，历史配置与锁不回写 |
| 独立授权 | v5/v7 已批准但均停止，v9 无新批准 | 用户主动发起后绑定新锁、原 DEBUG 清单和唯一前置账本剩余额度；预算与到期时间由路线维护 |
| 实际评测与试用 | DEBUG 未放行，TEST 与读者体验未完成 | 保留失败；新的真实 DEBUG 复核、人工独立评分与真实读者反馈仍需完成 |

以上不是新产品功能授权；它们是已经批准的阅读闭环执行真实评测所需的门禁。本地 HTTP 通过不证明供应商真实支持；实现及测试见[授权采集门禁](../../docs/evaluation/reading-live-gates-verification.md)。
