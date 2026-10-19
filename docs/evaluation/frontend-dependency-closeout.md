# 前端依赖告警收尾（2026-10-19）

同机干净工作区复跑发现的 21 个受影响依赖节点已通过兼容版本更新消除。更新前[官方审计报告](../../evals/results/clean-replay-2026-10-19/frontend-audit-official.json)保留；更新后重新 `npm ci`，安装树的[官方审计结果](../../evals/results/frontend-dependencies-2026-10-19/audit-after.json)为 0。扫描结果只覆盖当时 npm 已知告警，不能替代全面安全审查。

## 使用方式核查

| 依赖 | 当前用途与告警触发条件 | 本次处理 |
| --- | --- | --- |
| Axios | `src/api/client.ts` 创建浏览器 `/api` 客户端；不在 Node 服务中使用其 HTTP/2、代理或 data URI 适配器。没有据此排除所有原型污染组合风险。 | 1.18.1 → 1.20.0 |
| DOMPurify | `src/utils/markdown.ts` 清洗 Markdown 生成的字符串；没有 IN_PLACE、live DOM 或 addHook 调用，不满足所报原地清洗问题的前提。 | 3.4.13 → 3.4.17 |
| Vue | Vite 客户端应用，没有 SSR 入口或 renderToString；所报 server-renderer 问题未发现生产调用路径。 | Vue 及匹配子包 3.5.40 → 3.5.44 |
| PostCSS / source-map-js | Vue/Vite 构建链处理仓库源码；未提供运行时用户 CSS／source map 编译接口。 | 8.5.20 → 8.5.31 / 1.2.1 → 1.2.2 |
| nanoid | 构建链传递依赖；应用源码没有 custom generator 调用。 | 3.3.16 → 3.3.20 |

核查结合仓库调用点及公告：[DOMPurify](https://github.com/advisories/GHSA-p98j-92pf-mc4p)、[Vue SSR](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6)、[PostCSS](https://github.com/advisories/GHSA-fxqj-rqcc-2cmp)。这里记录的是调用路径分析，不是针对每条公告的漏洞复现测试。

## 变更与验证

直接依赖最低版本随更新上调；传递依赖写入 lockfile，没有增加依赖或强制 overrides，没有跨主版本升级，Vite 维持 8.1.5。更新 Vue 带来的 Babel 等传递变更见[完整版本差异](../../evals/results/frontend-dependencies-2026-10-19/version-changes.json)。重新解析的包使用官方 npm registry；没有更改用户全局 npm 配置。

依次执行 lockfile 更新、`npm ci --no-audit`、`npm run build`（含 vue-tsc 类型检查）、官方 registry `npm audit --json` 和 `npm ls --depth=0`，全部退出码 0。[构建日志](../../evals/results/frontend-dependencies-2026-10-19/build-output.txt)保留既有 500 kB 大包提示。业务代码和后端未因本次依赖更新而变化；本轮未重跑后端、浏览器交互或真实模型验收。

先前干净快照仍使用旧锁文件，原证据不覆盖、不重写；本次验证对应当前工作区更新后的锁文件。整体收尾仍等待模型专项、独立验收、人工评分及展示交付。
