# 小说阅读开发交接

更新：2026-10-22。用户已批准 `reading-contract-v1-draft1`，本次要求核实线上模型/端点与价格并准备授权；只完成公开资料核实及候选批次文档，没有启用真实调用。每轮只推进一个大功能点，相关检查通过并审阅 diff 后主动创建本地提交，使用中文提交信息，再停止并提醒发起 PR；执行细则见 [AGENTS.md](../../AGENTS.md)。**整项体验和语义验收仍未完成。**

## 起点与 Git

先读[项目背景](project-context.md)、[AGENTS.md](../../AGENTS.md)、[唯一路线](novel-reading-roadmap.md)，再核对 Git 和平台。本轮起点为干净的 `develop/144726e`，`git ls-remote` 核对远程 develop 相同，已包含适配提交 `1ab0968` 和提交规范 `144726e`。本轮没有查询新的 PR 状态；PR #31–#35 已合并是上一轮记录。最终工作区及远程状态以 Git 为准。

## 本轮交付

[授权准备记录](../evaluation/reading-live-authorization-preparation.md)已列明：官方模型映射、固定端点兼容依据、人民币公开单价、108 条 case/repeat、增量索引清单与条件性费用估算。DeepSeek 旧请求名实际映射到 V4.1-Flash-1017，代码请求名不变；DashScope 现有地址仍支持北京地域。未核实用户账户币种、地域与权限，没有授权付费。

新发现：官方文档中 `qwen3.7-text-embedding` 用量仅含 `total_tokens`，当前客户端只读 `prompt_tokens`。缺失会保留 UNKNOWN，不能宣称真实 usage 已贯通。生产授权/计价实现、case/repeat 约束及在线采集器仍缺；本轮记录差异，不改代码或旧锁。

## 上一轮实现（保留）

- 阅读专用 `ReadingProviderClient`：固定 DeepSeek 生成和 DashScope embedding 协议，通过小型业务抽象隔离供应商。复用已有 Reactor Netty/WebClient，无新增依赖；不更改旧 Spring AI 调用。
- 生成请求显式限制输出、关闭思考及流式输出；应用/HTTP 无重试、无重定向；整体截止覆盖连接、响应头和响应体，线程中断取消请求。验证响应大小、结构、向量维度/顺序/有限数值，异常只返回安全码。
- 完整生成请求包装参与输入上限及费用预留；embedding usage 传入原有账本。供应商调用完成后的本地检索失败不抹去已取得的 usage；截断结果保留 usage 但不得发布。
- 本地 HTTP 捕获真实序列化请求，隔离 PostgreSQL 集成测试贯通索引、人物查证、审计和截断发布门禁；详细结果见[协议验证](../evaluation/reading-provider-verification.md)。
- 当前冻结清单为 `reading-acceptance-v2`，复用未修改的 `reading-acceptance-v1` 语料/题目/评分；旧配置与锁保留，不把源码变化覆盖进旧锁。无新迁移、业务 API 或前端行为。

`ReadingAiConfiguration` 仍无条件返回 `LIVE_AI_NOT_AUTHORIZED`。配置现有密钥不会开放阅读调用。本地协议测试只能证明发送字段与客户端行为，不能证明供应商实际接受模型、执行输出限制或正确计费。

## 下一步

1. 确认账户人民币计费、DashScope 北京地域及端点；不索取密钥。公开文档兼容与价格已有依据，不重复把这些写作完全未知，也不将账户支持写成已实测。
2. 在既定阅读范围内完成付费前离线门禁：修复 embedding 用量，验证 token 上界，补计价/授权/采集接线并强制绑定候选批次。区分按保守单价记账与真实账单，UNKNOWN 保留预留；协议异常停止推进。实现变更生成新锁，保留 v1/v2。
3. 以上材料和检查齐备后，展示最终 case/repeat、索引、调用数、额度、准确到期时间及新锁，再请求独立付费授权。当前候选批次和估算不构成授权，也不能绕过生产拒绝语句。
4. 获授权后才执行真实协议/模型采集与人工语义复核，再补真实读者反馈、浏览器冷/热性能和新录屏。

付费授权仍为零，真实生成/embedding 调用为零。旧 V1–V5 验收基线保留；旧研究扩展、升级调优及录屏继续暂停。不得使用 TEST 调 Prompt，不以本地协议或固定任务结果宣称真实防剧透/语义验收通过。
