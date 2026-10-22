# 小说阅读开发交接

2026-10-22 最新授权：用户明确批准 DeepSeek 20 元、阿里向量模型 10 元，并允许真实调用。执行固定语料批次，两个供应商独立账本、额度不可互借，任一停止阻止整个批次；此前“未授权/待确认”描述仅为历史。当前实现冻结为 acceptance-v5，保留 v1–v4；结果见[本轮执行记录](../evaluation/reading-live-v5-results.md)。

执行结果：真实 DEBUG 已完成 12 题和 6 个索引，35 次 HTTP 有已知用量；debug-008 未正确拒答，两个账本已以 DEBUG_REVIEW_FAILED 持久停止，TEST 96 次保留 NOT_RUN。下一步修复该 DEBUG 缺陷并离线验证，不能沿用旧“请求付费授权”待办或自动重跑停止批次。详细金额、证据、恢复材料及未完成项见上方执行记录。

最新进展：用户要求增加预算日志/停止功能，已实现并升级 acceptance-v4，见[预算保护](../evaluation/reading-budget-alerts.md)。旧 v3 最终计划已被实现变更取代；保留绑定/隔离库，使用新的 v4 计划。付费授权仍为零，不沿用上一条待确认的 v3 授权请求。

最新进展（2026-10-22）：PR #37 已核实合并。已完成隔离库 bind/prepare，最终计划、UUID、哈希及后续恢复入口见[最终批次准备](../evaluation/reading-live-batch-preparation.md)。用户确认两模型开通、按量计费；尚未独立授权付费。后端和隔离容器已停止，命名卷与本地计划保留。下方“下一步”第 1 项已完成；不要重复导入或改写 plan。

更新：2026-10-22。用户要求开始下一阶段开发，本轮完成一个能力：真实调用前的固定批次授权与采集接线。开发授权不包含付费执行；没有有效批准文件，真实生成/embedding 调用仍为零。相关检查通过并审阅完整 diff 后主动本地提交，再停止并提醒 PR；不自动推送、建 PR 或合并。

## 起点与当前实现

先读[项目背景](project-context.md)、[AGENTS.md](../../AGENTS.md)、[唯一路线](novel-reading-roadmap.md)。本轮起点为干净的 develop/d6f3f05，本地已有两次文档提交，未推送；最终 Git/远程状态需重新核对，不据历史记录推断 PR 已合并。

- 用户已确认 DeepSeek 人民币计费、百炼北京地域与业务空间专属 Base URL，原始记录见[授权准备](../evaluation/reading-live-authorization-preparation.md)，无需重复询问。
- 阅读客户端已固定到该专属端点，并修复 total_tokens 审计；旧知识库 Spring AI 共享端点未改。
- 新增 V32 批准摘要/次数约束和输入 token 预留上界；金额与次数原子预留、同批准重复安装不重置、UNKNOWN 保留预留并停止批次。
- 默认 READING_LIVE_ENABLED=false；只有本地有效 plan/approval、未过期价格/授权、固定源码/资产/语料/题目绑定和后端凭据齐备才可执行。新增 HTTP 接口查询状态，没有创建或增额入口。
- 当前冻结版本为 reading-acceptance-v3，保留 v1/v2 配置/锁和全部 v1 语料、题目、评分。逻辑 Prompt 未改。新实现需重新构建，不能使用旧进程冒充新锁。
- 本机采集器分离 bind、prepare、DEBUG 与 TEST，追加保存 journal，失败或响应不确定即停；TEST 需 DEBUG 成功且人工复核后显式放行。

细节、测试与局限见[门禁工程验证](../evaluation/reading-live-gates-verification.md)，步骤见[采集操作](../../evals/tools/reading-live-README.md)。此前[本地协议](../evaluation/reading-provider-verification.md)和[价格准备](../evaluation/reading-live-authorization-preparation.md)保留历史结论。

## 费用口径

此前约 17.58 元是字节预测，不是可靠上界。本轮真实预留按供应商公开上下文容量计算，全部调用最大预留之和约 364.68 元；这不提高路线拟议停止额度，也不是账单。顺序调用以返回 usage 按高峰未命中单价结算并释放差额；预算不足停止，不承诺完成、不自动追加。保守预算记账与实际账单分开报告。

## 下一步

1. 在隔离评测数据库以默认关闭的后端执行 bind/prepare，取得真实 UUID 和最终 plan 哈希；这些步骤只有导入/业务持久化，不调用模型。当前没有可直接执行的已批准批次。
2. 复核当时官方价格以及账户按量方案/模型权限等适用条件，准备准确授权起止时间；已确认的人民币/北京信息不重复询问。
3. 展示最终 plan/实现锁、case/repeat 与索引清单、调用上限、预留方式和停止额度，最后请求独立付费授权。未获得授权前不改批准状态，不启用真实开关，不运行 DEBUG/TEST。
4. 获授权后先 DEBUG；异常即停。人工协议/用量/语义复核后才 TEST。保留失败及 UNKNOWN；不使用 TEST 调 Prompt。随后按路线补语义评分、真实试用、浏览器冷/热性能与录屏。

旧 V1–V5 基线保留；旧研究场景扩展、升级调优及旧录屏继续暂停。整项小说阅读 AI 语义/体验验收仍未完成。
