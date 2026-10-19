# 评估源码指纹的密钥扫描误报（2026-10-19）

PR #30 对应的 [CI 运行](https://github.com/fanqiepi/context-pilot/actions/runs/39231193059) 中，后端、前端检查通过，Gitleaks 报告泄漏。GitHub 公开 API 可读取失败状态，但原始任务日志需要授权；以下结论来自本地完整 Git 历史复现，不声称已读取远端全部命中详情。

使用官方发布的 Gitleaks 8.30.1（下载包 SHA-256 与官方 checksums 一致），在 48c3c00 执行 `gitleaks git . --redact`，扫描 60 个提交，得到 128 个 generic-api-key 命中。逐条读取命中提交的 JSON 行，再对该提交对应 Java 文件的 Git blob 计算 SHA-256，128 项全部一致，涉及以下两个源码指纹字段：

- `src/main/java/io/github/fanqiepi/contextpilot/common/ApiErrorResponse.java`
- `src/main/java/io/github/fanqiepi/contextpilot/common/openapi/OpenApiConfiguration.java`

字段名中的 Api 加上高熵十六进制指纹触发通用密钥规则。这些值是源码 SHA-256，不是供应商密钥；本次核对没有发现真实凭据。先前提交前的简单凭据模式检查没有复现 Gitleaks，不能替代 CI 扫描。

根目录 `.gitleaks.toml` 继承全部默认规则，仅在 generic-api-key 中增加 AND 例外：路径必须是 evals/results 下的 JSON，整行必须是上述精确字段名与各自已验证的固定哈希。不同哈希、不同字段、非评估路径或同行额外内容不匹配。没有关闭规则、跳过整个评估目录、重写历史或修改冻结原始结果。配置方式依据 [Gitleaks 官方配置说明](https://github.com/gitleaks/gitleaks#configuration)。

## 验证

- 添加配置后重新扫描同一完整历史：60 个提交，0 命中，退出码 0。
- 在 gitignored 临时目录构造无效合成凭据探针：已验证字段/哈希行被排除；同字段替换成其他值、同目录普通 api_key 字段、评估路径外的相同行仍各命中一次。预期退出码 1，3 个命中，证明没有扩大为目录或通用哈希白名单。
- 初次目录探针从仓库根目录传入临时目录，路径包含 backend/target 前缀，因此严格路径例外未触发，出现 4 个命中；改为在探针目录内运行后符合预期。未为测试调整例外范围。
- 只修改扫描配置和说明，未重复业务、前端或真实模型测试。远端 PR 是否恢复通过须以推送后的 CI 实际结果为准。
