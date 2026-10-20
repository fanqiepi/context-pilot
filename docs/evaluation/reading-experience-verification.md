# 续读体验与展示准备：离线验证

2026-10-20。起点为干净的 `develop/42686ca`。本轮只推进“验证续读体验并准备展示”；已完成离线准备，**整项体验/语义验收未完成**。未调用真实生成或 embedding、未消费额度、未改日常数据库。PR #34 核对时仍打开，#33 已合并。验证完成后，用户授权本地提交，实际提交以 Git 为准；未推送或创建 PR。

## 本轮实现

固定语料与 60 条独立编号问题（不是 60 个独立来源）见[数据集](../../evals/datasets/reading/acceptance-v1.json)，来源见[语料说明](../../evals/corpora/reading/acceptance-v1/provenance.md)。TEST 改写版与原版同属一个来源族；DEBUG 使用不同故事。当前资产为合成短篇与已知作品短摘录，不能据此推断真实长篇语义质量。

[工具](../../evals/tools/reading-README.md)实现不可覆盖的冻结、源码/语料指纹核对、题目/证据/范围校验，以及人工评分后的分项统计。没有网络执行功能，模板全为 NOT_RUN，真实评分尚未填写。参考事实和禁用事实仅供复核，不送模型；独立语义复核待真实运行后执行。

本轮只改书架的过时说明，明确阅读助手工程流程已接入而真实 AI 未开放；不改任务编排、生产适配器、V4/V5、迁移和 API。

## 自动验证

环境：Windows 11；AMD Ryzen 7 5800H，8 核/16 线程，物理内存 34,221,649,920 bytes；JDK 21.0.3；Node 22.12.0；Docker 29.6.1；PostgreSQL 17.10 / pgvector 测试容器。只使用本机隔离数据库。

```powershell
node --test evals/tools/reading-eval.test.mjs
node evals/tools/reading-eval.mjs validate

cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingLibraryPersistenceTests,ReadingTaskPolicyTests,ReadingTaskPersistenceTests,ReadingNoteProposalPersistenceTests' test

cd ../frontend
npm run build
```

- Node 工具 16 项通过：越界/错版/窗口遗漏、split 污染、缺题/重复、未知重复轮次、冻结漂移、空答不能满分、缺引用计失败、pooled/macro 区分、剧透阻断、拒答/技术失败区分、未知费用保留、真实运行来源字段等。
- 后端 47 项，0 失败/错误，2 跳过（手动浏览器夹具）；其中新增固定语料与生产解析器一致性、位置写入采样。隔离库 Flyway 到 V31。
- 前端 `npm run build`（含 vue-tsc）通过；既有大于 500 kB 主包警告保留。
- 手动浏览器夹具另行显式运行，不将默认跳过伪称已执行的 UI 测试。
- 冻结清单的资产与源码哈希匹配；108 行未运行模板汇总为 INCOMPLETE / NOT_DETERMINED，事实率均为 null。
- 本轮改动及新增文件的路径保留快照经 Gitleaks 扫描为 0 命中，未放宽规则；本地文档链接与 `git diff --check` 通过。

日志在 gitignored `backend/target/reading-experience-checks.log`、`reading-experience-ui.log`；可提交的非敏感证据在 [reading-experience-v1](../../evals/reports/reading-experience-v1/)。

## 容量观测

固定生成 500 万正文 code points、1000 章、10000 段；正文同字重复，验证容量/定位，不验证语义。进程、连接池、数据库均已启动；新文件导入一次，随后在最后章节采样。不是清空 OS/数据库缓存后的冷启动。

| 项目 | 观测 |
| --- | --- |
| 注册、解析并形成预览 | 901 ms |
| 确认导入并写库 | 1647 ms |
| 正文分页服务调用 | 30 次，nearest-rank p95 7 ms |
| 续读位置写入服务调用 | 30 次，nearest-rank p95 8 ms |

每次写入后读取确认 offset，已读边界仍为 0。原始样本、文件哈希、数据库/JVM 环境见 `capacity.json`。位置前置 GET 和断言不计入写入时延；未包含 HTTP、浏览器、网络、重启或真实大段落分布。不能将本表当作页面可操作或真实 AI 时延。

## 页面代表流程

使用既有 `ReadingNoteProposalPersistenceTests#offlineBrowserFixture`、Vite localhost 代理和测试随机端口；固定完成任务为自创短文本，不注册生产假模型。基础页面与任务内容无付费调用。

- 1280×900：书架点击继续阅读→助手固定结果→引用→一次关闭返回→编辑标题→生成最终预览→独立确认。API 在预览时笔记数为 0、resumeRevision=0；没有将预览当作保存。
- 390×844：已保存笔记→原文预览，出处保持第 1 章第 1 段；显式“从这里继续读”后 resumeRevision=1、位置第 1 段 offset 0，B 仍为 2。返回书架一次点击继续阅读、再刷新，均显示“已恢复上次保存的位置”。笔记数 1，保留 E1 与 requiredBoundary=2。
- 截图已目视核对，窄屏原文与按钮未截断。临时视口已重置，浏览器、Vite 和夹具在结束时关闭。截图与最终安全 API 状态在证据目录。

本轮没有完整重做每条流程在两个视口的所有组合，也没有实测浏览器首屏/继续阅读两秒门槛；本轮代表证据与历史笔记/范围 UI 记录共同供复核，不能称“全量体验通过”。没有真实读者样本、没有新录屏。

## 运行中观察与限制

- 首次浏览器点击已触发导航，紧接着寻找旧页链接超时；新快照确认已进入阅读页，没有重复提交业务请求。
- 引用抽屉关闭动画中一次过早点击未展开编辑，等待隐藏状态后重新点击成功。说明演示脚本应以界面稳定为准，不把工具选择超时当作模型/业务失败。
- 库启动保留 Mockito 自附加、OpenAPI 默认启用等已有提示；前端包体警告没有消失。
- 生产供应商适配和协议测试仍缺失，价格未核实，授权为零；当前工程账本和固定数据不构成真实协议/费用证据。
- 语义采集、人工评分、真实试用、冷/热浏览器性能、录屏均未完成。独立问题与参考由本轮编制，结构校验不是人工语义验收。

[演示脚本及求职说明](../development/reading-demo.md)只描述已验证工程能力。后续继续本能力的真实适配/协议与验收准备，付费批次须材料齐备后另获授权。
