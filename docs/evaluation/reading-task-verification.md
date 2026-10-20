# 回顾前情并查证人物：离线工程验证

日期：2026-10-20。对应 `reading-contract-v1-draft1` 的本次大功能，起点为干净的 `develop/86f4579`。验证时尚未提交；用户随后要求提交本次代码并写好中文提交信息，授权本地提交，实际提交以 Git 为准。未推送或创建 PR，远程状态未核实。本报告区分离线工程实现、真实适配和语义验收。

## 实现与限制

新增 `readingtask` 专用模块和 V30 九张表，无新增生产依赖。回顾完整按序装箱最近已读章节；人物直接/多线索查证采用独立 pgvector 候选过滤。证据绑定原文 hash、段落和 Unicode 偏移，结果逐事实关联证据。用户可查看历史、暂停/取消/恢复、打开临时引用；当前不提供 AI 笔记提案。

真实生成和 embedding **未接通、未运行**。生产 `ReadingAiGateway` 固定拒绝 `LIVE_AI_NOT_AUTHORIZED`，不注册固定答案。已有 key、旧研究开关不能打开阅读调用。离线 gateway 只在测试源码中注册，模型文本和向量都是工程夹具，不证明检索语义有效或事实正确。

费用账本已实现授权行锁、金额预留和幂等结算；测试金额及单价明确为假数据。当前没有核实供应商价格、真实授权批次或生产供应商协议测试。后续开放真实调用前必须补齐供应商桥接、有效输出 token 上限、HTTP 无重试/实际批次数/超时、真实价格和授权记录，不能只翻开关。未知 token/费用保持未知，不记成零。

## 检查与证据

环境：Windows、JDK 21.0.3、Node 22.12.0、PostgreSQL 17 / pgvector 0.8.2 Testcontainers。只操作临时数据库，未迁移用户现有 PostgreSQL。源码：

- [任务与索引持久化测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingTaskPersistenceTests.java)
- [输入输出与 Unicode 测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingTaskPolicyTests.java)
- [旧阅读范围与容量测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/book/ReadingLibraryPersistenceTests.java)
- [迁移测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/DatabaseMigrationTests.java)、[启动测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/ContextPilotApplicationTests.java)

| 检查 | 已取得的证据 |
| --- | --- |
| 迁移 | 空库 V1–V30；保留旧动作数据升级至 V30；任务/索引外键、唯一键及 pgvector 查询使用真实数据库 |
| 完整范围 | 回顾证据顺序完整；超窗口预算在生成前失败；同章后半段、未读章标题不进入 Prompt；人物 SQL 在排序前过滤 owner/book/version/B/profile/generation |
| 发布竞态 | 回退再前进不能复活旧 epoch；取消/暂停后的迟到生成和 embedding 不发布；迟到实际 usage 仍可补审计 |
| 恢复与预算 | 队列满不留会自动运行的隐性任务；排队超时、执行截止、重启暂停、显式恢复、命令幂等；UNKNOWN 阻止重发，恢复保留累计时间/次数 |
| 输出/引用 | 过多/重复/过长规划查询、越权字段、伪造引用、无证据事实、输出超限、供应商截断整次失败；逐段回查原文/版本/偏移；隐藏历史不返回问题/答案 |
| 事务/金额 | 向量写入异常不留下可见半批；并发预留竞争只允许余额足够的一方；未知 usage 不释放金额，重复结算不重复扣费，授权过期阻止预留 |
| SSE/HTTP | no-store、未知请求字段拒绝、旧 Last-Event-ID 重连重新生成隐藏快照，不重放较后结果 |
| 前端 | `npm run typecheck` 和最终 `npm run build`（含 vue-tsc）通过；保留既有主包超过 500 kB 的警告 |
| 页面 | 默认桌面和 390×844 窄屏：助手历史、回顾引用、多线索人物查证、范围确认与状态更新；服务端回退自动关闭预览并隐藏旧任务 |
| 文档与差异 | 合同、路线、功能清单和交接同步；已检查 140 个本地文档链接及 `git diff --check`，无失效链接或空白差异错误；README 后续更新的报告链接指向本文 |

完整后端回归 `mvnw.cmd --batch-mode --no-transfer-progress test`：**571 项，失败 0、错误 0、跳过 18，BUILD SUCCESS，3 分 38 秒**，日志为 gitignored `backend/target/reading-task-regression.log`。跳过项包含未启用的真实调用测试及显式浏览器夹具，不能算成已通过。

完整回归后，审查补充后台活跃任务扫描的 keyset 分页（避免无界查询及状态改变导致漏扫），新增超过一页的失效用例，并将人物问题输入计数改为 Unicode code point。最终相关回归 **37 项，失败 0、错误 0、跳过 1（手动浏览器夹具），BUILD SUCCESS，1 分 11 秒**；日志为 `backend/target/reading-task-last-checks.log`。不把前述 571 项称为这些收尾修改后的再次全套执行。

首次运行的一条 policy 测试用 1100 个 emoji 错误假定超出完整规划输入字节上限，断言失败。修正为真正越界的 2100 个 emoji 后通过，未放宽生产预算或删除失败用例。失败日志保留在 gitignored `backend/target/reading-task-tests.log`；后续相关测试日志为 `reading-task-final-tests.log`。

可复查命令（backend，JDK 21）：

```powershell
.\mvnw.cmd -q '-Dtest=ReadingTaskPolicyTests,ReadingTaskPersistenceTests,ReadingLibraryPersistenceTests,ContextPilotApplicationTests,DatabaseMigrationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress test
```

## 页面隔离与观察

页面夹具用 `ReadingTaskPersistenceTests#offlineBrowserFixture` 的显式 `-Dreading.ui=true` 启动，只绑定 127.0.0.1、随机后端端口和 Testcontainers 数据库。Vite 使用 5204。测试生成的 fixture 元数据和结束信号位于 gitignored `backend/target/reading-task-ui.json`、`reading-task-ui.done`；复跑前移走自己上一轮的结束信号。该夹具不会改变生产默认行为。

桌面打开 B=2 的回顾和引用，引用只包含第 1–2 段，未显示同章后半段或真实章标题。通过独立 HTTP 客户端将边界回退至 B=1，未发送浏览器 BroadcastChannel；页面通过服务端范围 SSE 关闭预览并把历史变为隐藏占位。回到 B=2 后，在窄屏提交多线索人物问题，看到一次规划、两次检索和一次回答的完成结果及 SEARCH_MATCHES 覆盖标记。前后核对 resumeRevision 都为 0、resumePosition 都为空，预览及任务操作没有移动续读位置。

本地截图 `backend/target/reading-task-citation.png`、`reading-task-mobile.png`，页面日志 `reading-task-ui.log`。测试服务与容器已停止，未停止用户数据库。页面观察在最后的 usage 响应整理、恢复错误码清理、后台扫描分页、Unicode 问题计数、引用切换时的迟到响应保护和“无需重复索引”文案调整之前；随后前端构建与相关后端回归覆盖收尾修改，没有声称再次走完所有浏览器流程。

## 尚未验收

- 无真实 DeepSeek/DashScope 调用、供应商协议证据、真实费用或语义 TEST 分数；固定返回不代表人物回答正确或绝对不剧透。
- 未完成设备矩阵、浏览器网络故障全面注入、真实读者试用或完整体验/AI 时延评估。
- 当前固定 profile 的不可变增量索引已实现；没有向用户开放 profile 更换/全量重建功能。
- 本轮止于该能力，提醒提交 PR。下一窗口只推进“确认保存回顾笔记”，不恢复旧研究收尾。
