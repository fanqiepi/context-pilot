# 项目资料接入首版验证

结论：已完成固定公开项目资料的 MCP 读取、不可变预览、独立确认导入、来源追溯与研究工作台衔接。工程回归及公开真实流程通过。独立语义质量评测不在本次首版结论内；升级影响研究仍保持原有未通过质量验收的状态。

## 工程结果

- JDK 21 完整回归通过：474 项，465 通过、9 项条件跳过，0 失败、0 错误。付费真实模型测试默认跳过。完整日志在 gitignored `backend/target/project-source-regression-final.log`，最终打包阶段因旧演示进程占用 jar 而失败；停止该进程后，`mvnw.cmd -DskipTests package` 成功（同一份已通过回归的代码），日志在 `backend/target/project-source-package-final.log`。原始 Surefire XML 在 `backend/target/surefire-reports/`。
- 前端 `npm run typecheck`、`npm run build` 通过；保留原有大 chunk 提示。
- V23–V26 在独立 PostgreSQL/pgvector Testcontainers 中迁移通过；最终 jar 启动本地数据库并加载 V26 成功。重启后既有成功导入仍可读取；对同一快照新建提案并确认，复用既有文档，没有重复登记。
- 最初全量回归中，无数据库 OpenAPI 测试缺少新模块替身依赖导致 2 项错误；已补齐替身并增加新预览接口检查，最终完整构建无错误。未将首次失败结果记为通过。

| 合同与门禁 | 可复核证据 |
| --- | --- |
| 路径范围、重复路径、编码、字节和快照哈希 | `ProjectSourcePolicyTests`：非法路径、非文本、非法 UTF-8、重复项、伪造哈希 |
| 固定 Git 目录、符号链接、响应上限、禁止重定向 | `GitHubProjectSourceReaderTests`：真实本地 HTTP fixture；逐层树检查、只读 blob、内容 Git SHA 与 SHA-256 校验；重定向不访问下一跳 |
| MCP 协议、工具 schema、固定调用范围 | `ProjectSourceMcpTests`：真实 Streamable HTTP 握手与读取；外部端点、额外工具、未知协议和服务不可用均拒绝 |
| 未确认零文档副作用、提案重放、并发确认 | `ProjectSourcePersistenceTests`：预览及提案后目标库文档数为零；四并发确认只登记一次；此组关闭索引处理，真实索引另由公开流程覆盖 |
| 跨知识库隔离、重复导入、删除重放 | 持久化测试：同一快照可进入两个库，文档 ID 与存储键隔离；同库复用；已删除身份不复活 |
| 整批事务与恢复 | 持久化测试：数据库触发器强制来源登记失败，文档和执行抢占一起回滚；重复确认成功；V25 保留提案关联的暂存文件元数据 |
| 文件失败定位 | 持久化测试验证第二份文件不存在时返回其路径和安全错误码，整批不发布任何预览条目 |
| 到期与拒绝 | 持久化测试：拒绝后确认仍为 REJECTED；过期预览不能创建新提案 |
| 可变 tag 不改变已预览内容 | 持久化测试在预览后将远端 ref 改为另一 commit；确认不再次读取远端，来源仍是原 commit |
| 超时、中断与迟到结果 | 真实 MCP 与数据库测试使用可控慢读取；短测试预算触发 FAILED/SOURCE_TIMEOUT，迟到返回不能插入条目；生产预算仍固定 90 秒 |
| 重启恢复 | 持久化测试对运行中预览执行与启动相同的恢复扫描，持久化 SOURCE_PROCESS_RESTARTED；迟到结果不能覆盖失败状态 |
| 保留旧行为 | 完整回归包含上传、确认动作、维护、RAG、比对、显式记忆、升级研究和任务工作台既有测试；原路由和工具允许列表未扩大 |

这些是工程可靠性证据，不是问答准确率样本。持久化终态检查阻止迟到数据写回，不承诺远端已经停止处理或计费。测试对应工作区源文件哈希见[代码清单](../../evals/reports/project-source-code-manifest.json)，不将未提交工作区冒充正式发布版本。

## 真实公开流程

`ProjectSourceLiveTests` 单独启用真实 GitHub、DashScope Embedding、DeepSeek 和独立数据库，完成：读取 → 预览 → 提案 → 两次独立确认请求 → 单份文档登记 → 索引 → 工作台计划 → 用户开始 → 带引用结果。

- 仓库：`spring-projects/spring-ai`；tag：`v1.0.0`；文件：`README.md`。
- 固定 commit：`fcd2803f36f35939c20ed152c9c1b0863fa35dcf`。
- 内容 SHA-256：`c38d40416f5154f53e5a7fd20fc519d6ead9e7910c3744cd9d04d24e1eddd5e9`。
- MCP 协议：`2025-11-25`；Embedding 生成 17 个片段。
- 研究生成两个限定文档检索步骤，最终 COMPLETED。两次模型调用返回的 model 标识为 `deepseek-flash`，usage 合计 2519 tokens；不包含 Embedding，不代表费用总额。

人工核对：研究结果解释 Spring AI 的目标及 README 明确列出的能力，主要结论对应 E1/E4。对被切分截断的 ETL 文字明确保留资料缺口，没有将不完整名称当作确定结论。该样例证明流程衔接和可追溯性，不证明完整召回率或独立语义成功率。

完整公开调试输出保留于 gitignored `backend/target/project-source-live.json`；可提交摘要见 [真实流程摘要](../../evals/reports/project-source-live-summary.json)。测试通过后补充的暂存元数据由全量回归及本地重复导入验证覆盖；失败路径提示由最终全量回归覆盖，不重复产生模型调用。

## 页面和运行验证

本地页面使用单独的“项目资料接入公开演示”知识库，验证版本输入、真实读取、预览刷新恢复、生成提案、提案刷新恢复、独立确认、索引就绪及文档来源弹窗。来源弹窗正确显示 commit、路径、内容哈希、获取时间和固定提交链接。页面演示与独立数据库真实模型测试分别计数，不合并成任务成功率。

页面证据：[导入结果](../../evals/reports/screenshots/project-source-import.png)。已检查当前应用侧栏宽度下的布局，并修复新增导航项挤压换行；没有宣称覆盖全部设备和视口。

运行入口、开关、故障码及 3–5 分钟演示路径见[运行指南](../development/project-source-integration.md)。仓库默认开关为 false，本地验证进程显式启用。未提交、未合并、未发布。

## 保留边界

首版只接固定仓库的 TXT／Markdown，最多 5 个指定路径，不提供目录浏览、代码解析、私有仓库、自动同步、模型工具自动发现或外部写操作。文件路径首版使用有限 ASCII 字符集、最多 8 层。Git 目录响应超出 1 MiB 时明确失败，不静默读取不完整目录。资料预览和暂存文件的物理清理需要未来独立维护流程。

更广语料、检索排序、语义完整性、成本和延迟优化保持后续集中完善；不降低已有功能的验收标准。
