# 确认保存回顾笔记：工程验证记录

日期：2026-10-20。本轮从干净的 `develop/b1e20bb` 开始，响应用户再次提出的“继续开发下一个大功能点”，仅实施 `reading-contract-v1-draft1` 中的确认保存笔记。验证完成后，用户要求提交代码并写好提交信息，授权本地提交，实际提交以 Git 为准。未推送或创建新 PR；远程合并状态未核实。真实生成、embedding 与付费调用均为零。

## 交付范围

- V31 新建阅读笔记提案，扩展 AI 回顾/人物笔记的来源、冻结引用、编辑标记及不可释放的唯一关系。
- 用户编辑后创建不可变提案并取得最终预览；独立确认仅接受 contentHash，拒绝正文、owner 或替代来源等额外字段。同任务最多一份笔记；替代提案原子拒绝旧待确认提案。
- 提案确认与范围回退、笔记管理共享生命周期/状态锁。hash 不符、来源移除与范围 epoch 失效会持久化 FAILED；过期为 EXPIRED。数据库异常回滚笔记插入及执行权，保持可重试的待确认态。
- 笔记继承整个任务输入范围和编辑时当前 B 的较大值，删减引用不能缩小标签；用户编辑只标记内容变化，不冒称新内容已获事实验证。
- 引用复制到笔记并再次核对原文版本、段落、偏移与 hash，任务移除后仍可预览。AI 笔记逻辑删除后不会因重放确认或创建新提案而复活。
- 页面恢复持久预览，确认成功后进入本书笔记；支持编辑、引用删减、逻辑删除、出处预览和显式继续阅读。范围变化、连接重验和切换任务丢弃旧预览及迟到响应。
- 补齐统一阅读错误响应的 `Cache-Control: no-store`；原有成功响应继续保留该头。未新增生产依赖，未接入通用动作/工作流。

## 自动验证

环境：Windows、JDK 21、PostgreSQL 17 + pgvector Testcontainers；迁移只应用于隔离测试数据库，未迁移用户日常数据库。

```powershell
cd backend
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingNoteProposalPersistenceTests,ReadingTaskPolicyTests,ReadingTaskPersistenceTests,ReadingLibraryPersistenceTests,ContextPilotApplicationTests,DatabaseMigrationTests' test
```

49 项，0 失败、0 错误、2 跳过（两套手动浏览器夹具）；包含阅读任务/范围/基础阅读回归、启动及 V12→V31 迁移兼容。日志：gitignored `backend/target/reading-note-final-checks.log`。

新增集成测试覆盖持久预览不写笔记、幂等重放与不同内容冲突、替代/拒绝、并发确认、不同提案竞争、过期、hash 不符、数据库内容被改动、回退再前进、回退与确认竞争、逻辑删除后重放、来源任务移除、跨版本原文错配、PARTIAL 人物结果、Unicode 上限、只删引用及范围提高、未知字段和 no-store。

数据库异常测试在插入笔记后、提交 SUCCEEDED 前注入 PostgreSQL trigger 失败：笔记数保持零，提案仍为 PENDING_CONFIRMATION、结果 ID 为空；移除测试 trigger 后同提案可确认成功。没有把事务未提交的失败误记为成功。

最后调整笔记内部请求 ID 与公开提案 ID 分离，新增“手写笔记恰好使用提案 ID 作为请求键仍可确认”的断言。随后单跑 `-Dtest=ReadingNoteProposalPersistenceTests`：12 项，0 失败、0 错误、1 跳过（手动浏览器夹具），日志为 `backend/target/reading-note-last-checks.log`。幂等副作用仍由 source_proposal_id/source_task_id 唯一约束保证。

前端 `npm run build`（包含 vue-tsc）通过；既有主包大于 500 kB 提示保留。差异检查通过。Gitleaks 8.30.1 扫描本轮已修改/新增文件的路径保留快照及完整 Git 历史（66 个提交）均为 0 命中，未放宽现有配置。

## 桌面与窄屏

使用 `ReadingNoteProposalPersistenceTests#offlineBrowserFixture`，显式设置 `-Dreading.note.ui=true`。测试用两本自创短文和持久化的固定完成任务，不提供生产假模型接口；服务只绑定 127.0.0.1 的随机端口，Vite 代理临时指向它。夹具通过结束文件退出，服务、临时浏览器标签和视口覆盖均已关闭。

- 1280×900：编辑标题和正文→生成预览；API 核对状态 PENDING_CONFIRMATION、笔记数 0、resumeRevision 0。刷新后重新选择任务，恢复同一预览及内容；明确确认后笔记数 1，显示“已确认 AI 回顾 · 已编辑”，出处显示原文，隐藏未完全已读章标题。
- 用独立 HTTP 客户端将 B=2 回退到 B=1，不发浏览器 BroadcastChannel：范围 SSE 自动关闭出处、隐藏笔记标题/正文；resumeRevision 仍为 0。
- 390×844：编辑→最终预览→再次编辑并替代→确认→笔记管理中编辑→原文引用。API 核对一份 AI_RECAP 笔记、revision 1、requiredBoundary 2。预览/保存期间 resumeRevision 为 0；显式“从这里继续读”后变为 1，定位第 1 段，B 仍为 2。
- 截图留在 gitignored `backend/target/reading-note-desktop.png` 和 `backend/target/reading-note-mobile.png`；浏览器夹具日志为 `backend/target/reading-note-ui.log`，夹具测试通过。

## 失败证据与限制

- 首次新增测试因 Map 泛型断言写法编译失败，修正测试类型写法后继续；日志 `reading-note-proposal-tests.log` 保留。
- 首次相关回归 43 项中出现 1 个 no-store 错误响应断言失败，修复统一阅读错误响应后，49 项回归通过；原日志 `reading-note-related-checks.log` 保留。
- 浏览器启动的首次 npm 参数转发失败，改为直接调用已安装 Vite CLI，未修改生产配置。刷新/关闭抽屉动画中一次过早点击未选中任务，等待当前页面稳定后正常恢复。
- 关闭/刷新 SSE 时现有异常处理仍可能记录断开连接的转换警告；本次不改写为无告警验收，未影响保存或范围隐藏验证。
- 固定任务只证明业务、事务和页面合同，不代表真实模型语义、防剧透、费用或真实读者体验验收。生产阅读 AI 仍拒绝真实调用。下一轮的体验/展示工作必须继续区分离线验证与独立授权的付费评估。
