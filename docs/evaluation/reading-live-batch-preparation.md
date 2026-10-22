# 阅读真实评测最终批次准备记录

日期：2026-10-22。状态：**待独立付费授权，尚未执行 DEBUG/TEST。** 用户确认 PR 完成并要求下一步；本轮仅执行隔离库导入、绑定和计划生成。用户另已确认两个模型均已开通、按官方按量计费；这是账户条件确认，不是付费执行授权。

## Git、构建与隔离

- 起点 develop/d706266，与实时远端 develop 一致；远端 main/6e8cca0 为 Merge pull request #37，已用 Git 祖先关系核实包含 d706266。工作区起点干净。
- 以 JDK 21 重新 package（跳过重复单元测试），构建成功。未修改冻结源码、配置或资产，acceptance-v3 的 assets/source 均 MATCH。
- 独立容器 context-pilot-reading-eval-20261022、独立卷 context_pilot_reading_eval_20261022、127.0.0.1:15433/context_pilot；后端仅监听 127.0.0.1:18081，存储在 backend/target/reading-batch-20261022/uploads。
- 后端显式关闭阅读真实调用，未传供应商密钥；全部 32 个迁移在独立库应用。日常 15432 数据库与上传目录未改。
- bind 实际调用本机导入预览与独立确认接口，校验全部段落哈希。数据库结果：4 imports、4 books、4 versions、86 paragraphs；reading_task、reading_index_job、reading_ai_call、reading_spend_ledger 均为 0。状态接口返回 LIVE_AI_NOT_AUTHORIZED。
- 绑定完成后已导出本地 database.sql，并停止本轮后端和容器；容器及命名卷保留，后续应复用，不能重建 UUID 后沿用本计划。该 SQL 仅为当前未执行快照，付费开始后不能用旧快照重置账本。

## 最终计划身份

- batchId：003fc19d-8cdb-4ba4-a679-18f550aedf44
- plan SHA-256：e70f0e980fe24b9fa1b3c683fc3b3c04bd0b4ced008e87aa2d741aeebf6bbc36
- acceptance-v3.lock SHA-256：3af6d63e62009e27ea3c8b7206d5e90df9134c2eba24efa958cc729d7ec468e8
- dataset SHA-256：bd9c3600661966d3ea415a0ff2a29c01cd7bfe9f0cb858e603a2f518ac27fe12
- 本地 plan：`backend/target/reading-batch-20261022/plan.json`
- 实际 UUID/段落绑定：`backend/target/reading-batch-20261022/bindings.json`
- 全部执行顺序、问题、case/repeat、确定性 clientRequestId 与预留：`backend/target/reading-batch-20261022/entries.json`
- 未批准模板：`backend/target/reading-batch-20261022/plan.json.approval.json`

上述本地文件均在 gitignored target，未提交。批准模板维持 NOT_AUTHORIZED、accountConditionsConfirmed=false、三个时间为空；用户已确认的账户条件保存在本记录，只有独立付费批准后才一并填写正式批准文件。计划绑定的固定语料为公开许可/项目自创评测内容，不包含用户私人小说。

| 语料 | bookId | versionId | 段落数 |
| --- | --- | --- | --- |
| harbor-v1 | 2eba790b-5ae2-4f36-b830-10fd5ed3d9e6 | 64bea815-de39-4a08-8b65-9c864e0f5770 | 36 |
| harbor-v2 | b0a5513c-2a3b-40fa-8cf3-1203171f969d | 0e7602e6-e1e9-47b2-8bee-664022be591f | 36 |
| island-debug-v1 | 3a2d5817-2d53-45ca-b727-a248d8958537 | 12492c37-3337-4649-9ca3-49a7b63c502f | 12 |
| alice-opening-v1 | 4850bbdd-03c5-4610-a5f0-435e7663cb8c | e2f76a21-2fb1-4b3d-be0b-2feb13d4dfa5 | 2 |

## 批次和调用上限

DEBUG 为 debug-001 至 debug-012 各一次；TEST 为 test-001 至 test-048 各两次，问题和边界以未修改的[固定数据集](../../evals/datasets/reading/acceptance-v1.json)及本地 entries 为准。

| 阶段 | 任务 | 增量索引作业 | 最多供应商 HTTP |
| --- | --- | --- | --- |
| DEBUG | 12 | 6 | 38 |
| TEST 两轮 | 96 | 21 | 308 |
| 合计 | 108 | 27 | 346 |

