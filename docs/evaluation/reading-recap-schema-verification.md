# 多章回顾输出格式修复记录

2026-10-23，acceptance-v16 / reading-evidence-v5。仅离线工程验证，不宣称真实多章回顾已修复或语义验收通过。

## 现场事实与限制

用户报告最近两章、三章回顾失败，日志均为 `OUTPUT_INVALID / JSON_SCHEMA`。只读查询日常库确认：

| 任务 | 章节数 | 输入/输出 token | 结束原因 | 结果 |
| --- | --- | --- | --- | --- |
| b0338468-e25c-42ee-87fd-641ea7bc2dea | 3 | 3643 / 815 | stop | JSON_SCHEMA |
| 341358e3-2cbc-477b-ab34-41e6272396b7 | 2 | 1949 / 576 | stop | JSON_SCHEMA |
| 4c349110-da04-4736-8018-ba1b289da11e | 3 | 3643 / 836 | stop | JSON_SCHEMA |
| 77aca474-4c14-433c-ac0a-c59da0109658 | 2 | 1949 / 700 | stop | COMPLETED |
| 80cddc2d-1f49-4582-b8f0-e93e09610516 | 3 | 3931 / 1026 | stop | COMPLETED |

这些观测不支持固定章节数限制或 token 截断解释。旧 `JSON_SCHEMA` 汇总了反序列化异常，未保留原始模型回答，因此不能断言是缺少 partial、按章嵌套或其他某种具体格式错误。不得将下面的合成回归样例视为真实失败原文。

原实现已有统一 `Result(facts, partial)` 及引用/长度校验，但供应商请求是 `response_format={"type":"json_object"}`，字段结构靠提示词约束。[DeepSeek Chat Completions 官方文档](https://api-docs.deepseek.com/api/create-chat-completion/)（2026-10-23 核对）只为该参数列出 text/json_object，说明后者保证合法 JSON；不能将它描述为严格遵循业务 JSON Schema。本轮沿用现有接口、模型和参数，没有静默迁移到其他端点或工具调用。

## 改动

- Prompt v5 明确一至三章共用一个对象和一个扁平 facts 数组，列明字段、类型、必填 partial=false 与字符串转义，去除回顾提示混入的人物查证说明。人物查证的独立 answerability 语义不变。
- 回顾先做 JSON 树检查，再绑定原 Result，禁止通过标量自动转换放行错误类型。细分 JSON_SYNTAX、JSON_ROOT_FIELDS、JSON_FACT_FIELDS、RECAP_PARTIAL_MISSING、RECAP_PARTIAL_TYPE、FACTS_SHAPE、FACT_TEXT、EVIDENCE_IDS_TYPE。只保存固定原因码，不记录解析异常消息、未知字段名或原文。
- 页面展示上述细分原因。保留已有 JSON_SCHEMA 的旧任务显示，既有任务不重写。
- 不修补/截断模型回答，不默认为完整，不放宽引用、长度或阅读范围校验，不自动重试。没有数据库迁移、付费调用、启动应用或变更本地密钥配置。

## 验证

后端定向回归 80 项：79 通过、1 项既有夹具跳过，零失败。覆盖多章实际窗口选择与未读段落排除、同一合同成功保存、缺字段失败原因持久化且每任务只调用一次、JSON 语法/层级/类型拒绝、人物查证、供应商本地协议和已有 SSE 异常处理。首次新增测试编译失败是 Evidence 类型名未限定，修正测试引用后通过；未调整生产校验迁就测试。

前端 `npm run build`（含类型检查）与 5 项交互测试通过。构建仍有既有大包提示。

31 项评测/采集/性能工具离线测试通过；acceptance-v16 源码与资产哈希全部匹配，v1–v15 锁及停止账本未修改。

## 下一步

用户目标是两章、三章回顾能够稳定用于续读。用户自行重启现有后端后，在相同已读范围手动验证日常回顾；若失败，按新细分码定位具体格式，继续修复现有合同。完成标准是真实多章任务成功、引用可跳转且无超出已读范围内容，并保留实际失败记录。本轮未代用户发起真实调用，历史停止试读和固定评测不恢复；付费 DEBUG/TEST 不是当前下一步。
