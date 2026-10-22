# 阅读人物查证完整性离线修复

2026-10-22，基于干净 develop/6c0ff9c；fetch 后 origin/develop 同步，origin/main 为 f80537c（PR #39 合并记录）。本轮按当前交接只修复 FULL/PARTIAL 判断与依据组织。**Prompt reading-evidence-v3，实现 acceptance-v8；工程回归通过，真实模型效果未复测。**

## 问题与修正

[v7 DEBUG 复核](reading-live-v7-results.md)记录两条事实有依据但标记过于保守的回答：debug-006 问处理方式，原文已有换线后重新出纸的直接答案；debug-011 问是否及依据，直接叙述与人物澄清均已检索到，但回答只组织了澄清。问题不能归因为未检索到，也不能仅凭 PARTIAL 终态判断语义失败。

本轮仅修改人物回答的提示规则：

- 完整性按用户实际问到的事项判断，单一事项已有明确答案不因答案简短、无关背景未复述或未知后续降级。
- 逐项核对全部已给证据，再组织答案。依据充分但草稿遗漏时先补齐；PARTIAL 只用于至少一项可答且另一项确实缺少依据，不把组织遗漏冒充检索缺口。
- 问处理方式时组织直接相关背景、实际行动和已发生结果；问是否及依据时组织直接叙述与相关人物澄清，保留说法归属并分别引用，不推断未记载原因或后续。
- 保留 v2 的拒答规则：要求、计划、预测或人物说法不证明实际发生；没有任何可回答部分时空 facts 并返回 INSUFFICIENT_EVIDENCE。文本要求或人物说法本身仍可被查证。

没有增加具体人物、题号或内容关键词的生产分支。沿用原来的同一次生成、answerability/facts 模型协议和公开 Result；服务端不把 PARTIAL 强行改成 FULL。检索、范围、调用上限、响应长度、计价与确认笔记合同不变；无前端、数据库迁移或生产依赖变更。需求、API 和数据合同无需变更。

## 离线证据与局限

[ReadingLookupCompletenessTests](../../backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingLookupCompletenessTests.java)直接读取已提交 v7 原始观测的题目、边界和 coverage.evidenceRanges，从固定 DEBUG 原文重建检索段落输入，不扩展证据范围。保留历史 PARTIAL 响应对应的处理结果，另外以手写 FULL 响应检查两组相关依据的引用和公开结果。生成的本地输入和合成响应写入 `backend/target/reading-completeness-offline/`，不写入真实 results 或评分。

另有不同人物/事件的合成例子：单项完整答案、多项中的真实缺口、全部未知、计划本身可回答但不等于次日实际结果。既有 v5 拒答样本、非法/矛盾协议、引用与长度验证，以及任务持久化的 FULL/PARTIAL/拒答/失败、usage 和调用数检查继续回归。

这些测试验证提示规则装配、范围输入和应用协议处理，**不验证模型是否会遵守规则或正确选择 answerability**。特别是，语义上错误但结构合法的 FULL/PARTIAL 仍可能通过现有校验；本轮没有新增自动语义判分器。没有修改 TEST 问题、参考事实、评分或重复次数，没有把手写答案伪装成模型输出。

## 验证

Windows / JDK 21.0.3，全部使用本地/离线替身或 Testcontainers：

- `backend/` 执行 `./mvnw.cmd "-Dtest=Reading*Tests" test`：106 项，0 失败/错误，2 项既有浏览器条件跳过。包含阅读导入、范围、任务、取消/迟到结果、预算与停止、笔记原子确认、本地供应商 HTTP 协议。
- 最终将“故障背景”泛化为“背景、实际行动”后，重跑 ReadingTaskPolicyTests/ReadingLookupCompletenessTests：10 项通过。
- 根目录 `node --test evals/tools/reading-eval.test.mjs evals/tools/reading-live.test.mjs`：27 项通过，保持 DEBUG/TEST 隔离、索引复用、拒绝未批准采集和未知用量停止。
- acceptance-v8 的资产/源码哈希核验、旧锁及结果原字节检查、文档链接和 diff 检查通过。新锁包含 v5/v7 调试观测和新增回归测试，数据集仍为 v1。

浏览器条件跳过不构成体验验收；前端未改，不运行无关构建。测试日志保存在本地 `backend/target/reading-completeness-unit.log` 和 `reading-completeness-regression.log`。

## 冻结与下一步

当前 CLI、后端新批次版本绑定 acceptance-v8；v1–v7 配置/锁与全部历史结果保持原字节，旧版完整复现应使用对应 Git 检出。本轮没有生成或安装 v8 可执行 plan/approval，没有付费调用、解除停止、修改旧账本或启动原隔离评测服务。金额、到期时间与后续执行范围仅由[当前路线](../development/novel-reading-roadmap.md)维护。

后续候选清单与重点已写入[交接](../development/continuation-handoff.md)：仅在用户主动发起后准备固定 DEBUG 重测，承接 v7 最新停止账本的剩余额度，重新核验时效/计价。原 v5 前置已被承接，不能重复使用。TEST 96 次仍 NOT_RUN，正式语义评分、读者体验和展示验收未完成；本轮完成不授权下一次付费执行。