全批最多 154 次生成、164 次查询 embedding、28 次索引 embedding HTTP。索引共覆盖 83 个片段；导入 86 段中 harbor-v1 的最后三段不在该批索引边界内。两次 TEST 复用索引，但分别生成答案。

| 索引条目 | 新增段落范围 | 最多 HTTP |
| --- | --- | --- |
| index:island-debug-v1:3 | 1–3 | 1 |
| index:island-debug-v1:5 | 4–5 | 1 |
| index:island-debug-v1:6 | 6–6 | 1 |
| index:island-debug-v1:7 | 7–7 | 1 |
| index:island-debug-v1:9 | 8–9 | 1 |
| index:island-debug-v1:12 | 10–12 | 1 |
| index:alice-opening-v1:1 | 1–1 | 1 |
| index:alice-opening-v1:2 | 2–2 | 1 |
| index:harbor-v1:1 | 1–1 | 1 |
| index:harbor-v1:2 | 2–2 | 1 |
| index:harbor-v1:4 | 3–4 | 1 |
| index:harbor-v1:5 | 5–5 | 1 |
| index:harbor-v1:6 | 6–6 | 1 |
| index:harbor-v1:8 | 7–8 | 1 |
| index:harbor-v1:9 | 9–9 | 1 |
| index:harbor-v1:12 | 10–12 | 1 |
| index:harbor-v1:15 | 13–15 | 1 |
| index:harbor-v1:17 | 16–17 | 1 |
| index:harbor-v1:18 | 18–18 | 1 |
| index:harbor-v1:20 | 19–20 | 1 |
| index:harbor-v1:21 | 21–21 | 1 |
| index:harbor-v1:24 | 22–24 | 1 |
| index:harbor-v1:27 | 25–27 | 1 |
| index:harbor-v1:30 | 28–30 | 1 |
| index:harbor-v1:33 | 31–33 | 1 |
| index:harbor-v2:21 | 1–21 | 2 |
| index:harbor-v2:36 | 22–36 | 1 |

## 当前价格和拟议授权

2026-10-22 13:50（北京时间）重新核对官方页面：[DeepSeek 人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)、[百炼同步 embedding](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。请求名 deepseek-v4-flash 保留，官方说明由 DeepSeek-V4.1-Flash-1017 提供服务；生成按高峰未命中输入 2 元/百万 token、输出 8 元/百万 token 做保守预算记账。北京 qwen3.7-text-embedding 同步价 0.5 元/百万输入 token、1024 维。与 reading-cny-20261022-v1 一致，账户开通及按量计费由用户确认，真实协议与扣费尚未实测。

端点固定为 https://api.deepseek.com/chat/completions 和用户确认的 https://llm-90adsh26dbyar5po.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings。

拟议授权该固定完整批次共用 **人民币 20 元停止额度**，先 DEBUG，人工协议/用量/语义复核后才放行 TEST；失败、UNKNOWN、预算不足立即停止，不自动重试或追加额度。正式生效时间记录为用户明确确认的时刻，拟议到期为 **2026-10-23 13:40:00+08:00**。本记录不是已生效批准；若确认过晚或价格条件变化，重新核对有效期，不沿用过期材料。

全部调用最大预留相加为 364.677120 元，这不是预计扣款或本次授权额度。顺序运行时已知 usage 按保守价格结算并释放差额；每次同时在途预留加已结算金额必须满足 20 元约束。不能承诺完整批次在额度内跑完。供应商账单、保守预算结算和 token 上限预留分开报告，详细假设见[工程验证](reading-live-gates-verification.md)。

本轮未改变实现，因此未重复运行后端回归；实际 bind/prepare、迁移、零调用审计、计划哈希、历史锁字节和 151 个文档相对链接检查通过，git diff --check 通过。

## 待确认后执行

恢复保留的隔离容器，校验 plan/lock/数据库绑定未变；从当前检出构建后端。收到针对上述计划的明确付费授权后，才记录实际 authorizedAt、pricingVerifiedAt、expiresAt、accountConditionsConfirmed=true 和 APPROVED，再通过后端现有安全配置提供凭据。密钥不进入计划、文档或采集工具。

先运行 DEBUG 并保留全部原始失败/用量/引用；独立复核通过才打开 TEST 复核标记。首次真实协议不符时停止并报告，不能改固定 TEST 或绕过冻结。语义验收、读者反馈与录屏仍未完成。
