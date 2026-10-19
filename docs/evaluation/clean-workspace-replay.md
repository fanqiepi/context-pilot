# 同机干净工作区复跑（2026-10-19）

本次使用当前未提交工作区的 649 个源文件建立独立快照，文件哈希见[清单](../../evals/results/clean-replay-2026-10-19/source-manifest.json)。没有复制密钥、上传数据、node_modules 或构建输出。复用本机已安装的 JDK 21、Node 22.12.0、Docker 与镜像，并可能复用 Maven Wrapper 分发缓存；不是全新机器安装验证。

快照位置为忽略目录 `backend/target/clean-replay-20261019`。Maven 使用独立 `.m2-clean` 仓库，npm ci 使用独立 `.npm-clean` 缓存。Docker Compose 使用独立项目 `contextpilot-clean-20261019`、全新数据库卷与回环动态端口，不接触原开发数据库。

## 记录边界

- 前端 `npm ci`、`npm run build` 已通过；build 包含类型检查，保留既有大包提示。
- 后端独立依赖下载及 `package -DskipTests` 通过，构建用时 7 分 2 秒；本轮不冒称全量测试重跑，既有全量结果见[工程回归](closeout-engineering-regression.md)。
- 离线启动与 API 冒烟通过，见[原始结果](../../evals/results/clean-replay-2026-10-19/smoke-results.json)及[验证脚本](../../evals/results/clean-replay-2026-10-19/smoke.ps1)。覆盖新库、确定性问候、空库拒答、历史读取、独立确认与幂等、文档入库、SSE 和文档删除后的列表行为。历史仅收集结果，本脚本没有逐字段断言历史内容。
- [数据库检查](../../evals/results/clean-replay-2026-10-19/database-checks.txt)确认 28 项迁移成功、最新版本 28，删除后业务文档保留为 deleted=1、向量清除。文档入库状态为 SUCCEEDED，索引为离线 1024 维 CURRENT 配置。
- 初次脚本把空 JSON 数组包成嵌套 PowerShell 数组，错误判断数据库非空；[失败记录](../../evals/results/clean-replay-2026-10-19/smoke-harness-array-error.json)保留。修正收集脚本后通过，没有修改产品代码或数据库数据来绕过断言。
- 完成后已停止本次后端进程，移除本次专用容器、网络和数据库卷；原开发数据库未改动。源码快照与构建日志保留在忽略目录，复跑脚本及结果另存版本化证据。
- 本轮不使用模型密钥，不验证真实生成质量，也不替代展示录屏和人工语义验收。

## 依赖审计发现

安装输出报告 7 个依赖告警；配置镜像的独立 audit 接口返回 HTTP 400。随后显式使用官方 npm registry，得到[完整原始报告](../../evals/results/clean-replay-2026-10-19/frontend-audit-official.json)：21 个受影响依赖节点（19 high、1 moderate、1 low），包括传递影响，不能解读为 21 个独立漏洞。

报告涉及 Axios、DOMPurify、Vue SSR 及构建链依赖。本快照保留当时的旧锁文件和原始扫描，不根据构建通过宣称依赖安全。随后已在主工作区完成调用路径核查、兼容更新与复验，官方审计为 0，见[依赖收尾记录](frontend-dependency-closeout.md)；没有执行 `npm audit fix --force`，新结果不覆盖本次快照证据。
