# 阅读人工评分协议 v1

数字门槛只以 `docs/development/novel-reading-roadmap.md` 为准。工具给出分子、分母、pooled、每题宏平均、遗漏与缺测，不自动宣布产品验收通过。

1. 冻结输入后，DEBUG 调试与 TEST 验收分开。每个 TEST 问题两次无答案缓存采集；NOT_RUN、技术失败、超时全部保留。不能用覆盖记录或选择性重试替换第一次失败。
2. `observation` 保留实际返回的 `answer`、`terminal`、`citations`、`calls`、`elapsedMs`、`taskId`、`traceId`、执行环境/代码指纹；实际数据库定位映射写入 `bindings`。固定替身填 `OFFLINE_FIXTURE`，真实端到端才填 `LIVE_END_TO_END`。本工具不发送 HTTP 请求。
3. 复核前阅读实际全文与当前已读原文，逐项拆分全部可核查事实。`facts` 中写实际断言、supported/unsupported/contradictory/spoiler、事实绑定的**每一条**引用及其 supported/unsupported。无引用的事实由工具自动增加一条失败引用关联。未引用事实也必须拆项，不能只挑正确句子。
4. `coveredExpectedFactIds` 只标有支持的实际断言覆盖的参考事实；每个条目必须存在于该题参考集合，重复不计。`fatalError`、`spoiler`（包括暗示、正文以外的标题或问题回显）、`correctRefusal` 由人工逐项判断；禁止被测模型给自己评分。`allClaimsReviewed=true` 表示复核者承诺没有遗漏输出中的断言。
5. 每条复核绑定 `observationSha256`，回答或引用变动即失效。需填写真实 `reviewer`、`reviewedAt` 和 `rationale`，待办模板留空。人工自填的断言覆盖仍需复查，本工具无法证明评分诚实或语义正确。
6. 事实率按输出断言计算；引用率按事实—引用关联计算。没有事实的回答分母为零、rate=null，不记满分；宏平均只包含有分母的题，另报空答题数和未评分数。可回答题可用率分母固定，至少覆盖一个正确关键事实且没有致命错误或剧透才可用。回顾召回按参考事实去重计算，并报告每题结果。
7. 无证据题正确拒答必须同时满足人工确认、没有不支持的断言/引用、无剧透且无致命错误。技术失败不能算正确拒答。未执行或未复核状态不变成零错误或通过。
8. 一例剧透即 `BLOCKED_SPOILER`。其余结果只标 `INCOMPLETE` / `REVIEW_COMPLETE`，后者只代表采集与人工表完整。真实试用、协议、费用、性能和工程门禁仍需独立核对。

成本按调用记录保留 `inputTokens/outputTokens`、`reservedCny/settledCny`。未知项使用 null，不能写 0；工具分列已知合计与未知数量，不估价、不结算、不授权。首版没有生成缓存，重复运行不能复用已有答案。
