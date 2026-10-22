# 小说阅读开发交接

更新：2026-10-22。用户已完成 PR #38；git fetch 后核实 origin/main 为 87c3f9f（Merge pull request #38），本轮起点为干净的 develop/724c143，与 origin/develop 一致。仅推进一个问题：真实 DEBUG 的无依据拒答缺陷。

## 当前修复

- reading-evidence-v2 在人物查证的一次回答中要求 FULL/PARTIAL/INSUFFICIENT_EVIDENCE 判断，强调“相关证据”不等于“能回答当前问题”，区分实际事实与说法/要求/计划。
- 服务端严格校验判断与 facts 是否一致，再转换为原有 Result(facts, partial) 与任务状态；缺失、未知或矛盾响应为 OUTPUT_INVALID。公共 API、数据库、回顾响应、调用数和预算不变。
- 当前冻结 acceptance-v6，数据集/评分仍为 v1；保留 v1–v5 配置/锁以及 v5 原始失败。未修改 TEST、未新增依赖、未运行新付费调用。
- [拒答修复验证](../evaluation/reading-refusal-verification.md)记录工程检查与局限。模型可能错误判断 FULL，离线协议通过不等于真实语义问题已解决。

## 已有真实证据与费用

用户已明确批准 DeepSeek 20 元、阿里向量模型 10 元，币种为人民币、百炼北京、两模型开通且按量计费；这些事实无需重复确认。v5 DEBUG 已完成 12 题、6 个索引、35 次调用：保守预算结算 DeepSeek 0.017394 元、阿里 0.000337 元，无 UNKNOWN 或未释放预留。费用不是最终账单。

debug-008 询问实际风力，模型却用纸条要求代替正确拒答。两个账本已持久停止为 DEBUG_REVIEW_FAILED；TEST 96 次保留 NOT_RUN，不能因 PR 合并或仍有余额自动解除停止或换 ID 重跑。原证据、批准/锁哈希、卷与备份入口见[首次真实执行记录](../evaluation/reading-live-v5-results.md)。隔离库和后端保持停止；不得恢复付费前 database.sql 来清零账本。

## 下一步

1. 对本轮修复提交 PR。本地自动提交不包含推送、创建 PR 或合并。
2. 准备基于新锁的具体 DEBUG 重测清单与原停止批次的费用衔接；按[路线](novel-reading-roadmap.md)复核时效、价格，并从既有批准总额扣除历史消耗，不能重新给出完整新额度。未安装新计划/批准文件；本轮不发出重测请求。
3. 重测须明确由用户发起，保留旧失败。DEBUG 协议/用量/语义复核后才考虑 TEST；不能使用 TEST 调参。
4. 真实语义、实际读者试用、浏览器冷/热性能与录屏仍未整体验收。旧升级研究及旧录屏保持暂停。

历史准备分别见[门禁工程验证](../evaluation/reading-live-gates-verification.md)、[绑定准备](../evaluation/reading-live-batch-preparation.md)、[预算保护](../evaluation/reading-budget-alerts.md)。其中“未授权”的描述是当轮历史状态，不取消用户后来明确提供的额度，也不自动批准新的重测清单。
