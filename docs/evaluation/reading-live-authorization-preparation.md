# 阅读真实调用：模型、端点、价格与授权准备

核对日期：2026-10-22（Asia/Shanghai）。起点：干净的 `develop/144726e`；通过 `git ls-remote` 核对远程 develop 同为该提交。本轮只读取官方公开文档、核对代码并准备批次，未访问账户或调用生成/embedding API。**已授权金额仍为 0 元；本文是候选材料，不是授权记录，也不是线上验收报告。**

预算与完成门槛仍由[唯一路线](../development/novel-reading-roadmap.md)维护；本文只记录该预算下的供应商证据、具体批次与计算。此前[本地协议验证](reading-provider-verification.md)保留原结论；本轮没有修改生产实现、配置、Prompt、数据集或历史锁。

## 官方核实结果

后续开发状态（2026-10-22）：usage 修复、专属端点、固定批次授权与采集入口已实现，默认关闭；真实预留改用供应商上下文容量，详见[门禁工程验证](reading-live-gates-verification.md)。本文的 v2 指纹、字节估算和“待补齐”清单保留为准备轮历史记录，不作为当前可执行批准文件。后续应绑定 v3 与实际导入 UUID，付费授权仍为零。

以下均为核对日读取的官方公开页面，不代表当前账户已开通、余额足够或实际请求成功。

| 项目 | 官方依据及结论 | 本项目处理 |
| --- | --- | --- |
| DeepSeek 模型 | [人民币模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)列出 `deepseek-flash`，版本为 `DeepSeek-V4.1-Flash-1017`；脚注明确旧名 `deepseek-v4-flash` 仍可调用，由该新版服务并按 Flash 计费 | 保留已批准的请求名；不能声称运行的仍是旧 V4 权重。未来运行需记录请求名、响应 model、调用时间与当日公开版本映射；响应别名不能证明权重固定 |
| DeepSeek 端点与参数 | 上述页面给出 `https://api.deepseek.com`；[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)支持 `max_tokens`、`thinking.type=disabled`、`response_format.type=json_object` | 代码固定 POST `https://api.deepseek.com/chat/completions`，非流式；字段有文档依据，实际输出限制仍待获授权的请求验证 |
| DashScope 模型 | [同步接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)北京表列出 `qwen3.7-text-embedding`，支持 1024 维、每请求最多 20 条输入 | 保留原模型和 1024 维；项目每批最多 16 条，未要求改成 flash 或其他模型 |
| DashScope 端点 | [Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)明确共享域名继续可用，北京 OpenAI 兼容地址为 `https://dashscope.aliyuncs.com/compatible-mode/v1`；API Key 与地域、计费方案需匹配 | 仓库固定 POST 该地址下的 `/embeddings` 有官方支持依据。文档推荐业务空间域名不等于旧端点下线；无需自动迁移。北京地域现已由用户确认，账户权限未实测；用户提供的专属端点见下文 |
| embedding 用量 | 同步接口当前中文文档说明：本项目模型仅返回 `usage.total_tokens`；`prompt_tokens` 由另一个 flash 模型返回 | 发现现有 `ReadingProviderClient.embed` 只读取 `prompt_tokens`，合法响应会落为 UNKNOWN。付费前需修复并补协议/账本测试，不得将缺失字段记为零或自动重发 |

DeepSeek API 枚举只列推荐模型名，价格页脚注补充旧名兼容承诺；这解决了之前“文档枚举不同”的公开依据缺口，仍不构成账户实测。DashScope 示例换成业务空间域名也不构成迁移授权。

## 公开价格与适用条件

2026-10-22 用户后续明确确认：DeepSeek 账户以人民币计费，百炼地域为北京，控制台 Base URL 为 `https://llm-90adsh26dbyar5po.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`。这是用户提供的账户信息，未登录控制台或发送 API 请求复验。该地址是业务空间专属端点，当前阅读客户端仍固定使用共享端点 `https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`；后续接线需将所选端点明确绑定到最终批次及固定允许列表，不扩展为任意 URL。

候选计价快照标识：`reading-public-cny-20261022-draft1`，状态更新为 `PUBLIC_DOCUMENTS_VERIFIED_CURRENCY_REGION_USER_CONFIRMED`，**未写入运行配置或账本**。该标识是项目本地证据名，不是供应商价格版本号。币种和地域符合本表适用条件；百炼按量方案、模型权限、余额/限流及实际请求支持仍未验证。价格和授权执行门禁齐备后才能激活，运行前还要复核页面有无变更。用户补充账户信息不构成付费授权。

