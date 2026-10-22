# 阅读固定批次授权与采集门禁工程验证

日期：2026-10-22。起点 `develop/d6f3f05`，本地包含前两次文档提交，未推送。本轮按用户“开始下一阶段开发”推进一个能力：真实调用前的授权与采集接线。**开发授权不包含付费授权；未生成有效批准文件、未访问供应商账户、真实生成/embedding 为零。**

## 实现

- 修复 `qwen3.7-text-embedding` 用量：读取 `total_tokens`，可选 `prompt_tokens` 必须一致，否则保持未知。保存有限字符的供应商 model/id 供观测溯源；不能凭响应别名断言固定权重。
- 阅读客户端固定到用户提供的北京业务空间 `/embeddings` 端点；DeepSeek 请求模型名保留。没有修改旧 Spring AI 的共享端点或既有研究功能，没有新增生产依赖。
- `ReadingLiveBatch` 读取本地 plan/approval，检查批准状态、期限、金额、计价版本、固定端点、冻结文件哈希与语料 UUID/段落哈希。所有题目/repeat 和索引前缀从冻结数据集派生，不接受任意任务列表。`ReadingLiveAuthorization` 在创建和每次调用预留前校验任务类型、问题、模式、版本、边界、索引增量及批准摘要。
- 默认 `READING_LIVE_ENABLED=false`。启用必须同时提供有效 plan/approval、当前源码目录及后端凭据；配置密钥本身不授予阅读权限。批准文件是受信任本地运维输入，不是用户登录或公网认证。
- [V32](../../backend/src/main/resources/db/migration/V32__bind_reading_live_authorization.sql) 为账本增加批准摘要、调用上限与累计次数，为调用增加输入 token 预留上界；金额和次数同一 SQL 原子预留。批准重复安装不重置余额、次数和停止时间，同一请求不能二次计费预留。原有任务幂等键确保同批同题同 repeat 不重复创建。
- 缺失 usage、无效上界、技术失败或暂停/重启中断使授权停止；未知用量保留预留。已知 usage 按保守单价记账，不冒充供应商实际扣款。生成/索引原有超时、取消、无重试及范围校验保持。
- 新增状态查询 `GET /api/reading/evaluation/status`，展示授权/预算/次数/锁/环境和成本口径，无 HTTP 授权创建、增额或任意供应商代理接口。任务详情增加真实入队起耗时、预留 token 上界和有限供应商元数据。
- [采集工具](../../evals/tools/reading-live.mjs) 分离 bind、prepare、DEBUG、TEST；只访问数字 loopback 地址，拒绝重定向和自动重试。每次写入追加 journal 并 fsync，保留原始任务/引用/用量。失败或提交响应不确定即停止；不确定尝试不伪造 UUID，报告显示 FAILED/UNRESOLVED，不能据空 calls 宣称费用为零。未分摊的索引审计仍计入汇总。
- TEST 需要操作者确认 DEBUG 已复核，并且后端检查固定 DEBUG 任务和索引均成功。复核开关不构成语义评分；修改 Prompt/实现后必须新冻结，不用 TEST 调参。

## 价格和预留依据

公开资料及用户币种/地域确认见[准备记录](reading-live-authorization-preparation.md)。本轮再次读取 [DeepSeek 人民币模型页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)与[百炼同步接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)：生成按高峰未命中输入/输出单价，embedding 按北京同步输入单价。计价代码版本为 `reading-cny-20261022-v1`，正式授权时仍须重新核实价格和账户适用条件。

此前约 17.582815 元是按字节估算输入的预测，不能作为已证明上界。本轮避免依赖未经验证的 tokenizer，改用供应商公布的输入上下文容量：DeepSeek 1M 按较大的二进制 1,048,576 token 预留，另加每次受限输出；embedding 每行按 128,000 token，索引每批保守按 16 行计，不提前扣除短文本优惠。业务输入字符/字节限额仍不变。此方法基于供应商遵守所公布上下文容量和输出限制；若返回 usage 超过上界，则冻结，不释放预留继续调用。

| 单次调用 | 最大预留（人民币元） |
| --- | --- |
| 回顾生成 | 2.129920 |
| 人物回答 | 2.113536 |
| 查询规划 | 2.101248 |
| 单查询 embedding | 0.064000 |
| 一批索引 embedding | 1.024000 |

固定批次仍为 108 次任务、27 个增量索引作业，最多 154 次生成 + 164 次查询 embedding + 28 次索引 HTTP。全部调用最大预留之和为 **364.677120 元**，不代表实际账单，更不构成提高预算的授权。路线拟议停止额度保持不变；顺序运行中已知 usage 结算后释放差额，余额不足则提前停止。不能承诺拟议额度足够完成全批。

## 验证命令与证据边界

从 backend 使用 JDK 21：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dcontextpilot.reading.live-enabled=false' '-Dtest=ReadingProviderProtocolTests,ReadingLiveAuthorizationTests,ReadingLibraryPersistenceTests,ReadingTaskPolicyTests,ReadingTaskPersistenceTests,ReadingNoteProposalPersistenceTests' test
```

从根目录：

```powershell
node --test evals/tools/reading-live.test.mjs evals/tools/reading-eval.test.mjs
node evals/tools/reading-eval.mjs validate
```

本轮结果：后端 90 项、0 失败/错误、2 项条件跳过；跳过项为已有的人工浏览器会话夹具。Node 24 项全部通过。后端新增验证覆盖金额/调用数竞争、UNKNOWN 冻结、重复安装不重置，以及授权任务的持久化结算和幂等重放；V32 在隔离 PostgreSQL 成功应用。

状态查询可能首次将已由本地文件批准的账本幂等落库；HTTP 不接受批准内容，也不能创建或扩大授权。

供应商 HTTP 测试只请求 loopback stub，数据库测试使用 Testcontainers PostgreSQL/pgvector；没有迁移日常数据库。Node 采集测试使用内存 HTTP 夹具，验证固定条目、无授权拒绝、提交不确定停止、完整 DEBUG 采集和索引归集，不是真实供应商证据。无前端源码变更，不重新声称页面/录屏验收。

## 冻结与下一步

当前实现版本升级为 `reading-acceptance-v3`；v1/v2 配置和锁保留原字节，v1 语料、题目及评分协议未改。新锁覆盖 V32、授权类与采集器/测试。未运行模板仍是 108 条 NOT_RUN。冻结校验 assets/source 均 MATCH；报告为 INCOMPLETE / NOT_DETERMINED、调用数 0。251 个本轮 Markdown 相对链接检查通过，历史配置/锁、数据集和评分文件逐字节核对未改；git diff --check 通过。

下一步在隔离评测数据库执行无模型的 bind/prepare，得到实际 UUID 和最终 plan 哈希；确认账户按量方案/模型权限、复核当时价格并填写准确授权时间，再向用户展示并请求独立付费授权。当前仅有可执行开发入口，没有可以直接开跑的有效授权。操作说明见[采集手册](../../evals/tools/reading-live-README.md)。真实协议观察、独立语义评分、读者反馈、冷/热浏览器性能和录屏仍未完成。
