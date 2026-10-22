# 阅读验收工具

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
- 当前[配置](../configs/reading/acceptance-v2.json)、[冻结清单](../configs/reading/acceptance-v2.lock.json)、[评分协议](../configs/reading/scoring-v1.md)记录模型、Prompt、代码与资产指纹及未就绪条件。v2 的 `datasetVersion` 指向未修改的 v1 数据集，运行版本必须为 v2，不能混用旧观测。
- [v1 配置](../configs/reading/acceptance-v1.json)及[v1 锁](../configs/reading/acceptance-v1.lock.json)保持原字节，作为 `4f13128` 的历史快照；复核旧版工具/文档应在该 Git 提交的独立检出中执行，不能用新工作树覆盖旧锁。当前 CLI 默认使用 v2。
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

## 付费执行前仍缺什么

生产 `ReadingAiConfiguration` 仍拒绝真实调用。当前工具不包含在线采集执行器。

| 前置项 | 当前状态 | 需提交的证据 |
| --- | --- | --- |
| 阅读专用 DeepSeek / DashScope 适配 | 客户端已实现，生产网关仍阻断 | 固定模型不变；序列化输出上限、错误/截断/usage 已有本地证据，真实供应商行为待验证 |
| HTTP 协议门禁 | 本地通过 | 捕获序列化请求、无自动重试/重定向、整体限时与取消、embedding 不隐藏拆批；见[协议验证](../../docs/evaluation/reading-provider-verification.md) |
| 当前价格版本 | 公开资料已核实，币种/地域经用户确认；账户权限及可执行版本未验证 | 见[授权准备](../../docs/evaluation/reading-live-authorization-preparation.md)；还需可靠 token 上界、usage 修复与价格/授权接线；历史配置与锁保持未就绪状态 |
| 独立授权 | 未获得 | 确定批次 case/repeat 清单、索引清单、调用上限、金额与到期时间；旧研究授权不能沿用 |
| 实际评测与试用 | 未执行 | 原始运行、人工独立评分、真实读者反馈与失败记录 |

以上不是新产品功能授权；它们是已经批准的阅读闭环开放真实调用所需的门禁。本地 HTTP 通过不证明供应商真实支持，也不提供计价/授权接线或在线执行器。
