# 升级研究结果范围调试（2026-10-19）

当前结论：用户已明确宽泛兼容问题的完成语义。经过独立 DEBUG 对照，生产配置已采用 upgrade-research-v6，同步登记新运行的 Prompt 版本；新建研究默认关闭，最终质量验收仍未通过。v6 的四题完整调试终态符合预期，但不代表独立验收或稳定性证明。以下按时间保留各候选未采用时的证据与判断；没有修改冻结预期、强行清除 missingEvidence 或将 PARTIAL 改写为 SUCCEEDED。

## 本轮证据

使用独立 Testcontainers PostgreSQL/pgvector、真实 DashScope 和 DeepSeek，工具调用、来源与运行账本均走现有实现。请求模型 deepseek-v4-flash，调用记录保留供应商实际标识。没有重跑已观察的冻结 TEST。

| 运行 | 来源与实际 Prompt | 结果 | 助手观察（非人工评分） |
| --- | --- | --- | --- |
| [真实 GitHub 调试](../../evals/results/upgrade-closeout-debug-2026-10-19/github-v1.json) | 旧工作进程 DEBUG 文档＋真实 v2.0.0 发布记录；v1 | PARTIAL，3 次模型、4 次工具 | 能指出缺少调度升级依据，但扩展到 OpenAI、Chat Memory、依赖与运维清单；确认保存链路通过 |
| [合成发布基线](../../evals/results/upgrade-closeout-debug-2026-10-19/synthetic-v1.json) | v6-upgrade-debug-v1；v1 | PARTIAL，4 次模型、5 次工具 | 两级优先队列、空优先级 normal、结果格式不变均已读取，仍新增协议、混合部署、回滚等必要缺口 |
| [第一版候选](../../evals/results/upgrade-closeout-debug-2026-10-19/synthetic-v2.json) | 同一 DEBUG 资料及问题；v2 | PARTIAL，3 次模型、3 次工具 | 已明确限定当前问题，仍扩展到版本、灰度、回滚、时延与公平性 |
| [第二版候选](../../evals/results/upgrade-closeout-debug-2026-10-19/synthetic-v3.json) | 同一 DEBUG 资料及问题；v3 | PARTIAL，3 次模型、4 次工具 | 已给出“兼容机制不等于证明所有旧行为不变”的规则，仍新增版本协商等要求；正文还把旧 FIFO 推断为新调度下不变 |

三次合成发布收集测试各 1 项通过，只表示完成观测并归档终态，**不是质量通过**。合成 release 不是 Spring AI 的真实功能。每个变体目前仅运行一次，不能据此估算稳定性、模型间优劣或提升幅度。

合成报告记录 actualPrompt 与源码/输入 SHA。候选由测试手动构造生产执行器并传入资源，因此账本注册仍写生产 v1；以报告 actualPrompt 和资源 SHA 区分实验，不冒称候选已上线。三个 Prompt 文件均保留，生产构造器与注册服务仍指向 v1。

## 归因边界与下一步

用户于 2026-10-19 明确选择暂时继续使用 DeepSeek，不考虑其他模型。后续沿用当前模型定位，不开展跨模型切换；生产默认值不变。

追加三题诊断（同一 DEBUG 来源、候选 v3，未替换原题或冻结预期）：

| 输入 | 观测 | 解释边界 |
| --- | --- | --- |
| [原宽泛兼容题](../../evals/results/upgrade-closeout-debug-2026-10-19/matrix-broad-v3.json) | PARTIAL，继续要求 FIFO 保序和路由依据 | 原问题未解决 |
| [明确只问两项机制](../../evals/results/upgrade-closeout-debug-2026-10-19/matrix-mechanism-v3.json) | SUCCEEDED，缺口为空 | 支持范围理解与问题措辞存在关联，不能用窄题替换原验收题 |
| [加入未提供的恢复时间](../../evals/results/upgrade-closeout-debug-2026-10-19/matrix-missing-v3.json) | REFUSED；两次本地检索未命中，发布读取成功 | 预期 PARTIAL，但缺少本地侧证据导致生产合同拒答；未检验到模型拿到双侧证据时的缺口判断 |

