# 导入并持续阅读：工程验证记录

日期：2026-10-20。对应已批准 `reading-contract-v1-draft1` 的首个功能点；不是整条阅读路线的验收报告。本文记录本地提交前的验证结果；开发分支 `develop`，起点 `5c9dfb9`，最终提交以 Git 历史为准。

## 实现与隔离

新增 TXT/Markdown 确定性解析、可重选规则的异步预览、原子确认和不可变正文版本；书架、分页阅读、设置、自动续读、书签与手写/摘录笔记。基础已读边界独立确认，较后笔记和标签在回退后隐藏。编辑摘录保留原选区，页面区分编辑内容和原文。新增 V29 九张表，无新增生产依赖。

测试使用 JDK 21.0.3、PostgreSQL 17/pgvector 0.8.2 Testcontainers。页面检查使用独立临时 PostgreSQL（15442）、后端（18082）、Vite（5202），禁用 chat/embedding/vectorstore/旧任务后台处理。原本本地数据库 15432 未执行 V29。原始上传存于 gitignored `backend/target/` 下，未物理删除用户上传。

## 已执行检查

| 检查 | 结果与边界 |
| --- | --- |
| `BookParserTests` | 5 项通过：UTF-8/章节规则/Markdown 代码围栏/Unicode 分段及容量等确定性解析 |
| `ReadingLibraryPersistenceTests` | 9 项通过；最终书签冲突修改后重跑 9 项全部通过 |
| `DatabaseMigrationTests` | 1 项通过，保留既有数据并从 V12 升至 V29；新建空库也完成全部迁移 |
| `npm run typecheck`、`npm run build` | 通过；最后一次 build 内再次执行 vue-tsc；保留既有主包大于 500 kB 的构建警告 |
| 页面代表流程 | 自编 3 章文本导入预览、确认、阅读、位置保存、书签、手写笔记；窄视口人工检查，无完整设备矩阵 |
| 后端重启 | 重启后恢复同一 paragraphId/offset，resumeRevision=1、readBoundary=3、scopeRevision=1 均保留；页面显示恢复成功，书签与手写笔记仍可读取 |
| 文档与差异 | 17 个入口/合同文档的 247 个本地链接无失效；`git diff --check` 通过 |

页面截图保存在本地 gitignored `backend/target/reading-library-ui.png`。验证结束已停止本轮临时前后端和专用数据库容器，未停止用户原数据库。

持久化测试覆盖：并发确认仅一版本；重复文件与重解析失效；数据库故障回滚无半成品；跨主体、跨版本、段落定位验证；续读与边界独立 CAS；未知字段拒绝/no-store；multipart 上传与异步解析；逻辑删除及幂等不复活；笔记范围收缩隐藏、摘录来源验证和编辑标记。使用真实数据库、无模型替身评分。

测试源码：[解析测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/book/BookParserTests.java)、[持久化测试](../../backend/src/test/java/io/github/fanqiepi/contextpilot/book/ReadingLibraryPersistenceTests.java)。可复查命令（backend 目录、JDK 21）：

```powershell
.\mvnw.cmd -q '-Dtest=BookParserTests,ReadingLibraryPersistenceTests,DatabaseMigrationTests' test
```

## 容量观察

合成 500 万正文字符、1000 章、10000 段；最终复跑解析 725 ms、确认入库 1763 ms，30 次正文分页采样 p95=5 ms。此前同机运行分别为 933/1764/5 ms。测试输出 `READING_CAPACITY` 保留在本地 Surefire 报告。这是热身/本机合成文本工程数据，不是生产 SLA，也不是复杂真实小说语义或所有容量上限的验收。

## 未完成与后续边界

- 完整 ReadingScope 服务、范围状态 SSE、统一结果发布/失效协议及引用预览留给下一功能点；本轮有基础 revision/visibilityEpoch 和笔记可见性，不能声称完整防剧透保证。
- 索引、回顾、人物查证、AI 笔记提案未实现；真实模型/embedding 调用为零，付费实验为零。
- 旧研究功能未扩展，未重跑无关真实模型验收；既有语义失败及暂停状态保留。前端旧页面随整体构建检查，迁移回归保留旧数据。
- 本轮结束提醒用户提交 PR，用户开新窗口后再进入下一功能点，不继续实现全部路线。

## PR 启动回归修复

用户提交 PR 后，CI 的 `ContextPilotApplicationTests` 启动失败。本地复现的首个异常为 `BookImportService` 缺少 `BookMapper`；`ApplicationContext failure threshold` 是随后跳过重复加载的提示。该测试关闭数据库自动配置、使用 Mapper mock，但新增阅读模块未同步补齐测试依赖。首次交付的 15 项相关测试没有覆盖这项启动回归，不能据此声称完整后端测试通过。

修复仅补齐 Book、ReadingState、ReadingBookmark、ReadingNote 四个 Mapper 的 `@MockitoBean`，保留真实领域服务和 Controller；增加阅读导入、书架、状态、笔记的 OpenAPI 路径断言。没有跳过启动测试或调整生产配置。

2026-10-20 修复后使用 JDK 21 执行与 CI 同参数的 `.\mvnw.cmd --batch-mode --no-transfer-progress test`：**547 项，失败 0、错误 0、跳过 17，BUILD SUCCESS**，耗时 3 分 18 秒。启动与 OpenAPI 两项均通过；未启用任何真实模型实验 profile。完整本地日志保存在 gitignored `backend/target/reading-ci-regression.log`。这是 Windows 本地全套后端回归结果，远程 Linux CI 仍需推送修复后重新确认。
