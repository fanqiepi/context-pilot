# 统一基线资产与报告入口

Node.js 22，无新依赖。从仓库根目录运行：

```powershell
node evals/tools/baseline.mjs validate
node --test evals/tools/baseline.test.mjs
node evals/tools/baseline.mjs template core-workspace-v1.jsonl LIVE_END_TO_END
node evals/tools/baseline.mjs report backend/target/core-observations.json
node evals/tools/baseline.mjs compare backend/target/baseline-observations.json backend/target/candidate-observations.json
```

输出是 JSON，可重定向到 gitignored `backend/target/`。Windows PowerShell 5.1 重定向默认为 UTF-16，需使用 `| Out-File -Encoding utf8`，读取时移除 BOM 或使用 Node 写文件；推荐通过 Node `fs.writeFileSync` 保存 UTF-8。

此入口校验冻结资产并汇总执行证据，**不执行应用、不调用模型、不凭数据集内容生成通过结果**。template 的每条结果都是 NOT_RUN；补入真实运行观测和人工评分后才计算质量。完整业务运行仍须使用对应版本执行器/API及故障注入，不能把本工具测试通过写成 50 个任务通过。

核心 TEST 为 50 条，DEBUG 为 3 条；V6 TEST 为 24 条，DEBUG 为 1 条。不同 split 使用不同资料族，事实标签与行为前置条件联合去重；同一事实的普通问答和隔离测试是不同风险场景，但不得声称代表 50 个独立产品领域。工具允许列表由 dataset manifest 继承；核心集不允许模型工具，V6 只允许合同内三个读工具。来源带文件与标题锚点；空来源表示缺证据或纯状态机任务。

`core-baseline-v1.lock.json` 对所有问题、语料、评分规则和 V6 预算记录 SHA-256。修改冻结输入应发布新版本，不重新生成同名锁文件掩盖差异。调试改进仅使用 DEBUG；TEST 只用于阶段验收，不能循环调 Prompt 后继续报告为未见测试集。

冻结文本使用 LF 换行，`.gitattributes` 对 `.jsonl` 也强制 LF。2026-10-19 复跑发现旧 Windows 检出因 `core.autocrlf=true` 把四份 JSONL 转为 CRLF；恢复 LF 后均匹配原锁。若旧工作区仍报哈希不一致，先检查换行和实际 diff，不重新生成锁来掩盖差异。

观测文件保留 code commit、worktree fingerprint（未提交代码不可只写 HEAD）、Prompt、模型、Embedding profile、检索配置、预算和环境版本。无适用值明确写 NOT_APPLICABLE，不能留空。结果的 facts 与案例 facts 一一对应，reviewer 标识实际复核者。引用分母为人工复核的声明数，不是引用编号数量；没有人工复核不能填 100%。安全或状态机案例由复核者结合测试报告/数据库观测评分。

ENGINEERING、MODEL_FIXED_RETRIEVAL、LIVE_END_TO_END 分开报告。报告包含逐条终态、质量、延迟和 usage/可靠性汇总。重试/稳定性重复运行应写成独立观测文件，不能增加任务分母；候选与基线环境和冻结资料必须相同，采用决定始终留待复核。

当前报告入口尚不汇总原始检索排名、首次进度/首字分位数及价格估算；这些原始字段和定义已预留，未测量项不得宣称通过。完整跨版本展示交付与统一量化仍需后续真实执行和这些指标补齐。

## 核心实跑复核材料

`CoreWorkspaceLiveTests` 的原始采集格式保留完整公开回答与生产历史。运行 `node evals/tools/core-report.mjs <raw.json> <output-prefix>` 生成 `-summary.json` 和 `-review.md`；运行 `node --test evals/tools/core-report.test.mjs` 验证缺失/重复案例不能减少分母，以及终态匹配不能生成语义成绩。汇总仅记录观测与待复核状态，不替代既有人工评分协议。