这三次收集完成，无工程断言失败，但只有窄题终态符合诊断预期，不是语义质量分数。首次参数化运行还出现测试夹具知识库重名，导致后两题在准备阶段失败；已修正每题独立名称后重跑，首次已完成的[原题记录](../../evals/results/upgrade-closeout-debug-2026-10-19/matrix-initial-broad-v3.json)也保留。准备错误不计为模型失败。

目前仍有检索与生成混杂因素。下一步应固定双侧证据做综合输出对照，而不是继续用不同命中结果判断模型优劣。

### 固定证据的追加结果

已执行固定同一双侧 DEBUG 证据的综合对照，跳过检索，人工构造工具历史，只调用一次模型并禁用后续工具。这属于 MODEL_FIXED_EVIDENCE，不是完整生产流程或独立验收。

首次构造普通 AssistantMessage 缺少供应商工具历史要求的 reasoning_content 字段，调用返回 HTTP 400。两批 6 次及单次诊断分别保留在 [首次](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-invalid-history.json)、[异常类型](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-error-diagnosis.json)、[协议定位](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-protocol-diagnosis.json)。这是测试夹具错误，不能计为模型答错，也不能据此推断历史六项生产调用异常的根因。没有返回 usage 的调用保持未知。

夹具改用 DeepSeekAssistantMessage 并显式提供空 reasoning_content 后，6 次均返回正文，[原始结果](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-v1-v3.json)显示：

- v1 的宽泛题和恢复时间题返回了工具协议文本，未满足最终 JSON 合同；窄题返回 JSON，但仍列出超出要求的缺口。
- v3 的三题均返回可解析 JSON；窄题没有缺口，恢复时间题正确保留该数值缺口，原宽泛题仍额外要求 FIFO 顺序不变的依据。
- 因而检索不是唯一因素；当前模型与 Prompt 的范围判断及协议遵循都还需要验证。每个组合仅一次，不生成语义通过率。

随后在 v1 的最后一轮追加显式“工具阶段已结束、只能输出 JSON”的指令，再跑三题，[结果](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-explicit-final-v1.json)为两次可解析 JSON、一次 JSON 对象提前闭合且尾随字段；范围问题仍存在。该候选文件 upgrade-research-final-v1 也**未接入生产**，不能声称已修复协议问题。生产网关和 Prompt 版本保持原样，无额外重试、预算放宽、格式修复或强制成功。

固定证据收集命令：

```powershell
.\mvnw.cmd -q '-Dtest=UpgradeScopeDebugLiveTests#collectFixedEvidenceSynthesis' -Dcontextpilot.evaluation.upgrade-scope-debug=true -Dcontextpilot.evaluation.upgrade-fixed-evidence=true test
# 仅显式最后一轮候选实验追加：
-Dcontextpilot.evaluation.fixed-prompt=upgrade-research-v1 -Dcontextpilot.evaluation.fixed-final-instruction=true
```

原 Prompt 强制广泛迁移清单是诱因之一；进一步约束后仍出现问题，表明当前模型/任务组合的指令遵循和事实约束值得独立对照。不能仅凭单模型小样本排除问题措辞歧义、工具上下文或抽样波动，也不能直接声称更换模型必然解决。

历史六项 UPGRADE_EXECUTION_FAILED 在失败前最后一次模型调用没有 usage；本轮四个调试运行未复现，不能将其归因于提供商、宣称已修复或记为零消耗。已有安全根异常类型/位置日志保留，不输出密钥或完整请求。

2026-10-19 用户确认暂时继续使用 DeepSeek，不考虑其他模型。后续固定同一输入与评分要求，在当前模型上隔离问题措辞、检索证据、工具消息协议和最终输出约束；不开展跨模型对照。生产默认模型保持不变，不增加调用预算、不放宽来源或引用门禁。完整独立验收、人工事实/引用核查及 16 次代表生产运行仍未完成。

运行命令：

```powershell
.\mvnw.cmd -q -Dtest=V6LiveDebugTests -Dcontextpilot.evaluation.v6-debug=true test
.\mvnw.cmd -q -Dtest=UpgradeScopeDebugLiveTests -Dcontextpilot.evaluation.upgrade-scope-debug=true test
# 候选分别追加以下一个参数：
-Dcontextpilot.evaluation.upgrade-debug-prompt=upgrade-research-v2
-Dcontextpilot.evaluation.upgrade-debug-prompt=upgrade-research-v3
```

