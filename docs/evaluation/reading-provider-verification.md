# 阅读供应商适配与本地协议验证

日期：2026-10-22。起点：干净的 `develop/4f13128`，远程相同，PR #31–#35 已合并。用户授权继续开发，本轮只收尾“验证续读体验并准备展示”的专用适配和本地协议门禁。**真实模型/embedding 调用为零、付费授权为零，整体语义及体验验收未完成。**

## 实现与边界

`ReadingProviderClient` 是包内阅读专用客户端，通过 `ReadingAiGateway` 隔离。直接使用项目已有 WebClient/Reactor Netty，固定生成模型 `deepseek-v4-flash` 和向量模型 `qwen3.7-text-embedding` / 1024 维；不新增依赖，不修改旧 Spring AI、V4/V5 配置，不增加业务 API、迁移或前端行为。生产 `ReadingAiConfiguration` 已委托协议实现，但所有网络方法前仍无条件抛出 `LIVE_AI_NOT_AUTHORIZED`；配置密钥不会开启调用，也没有新增启用开关。

生成协议将既有 Prompt 中的系统规则与不可信用户/证据数据分成两个消息，显式传递阅读输出上限、非流式、关闭思考和 JSON 输出格式。完整序列化请求计入路线规定的输入上限与预留上界；逻辑 Prompt 未修改，但线上消息包装改变，因此更新实现冻结版本。

一次有效调用只发一个 HTTP 请求；新连接禁用 HTTP 自动重试及重定向，不使用会自动拆批的 SDK。整体截止包含连接、响应头及响应体，线程中断取消订阅。生成响应受现有原始输出字节上限约束（包含 HTTP JSON 包装）；embedding 使用独立的 1 MiB 传输保护上限，以容纳已批准批次的向量 JSON。响应必须为 JSON、合法 UTF-8，拒绝重复 JSON 字段/尾随内容、错误向量数量/维度/索引/零向量/非有限值。HTTP 错误只保留状态码，不记录正文或密钥。

embedding 的 `prompt_tokens` 传入原有调用审计和结算；缺失/非法用量保留 UNKNOWN。供应商结束后即审计 usage，本地检索或发布失败不能覆盖为未知。生成 `length` 等结束原因以及输出 token 超限均不得发布；可获得的 usage 仍保留。没有供应商响应的失败维持未知计费和预留，不能自动免费重发。

## 官方协议参考与未解决差异

后续核实见[真实调用授权准备](reading-live-authorization-preparation.md)：公开文档已补足旧 DeepSeek 名称兼容和北京共享域名依据，并提供公开价格；同时发现 embedding `total_tokens` 与此版客户端读取字段不符。下文保留适配轮当时的调查状态及本地测试结论，不能把它们当作真实供应商验收。

2026-10-22 只读取公开文档，没有请求付费 API：

- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)：依据消息、输出上限、thinking、结束原因和 usage 字段实现。当前文档枚举展示 `deepseek-flash`，仓库已批准模型为 `deepseek-v4-flash`；本轮保留原选择，别名兼容性尚无实际供应商证据，不能静默替换模型。
- [DashScope 文本向量同步接口](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)：依据 OpenAI 兼容的 input、dimensions、encoding_format、data.index 和 usage 实现。文档当前展示工作空间专用域名，仓库使用 `dashscope.aliyuncs.com/compatible-mode/v1`；保留既有固定端点，账户区域及模型可用性待核对。

本地 stub 接受这些字段只能证明客户端发送正确，不能证明线上供应商执行输出限制、兼容模型名/端点或实际计费。价格信息未形成经过核实的授权计价版本；价格、模型/端点兼容、生产授权接线和确定批次仍是下一步前置材料。

## 执行与结果

环境：Windows 11、JDK 21.0.3、Node 22.12.0；Testcontainers PostgreSQL/pgvector 隔离数据库迁移到 V31。未修改日常数据库。

```powershell
cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingProviderProtocolTests,ReadingLibraryPersistenceTests,ReadingTaskPolicyTests,ReadingTaskPersistenceTests,ReadingNoteProposalPersistenceTests' test
cd ..
node --test evals/tools/reading-eval.test.mjs
node evals/tools/reading-eval.mjs validate
```

- 后端 74 项，0 失败/错误、2 跳过（既有手动浏览器夹具）；其中 26 项本地协议测试，涵盖三个生成输出上限、单查询/16 条 embedding 单批、usage 缺失、HTTP 302/401/429/500/503、断连接、响应体等待、超时/中断、截断、恶意结构和容量边界。
- 数据库集成新增本地 HTTP 索引→直接人物查证：恰好 3 个请求，索引和查询各一次 embedding、回答一次生成；用量及完整包装字节记录核对。另一次截断回顾记录 usage、任务失败且无可发布正文。
- Node 工具 17 项通过，增加实现版本与数据集版本分离、拒绝混用旧运行版本的验证。v2 模板仍为 108 条 NOT_RUN，语义成绩为空。
- 当前资产/源码哈希均 MATCH；旧 v1 语料、题目、评分、配置与锁经 Git 核对未修改。变更 Markdown 本地链接及 `git diff --check` 通过；本轮文件的保留路径快照经 Gitleaks 扫描为 0 命中，未放宽规则。可提交的测试汇总见[checks.json](../../evals/reports/reading-provider-v1/checks.json)。
- 本轮没有前端改动，因此未重跑构建或浏览器流程；此前页面和容量证据仍仅适用于原报告，不冒称本轮复验。

首次协议测试发现极大向量数值转换会直接抛 Jackson 异常；已改为统一安全 `OUTPUT_INVALID`，随后上述完整相关回归通过。首次日志保留于 gitignored `backend/target/reading-provider-protocol.log`，通过日志为 `backend/target/reading-provider-regression.log`；不提交可能包含环境细节的完整运行日志。

## 冻结与后续

当前[acceptance-v2 配置](../../evals/configs/reading/acceptance-v2.json)与[锁](../../evals/configs/reading/acceptance-v2.lock.json)冻结变更后的实现，继续使用未改动的 v1 语料、题目及评分协议。v1 配置、锁和既有证据保持原样；历史复现使用 `4f13128` 的独立检出。本轮不使用 TEST 调 Prompt，不产生参考答案替代观测。

下一步准备固定模型/端点兼容依据、官方计价、明确批次与账本授权接线，再单独请求付费授权。实际采集、人工语义评分、真实读者反馈、浏览器冷/热性能和录屏均未完成。本轮交付后停止并提醒提交 PR。
