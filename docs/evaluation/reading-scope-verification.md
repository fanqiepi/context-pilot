# 明确已读范围并查阅原文：工程验证记录

日期：2026-10-20。对应 `reading-contract-v1-draft1` 的第二个用户能力。起点为干净的 `develop/7e0f4f4`，前置基础阅读提交为 `2ddb441`。用户在验证完成后要求提交代码并写好提交信息，授权本地提交；PR 仍由用户提交，最终 Git/PR 状态以实际记录为准。

## 交付与验证边界

新增 `ReadingScopeService`、安全状态 SSE、范围内段落原文和笔记出处预览。目录、普通正文标题、笔记与书签视图在统一范围事务下组装；边界更新/书籍删除与发布门禁采用同一锁序。预览不更新续读位置，显式“从这里继续读”才调用原位置命令。页面回退提示、读完本章、断线/聚焦重验与迟到请求丢弃均已接入。

没有新增生产依赖、迁移或 AI 任务。V29 的不可变正文、scopeRevision、visibilityEpoch、笔记范围标签继续使用。任务 executionId/状态/预算与范围门禁的联合检查仍属于下一功能；本报告不是阅读 AI、防剧透语义或完整读者体验验收。

## 已执行检查

环境：Windows，JDK 21.0.3，PostgreSQL 17/pgvector 0.8.2 Testcontainers，Node 22.12.0。本轮仅离线工程验证，真实模型与 embedding 调用均为零。

| 检查 | 结果 |
| --- | --- |
| 完整后端回归 | `mvnw.cmd --batch-mode --no-transfer-progress test`：553 项，失败 0、错误 0、跳过 17，3 分 33 秒；显式真实调用测试未启用 |
| 最终相关回归 | 最后修改后重跑 `BookParserTests,ReadingLibraryPersistenceTests,ContextPilotApplicationTests,DatabaseMigrationTests`：24 项全部通过（5+16+2+1） |
| 前端类型与构建 | `npm run typecheck` 通过；最终 `npm run build`（包含 vue-tsc）通过，仍有既有主包大于 500 kB 的警告 |
| HTTP/OpenAPI | 新范围事件、段落预览、笔记出处路径被启动测试覆盖；no-store、非法参数、JSON 错误和流连接上限验证通过 |
| 迁移与容量回归 | 空库迁移和保留旧数据升级至 V29 通过；500 万字符/1000 章测试保持分页与定位，最终本机解析 940 ms、确认 1816 ms、正文分页 30 次采样 p95=9 ms |
| 页面代表流程 | 默认桌面及 390×844 窄屏检查原文预览、笔记出处、整章确认、范围回退隐藏及显式继续阅读 |

完整回归在最终追加连接上限测试、正文标题事务锁和输出标题依赖标签之前运行；最终相关回归覆盖这些收尾修改。不能将 553 项表述为最终代码再次全套执行，也不能把跳过的付费测试当作通过。

可复查命令（backend 目录，JDK 21）：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
.\mvnw.cmd -q '-Dtest=BookParserTests,ReadingLibraryPersistenceTests,ContextPilotApplicationTests,DatabaseMigrationTests' test
```

测试源码：[阅读持久化/范围测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/book/ReadingLibraryPersistenceTests.java)、[启动及接口检查](../../backend/src/test/java/io/github/fanqiepi/contextpilot/ContextPilotApplicationTests.java)。

## 范围与并发证据

- B=0 不提供原文；同章第一个段落已读时，不返回后续段落，也不展示完整章标题。实际 SQL 同时绑定主体、书、版本与边界，测试检查查询结果为空，未用“全书返回后过滤”。
- 跨书/版本段落、伪造主体快照和移除书籍不能读取或发布。完整章标题一旦包含在预览中，输出 requiredBoundary 同时包含章末依赖。
- 摘录按 Unicode code point 返回原选区；笔记引用即使指向较早段落，只要整条笔记的原输入依赖较高，回退后仍隐藏标题、正文、摘录与出处。范围重新达到依赖后历史笔记可见，逻辑删除不复活。
- 推进范围时发布回调收到原冻结 B，不能偷偷扩大输入；回退再前进后旧 epoch 发布被拒绝。
- 两线程争用下，发布回调持锁期间边界回退等待；发布提交后回退隐藏该记录。注入回写异常，事务回滚，没有部分新正文。
- SSE 的旧 Last-Event-ID 只触发当前安全快照；事件载荷没有正文、标题、hash 或 owner。订阅上限及断开释放名额有测试。

一次最终相关测试曾在 SSE 只写出事件头、尚未写完 data 时就断言正文，造成 1 项失败。修复测试等待条件为完整 SSE 帧终止符后，24 项全部通过，没有删除失败用例或降低业务断言。失败日志保留在本地 `backend/target/reading-scope-final-tests.log`；成功相关日志为 `reading-scope-final-checks.log`，完整回归日志为 `reading-scope-full-tests.log`（均 gitignored）。

## 页面与环境证据

页面使用独立临时容器 `contextpilot-reading-scope-qa`（15443）、后端 18083、Vite 5203，自创两章测试文本。禁用 chat/embedding/vectorstore/文档处理与旧研究任务后台流程。用户已有 PostgreSQL 15432 未改动。

页面在已读到第 2 段时打开该段预览，未显示真实章标题；关闭预览后 API 核对 resumeRevision 仍为 0、resumePosition 为空。打开笔记出处后，用独立 HTTP 客户端回退到第 1 段，未通过 BroadcastChannel 通知；页面依靠服务端 SSE 自动关闭预览并把笔记变为隐藏占位。确认读完本章后显示真实章标题；在窄屏点击“从这里继续读”，最后保存为第 2 段、offset=0、resumeRevision=1，已读边界独立保持第 3 段。

本地截图：`backend/target/reading-scope-mobile.png`。验证完成后停止本轮临时前后端与专用数据库，保留日志/截图；不停止用户原数据库。

## 限制与下一步

- 状态通知是周期读取提交状态，可能合并多次变化，不承诺同步撤回已经发送或用户已经看见的内容；epoch 能识别期间曾经回退。
- 未进行完整设备矩阵、真实读者试用、断网/迟到响应的浏览器故障注入自动化或真实小说语义评估。页面并发响应保护有实现和代码检查，不冒称所有浏览器竞态均已穷尽。
- 本轮不存在阅读 AI 结果、索引或任务证据，因此没有验证模型先验泄露、任务执行令牌/重启/超时/取消及付费预算；下一能力必须接入范围服务并补齐相关门禁。
- 本轮完成后提醒提交 PR，下一窗口仅推进“回顾前情并查证人物”，AI 笔记提案继续留待后续。