| 模型/费用项 | 公布价格 | 保守预留单价（元/token） |
| --- | --- | --- |
| DeepSeek Flash 输入，缓存未命中 | 空闲 1 元/百万 token；高峰 2 元/百万 token | `0.000002`，全部按高峰未命中 |
| DeepSeek Flash 输入，缓存命中 | 空闲 0.02 元/百万 token；高峰 0.04 元/百万 token | 不提前扣除缓存优惠 |
| DeepSeek Flash 输出 | 空闲 4 元/百万 token；高峰 8 元/百万 token | `0.000008` |
| 北京 `qwen3.7-text-embedding` 同步输入 | 0.0005 元/千 token，即 0.5 元/百万 token | `0.0000005`，输出费为 0 |

生成价格来源为上述 DeepSeek 人民币价格页；向量价格来源为[阿里云向量化模型表](https://help.aliyun.com/zh/model-studio/embedding)北京部分及同步接口北京表。不使用美元汇率估算，不把免费额度、赠金或 Batch API 折扣计入预留。一个同步请求传入多条文本仍是同步价格，不享异步 Batch 折扣。

按高峰未命中价格乘实际 token 得到的仍是保守成本口径，不一定等于账单。当前 `ReadingSpendService.settle` 只按预留时单价结算，尚不区分峰谷/缓存；后续报告必须分别标明预算记账金额、供应商 usage 与账单核对结果，不把该值称作实际扣款。UNKNOWN 保持预留。

## 候选批次与离线核算

候选批次名：`reading-first-live-20261022-draft1`。以路线中拟议的首次额度为停止上限，不新增预算。未来正式授权建议有效期为确认时起 24 小时，正式记录须填入明确的 `authorizedAt` / `expiresAt`（含时区）；当前均未设置。本候选不允许追加尝试、自动重试、跨供应商替换或额外协议探针。

输入绑定：

- [数据集](../../evals/datasets/reading/acceptance-v1.json) SHA-256：`bd9c3600661966d3ea415a0ff2a29c01cd7bfe9f0cb858e603a2f518ac27fe12`。
- 当前 [acceptance-v2 锁](../../evals/configs/reading/acceptance-v2.lock.json) SHA-256：`ed3ce540790c51a8b2208e6af1a90e80e02e2b71376072773856b5bbf17397bc`。它仍记录未授权/价格未核实的历史状态，不回写本次新发现。
- 下一步修复 usage 或增加授权接线后，必须生成新的实现冻结版本及锁；正式授权绑定新锁，不能直接用本候选的旧源码指纹执行。语料、题目及评分保持原版，不使用 TEST 调 Prompt。

明确的 case/repeat 集合为 `debug-001` 至 `debug-012` 各 repeat=1；`test-001` 至 `test-048` 各 repeat=1、2，共 108 条。问题、范围、模式完全按绑定数据集，不扩大每题任务上限。先完成 DEBUG 并复核协议/用量，再执行 TEST 两轮；若 DEBUG 需要改实现或 Prompt，停止，发布新冻结版本并重新核对剩余授权，不能顺势消耗 TEST。

| 子批次 | 回顾/直接人物/多线索任务数 | 生成 HTTP 最多 | 查询 embedding HTTP 最多 | 生成预留估算（元） | 查询向量预留估算（元） |
| --- | --- | --- | --- | --- | --- |
| DEBUG ×1 | 4 / 4 / 4 | 16 | 16 | 1.916928 | 0.014144 |
| TEST 每轮 | 16 / 11 / 21 | 69 | 74 | 7.770112 | 0.054256 |
| 两轮 TEST 加 DEBUG | 36 / 26 / 46 | 154 | 164 | 17.457152 | 0.122656 |

以上是从冻结题目逐项统计的具体批次，小于路线允许的保守最大调用规划，并不修改路线预算。

索引按同语料、同版本逐步扩展已读前缀。建议每个 split 内按 corpusId、boundary 升序执行，第二轮复用成功索引，仍重新生成回答。下表列出首次需要人物检索的全部目标段落序号；每个目标只索引上次目标之后的增量，不预先索引未读部分。回顾本身不要求索引。已有更宽索引只能在检索/结果复核均过滤当前边界的情况下复用。

| 语料 | 依次索引到段落 | 增量作业数 | 片段总数 | HTTP 请求最多 | 原文 UTF-8 bytes |
| --- | --- | --- | --- | --- | --- |
| island-debug-v1 | 3, 5, 6, 7, 9, 12 | 6 | 12 | 6 | 762 |
| harbor-v1 | 1, 2, 4, 5, 6, 8, 9, 12, 15, 17, 18, 20, 21, 24, 27, 30, 33 | 17 | 33 | 17 | 2244 |
| harbor-v2 | 21, 36 | 2 | 36 | 3 | 2409 |
| alice-opening-v1 | 1, 2 | 2 | 2 | 2 | 599 |
| 合计 | 第二轮无新索引 | 27 | 83 | 28 | 6014 |

索引预留估算为 `6014 × 0.0000005 = 0.003007` 元；整批合计最多 **346 次 HTTP 请求**，按上述保守单价和输入估算为 **17.582815 元**。这不是账单报价或可靠上界已经验证的声明；路线拟议的 20 元仍未获授权，剩余空间不自动变成重试额度。

索引计划需在导入后用真实段落哈希/ordinal 复核；一段一片、每批最多 16 条，本批原文均短于单片限制。版本、分段或批次有差异时停止重算，不能按表硬发。索引失败/UNKNOWN 不自动补发；任何额外索引、手工重试或协议探针都需更新明确清单。

计算方法：生成输入使用完整序列化请求 UTF-8 bytes 作为 token 的保守估算；综合输入最多 65536 bytes，多线索另加规划 8192 bytes；回顾/人物/规划输出分别取路线的 4096/2048/512 token 上限。直接查询向量按 4000 bytes，多线索每条按 1024 bytes、最多三条。索引按上表实际原文 bytes。每次预留向上保留八位小数。

该字节估算法来自现有工程合同，**不是供应商保证的 token 上界**。[DeepSeek token 文档](https://api-docs.deepseek.com/quick_start/token_usage/)说明字符换算只是近似、实际以返回 usage 为准。正式接线必须验证所用 tokenizer/消息包装的保守性，或拒绝调用；不能因估算小于预算就认为可安全放行。

## 付费前仍需补齐的事项

1. 账户条件：人民币计费、北京地域及控制台专属 Base URL 已由用户确认，无需重复询问。后续接线需处理专属端点与现有固定共享端点的差异；按量方案、模型权限、余额与限流仍需账户证据或获授权后受控请求验证。不要在文档或聊天中收集 API Key。
2. 修复 embedding `total_tokens` 审计；覆盖仅 total、字段缺失、负数、冲突/异常值等，并验证 UNKNOWN 不释放预留。这是当前实现与官方协议的具体差异，不是模型质量问题。
3. 完成受账本保护的生产授权与采集入口。当前网关无条件拒绝，`pricing()` 默认抛 `PRICING_UNKNOWN`；账本金额/到期门禁已有，但 case/repeat、模型/端点、索引计划、请求数量与运行版本仍需在执行端强制绑定。不能只手工插入余额并删除拒绝语句。
4. 费用口径：校验 token 上界、峰谷/缓存与账单语义；提供逐任务/索引估算和原子预留，超额/过期/未知价格停止。失败/UNKNOWN 保持预留，无隐藏重试。完成这些离线检查后生成新锁及最终批次。
5. 最后再请求独立付费授权。首个真实 DEBUG 调用兼作协议验证，异常即停止评估推进、保留失败与用量；不额外发送未列出的试探请求。实际调用成功只算模型观察，语义评分、读者反馈与演示仍按路线另行完成。

正式请求授权应同时展示：最终批次及锁哈希、上述账户/模型实际映射、case/repeat 与索引清单、总调用数、可靠预留依据、拟议金额和准确到期时间。当前不要求用户为尚未可执行的接线批准付费。

## 本轮检查

- 对冻结题目逐项核对 caseId/repeat、任务模式、生成/查询调用数；按语料段落重新计算索引增量、片段数与 UTF-8 bytes，核算上表费用。均通过，未发送 HTTP 模型请求。
- `node evals/tools/reading-eval.mjs validate`：60 题、4 份语料，assets/source 均 MATCH。首次检查曾发现向路线增加说明会改变冻结资产；已撤回该说明，将新增证据放在本文和交接入口，复验通过，没有改写锁。
- 本轮 Markdown 本地链接、完整 diff 和 `git diff --check` 检查通过。仅文档变更，未运行无关后端/前端构建或付费实验。