### 最终综合消息形态对照

2026-10-19 追加候选 v3 三题 × 两种输入形态，均为独立 DEBUG、单次真实 DeepSeek 调用、60 秒上限，不接入生产：

- [证据包组](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-envelope-v3.json)：系统 Prompt、原问题、显式 untrusted 双侧证据包、结束工具阶段指令。
- [工具历史对照组](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-history-control-v3.json)：同一 Prompt、原问题、合成助手工具请求及工具响应、与证据包组完全相同的结束阶段指令。

证据包组相对前一批同时改变了历史形态和结束阶段指令，因此补跑工具历史对照组以保持结束指令一致。两组保留相同事实、来源 ID 和问题；工具历史带有调用名/参数及响应包装，故结论只针对两种整体消息表达方式，不能分离具体包装字段的影响。每组每题仅一次，不测量稳定性或统计显著性。

[结构检查](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-message-shape-summary.json)：6 次均返回可解析 JSON，字段恰为 answer、missingEvidence。两组的宽泛题都列 FIFO 顺序缺口，明确机制题都没有缺口，恢复时间题都保留该真实缺口。由此本次不支持“换成证据包即可消除范围分歧”；也不支持历史 v1 的 JSON 异常已经修复。收集测试均通过仅表示执行完成，未产生人工质量分数。

这里需要产品语义决定：“新调度方案如何兼容旧客户端？”是要求介绍已知兼容机制，还是评估旧行为整体兼容？资料没有说明同级 FIFO 是否保留；原始预期 SUCCEEDED 与模型持续给出的缺口存在解释分歧。已向用户提交两种解释，决定前保留原预期与全部结果，不缩窄原题、不强制成功。当前未将这个问题归因于 DeepSeek 能力上限。

协议依据另行核对了 [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/) 与 [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)：现有网关已设置 JSON_OBJECT、Prompt 含 JSON 示例，末轮使用 tool_choice=none。文档中的格式要求不能代替真实输出验证；应用严格解析、引用门禁和失败状态继续保留。

复现时使用既有固定证据命令并指定 `-Dcontextpilot.evaluation.fixed-prompt=upgrade-research-v3`；证据包组追加 `-Dcontextpilot.evaluation.fixed-evidence-envelope=true`，工具历史对照组追加 `-Dcontextpilot.evaluation.fixed-stage-instruction=true`。两者不要同时设置旧的 `fixed-final-instruction`，否则会多加另一版指令。

2026-10-19 后续决定：用户已确认宽泛兼容题回答已知机制，未承诺行为作为范围说明；详见设计文档的同日澄清。新增 v4 候选验证该决定，暂未接入生产。上文待确认描述为当时的实验过程记录。

### v4 候选与明确保证对照

用户选择已写入设计合同。v4 候选直接定义宽泛兼容题默认解释，并区分范围说明与必要缺口；仍未接入生产，生产 Prompt 版本仍为 v1。

[首次三题](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-v4-initial.json)中，宽泛兼容题与明确机制题均返回空 missingEvidence；宽泛题明确未证明 FIFO 保序，未把它写成已保持。[明确 FIFO 保证题](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-v4-guarantee.json)则明确不能确认保序，并保留必要缺口。以上为助手观察，不是人工语义评分；回答仍有冗长和范围外建议，不能仅凭终态宣称已验收。

首次恢复时间题失败于约 10 秒的 `io.netty.handler.timeout.ReadTimeoutException`，没有正文与 usage，不计通过、也不计零消耗。核对实际 Spring Web 7.0.8 字节码发现 ReactorClientHttpRequestFactory 默认 responseTimeout 为 10 秒，升级研究独立创建的 RestClient 未覆盖该默认值。已为升级研究模型客户端显式设置既有 60 秒读取上限；应用单次硬截止、180 秒总截止、无重试合同不变，发布记录 HTTP 的 10 秒限制不变。

[网关专项](../../evals/results/upgrade-closeout-debug-2026-10-19/http-timeout-tests.txt)3 项通过：新测试用本机 HTTP 服务延迟 11 秒响应，配置后的生产请求工厂成功读取且服务端只收到一次请求；原输入预算与末轮禁止工具／不设置 max_tokens 测试继续通过。此证据解释本次超时机制，不能追认历史六项 UPGRADE_EXECUTION_FAILED 全部同因或已修复。

