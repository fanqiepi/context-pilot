# 升级研究独立收尾采集（2026-10-19）

结论：继续默认关闭，最终质量验收未通过。生产代码、upgrade-research-v6 Prompt、模型与预算均未改变。用户明确暂不引入人工复核；所有语义分数保持空值，复核材料只作为待办材料。

## 本次证据

| 批次 | 实际采集 | 终态观察（不是质量成绩） | 原始材料 |
| --- | --- | --- | --- |
| 独立 DEBUG | 缓存键、分页、编码、指标四个新来源族各一次 | 3/4 匹配；指标恢复时间题 FINAL_RESPONSE_INVALID | [摘要](../../evals/results/upgrade-independent-debug-2026-10-19/summary.json)、[逐题材料](../../evals/results/upgrade-independent-debug-2026-10-19/review.md) |
| 新冻结 TEST | 六个新来源族、24 项任务各一次；8 直接、6 组合、4 缺口、6 注入 | 18/24 匹配；4 个 FINAL_RESPONSE_INVALID、1 个额外 PARTIAL、1 个 REFUSED | [摘要](../../evals/results/upgrade-fresh-acceptance-2026-10-19/summary.json)、[逐题材料](../../evals/results/upgrade-fresh-acceptance-2026-10-19/review.md) |
| 代表生产服务流程 | 成功、局部缺失、拒答、注入各两题，每题两次；真实 DeepSeek、DashScope、pgvector、GitHub | 15/16 匹配；一次 FINAL_RESPONSE_INVALID；11 次有草稿的运行经独立提案确认保存 | [逐次摘要及原始文件](../../evals/results/upgrade-representative-2026-10-19/summary.json) |

三个采集器分别为 4、24、16 项收集测试通过，表示运行及记录完成，不代表相同数量的业务任务通过。代表组仅 8 个独立任务，重复不扩大分母；合成 TEST 与可变 GitHub 来源不合并计分。代表组使用生产启动、查询、提案与确认服务，但不是 HTTP/SSE 或页面交互验收。

新 TEST 和代表输入均在首次调用前记录锁文件。新 TEST 的事实由助手编写、尚无人审，不能声称经独立人工标注。四个 DEBUG 与六个新 TEST 来源族分离；六个 TEST 来源族中的不同问题仍共享资料，不能冒称 24 个独立产品领域。既有观察过的 TEST 和历史失败没有修改。本次没有调 Prompt，也未重复旧实验。

新 TEST 保留公开合成输入和原始最终模型响应。FRESH-007 输出两个 JSON 对象，FRESH-008/009 缺少必要字段；其余失败以原始响应和生产错误码为准。DEBUG 首次记录没有保存非法响应正文，因此不能将其精确归因为同一种格式错误，也没有为填补正文而重复调用。代表组仅保存第三方来源标识、哈希、抓取时间等元数据，不转载整篇发布记录。

首次新采集器编译缺少 DirtiesContext 导入，修正后执行；[编译失败](../../evals/results/upgrade-fresh-acceptance-2026-10-19/initial-compile-failure.txt)保留，不算模型失败。后续 24 项正常完成。原始源码哈希反映实际运行工作树（基于 601da27），不是声称新增测试已包含在该提交。

## 复现入口

在 backend 使用仓库指定 JDK 21、Docker 和 local-secrets，分别显式指定测试与开关：

```powershell
.\mvnw.cmd -q '-Dtest=UpgradeIndependentDebugLiveTests' '-Dcontextpilot.evaluation.upgrade-independent-debug=true' test
.\mvnw.cmd -q '-Dtest=UpgradeFreshAcceptanceLiveTests' '-Dcontextpilot.evaluation.upgrade-fresh-acceptance=true' test
.\mvnw.cmd -q '-Dtest=UpgradeRepresentativeLiveTests' '-Dcontextpilot.evaluation.upgrade-representative=true' test
```

以上仅为复现记录，当前没有重跑授权需求或必要性；新 TEST 已观察，不能用来循环调优后冒称未见验收。输出使用 UUID 避免覆盖。`evals/tools/upgrade-review.mjs` 生成观察摘要与空白评分材料，不生成语义成绩；旧冻结资产校验通过。没有生产变更，未重复全后端回归或前端构建，历史 528 项回归不等于当前所有新增收集器都在其中。

## 尚未完成

人工复核由用户暂缓；核心修正后的独立验收、语义质量结论和最终启用批准仍未完成。当前证据不足以启用升级研究，保持默认关闭。

展示脚本见[录制脚本](../development/closeout-demo-script.md)。Computer Use 因无法可靠识别 Windows 浏览器当前 URL 而自动中止，已停止界面操作，未生成视频；不得将脚本或页面准备记成录屏完成。

演示环境使用专用容器 `contextpilot-closeout-demo-20261019`（127.0.0.1:55439）、独立公开数据，未修改原开发数据库。演示后端 8080、前端 5200 在交接时仍运行；构建输出、上传与服务日志位于 gitignored backend/target。没有将环境密钥或原始私有配置纳入提交。