读取超时对齐后，单独补跑[恢复时间题](../../evals/results/upgrade-closeout-debug-2026-10-19/fixed-evidence-v4-missing-after-transport.json)，真实调用约 14.1 秒后返回合法 JSON，保留恢复时间缺口，未编造数值。这次实际跨过原 10 秒默认阈值，但仍不是稳定性或完整生产流程验收。

### v4 完整独立调试（真实检索与工具循环）

读取超时修正后，以候选 v4 手动构造生产执行器完成三题：

| 题目 | 结果 | 模型／工具调用 | 证据 |
| --- | --- | --- | --- |
| 宽泛兼容题 | SUCCEEDED | 4／4 | [原始记录](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v4-broad.json) |
| 明确机制题 | SUCCEEDED | 2／2 | [原始记录](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v4-mechanism.json) |
| 缺失恢复时间题 | PARTIAL | 3／5 | [原始记录](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v4-missing.json) |

三题终态与诊断预期一致，真实 DashScope 检索、DeepSeek 工具循环、来源账本和最终解析均执行；release 仍是明确标注的合成资料。宽泛题曾检索未命中，之后在同范围恢复证据；最终状态基于已取得账本，没有降低相似度门槛。三项收集测试通过不是人工质量分数，也不是最终独立验收。

正文仍出现“旧工作进程仍按 FIFO”及新旧工作进程并存的假设，虽可解释为旧版描述，但升级语境容易混淆版本事实。因此尚不采用 v4；新增 v5 候选要求旧版事实明确限定版本、不推断升级后保持、不假定混合部署、减少无关范围列举，并保留空值与省略字段的事实限定。追加明确 FIFO 保证题进入完整调试；不修改原 DEBUG 题或历史记录。

### v5 完整调试未采用

四题均运行到 PARTIAL：[宽泛题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v5-broad.json)、[机制题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v5-mechanism.json)、[恢复时间题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v5-missing.json)、[FIFO 保证题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v5-guarantee.json)。前两题不符合诊断预期；后两题虽为 PARTIAL，仍含额外缺口。模型把已取得本地证据之后的可选查询未命中视为来源缺失，并要求发布明示事实必须取得本地重复证明。v5 不采用，不把后两题终态正确记为语义通过。

新增 v6 候选对齐已有生产规则：按最终来源覆盖判断缺口，已经恢复的文档不因额外查询未命中而重复报缺；每项事实可由对应一侧证据支持，整体报告仍需双侧引用。保留旧版事实的时间归属约束，撤回 v5 引发过度展开的空值输入枚举，不增加模型预算、检索工具或降低相似度门槛。

### v6 调试结果与采用决定

四题完整真实检索／模型流程均取得双侧依据，终态依次为 SUCCEEDED、SUCCEEDED、PARTIAL、PARTIAL；对应[宽泛题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v6-broad.json)、[机制题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v6-mechanism.json)、[恢复时间题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v6-missing.json)、[FIFO 保证题](../../evals/results/upgrade-closeout-debug-2026-10-19/full-v6-guarantee.json)。模型调用为 2、2、3、3 次，工具请求为 2、3、3、3 次；未放宽预算或检索阈值。正文区分了旧版事实与新版声明，明确保证和恢复时间仍保留缺口；仍有重复表述及可选建议，不宣称全面质量通过。

采用 v6 为生产 Prompt，同时将 UpgradeRunStateService 新运行登记版本改为 upgrade-research-v6。上述实验采用前的登记字段仍为 v1，以 actualPrompt 和源码哈希识别真实候选；历史记录不修改。v1–v5 文件保留用于复现，既有冻结评估工具仍保留原基线配置，不将其重跑冒充独立验收。功能默认关闭与未通过最终验收的结论不变。

采用后的升级研究工程回归完成：44 项，42 通过、2 个 opt-in 入口跳过、0 失败／错误，正常退出；见 [回归摘要](../../evals/reports/2026-10-19-upgrade-prompt-adoption.json)。这不是全后端回归或真实模型独立验收。
