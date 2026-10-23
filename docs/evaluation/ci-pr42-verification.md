# PR #42 的 CI 修复记录

2026-10-23，PR head `860ebb1`。公开 GitHub Actions API 确认[前端通过、后端与密钥检查失败](https://github.com/fanqiepi/context-pilot/actions/runs/39935790278)。诊断连接暂不可用，公开注释只有退出码 1，未读取远端原始日志；下面的具体原因和结果来自本地完整复现，不冒称已验证远端所有失败详情。

## 密钥检查

Gitleaks 8.30.1 扫描 93 个提交，5 条告警都是 acceptance-v13 至 v17 锁文件中公开配置示例的同一 SHA-256。逐条读取各提交的 Git blob 计算，全部相符。精确限定路径、完整字段行及固定指纹的例外加入后，完整历史 0 命中；改变指纹/字段/路径或追加内容的负向探针仍命中。实际本地密钥文件未入库，不改写历史或冻结锁。详见[误报记录](secret-scan-false-positive.md)。

## 后端完整测试

执行与 CI 相同的 `mvnw --batch-mode --no-transfer-progress test`，本地使用 JDK 21 与 Testcontainers 独立数据库。修复前共 667 项，0 assertion failure、4 errors、20 项既有跳过；错误集中在：

- `ContextPilotApplicationTests` 两项：普通测试默认 offline，使现有 application-offline.yml 的 PgVector 自动配置生效；该启动测试已排除数据库自动配置，却漏关向量存储，启动时缺少 JdbcTemplate。只给该测试显式设置 `spring.ai.vectorstore.type=none`。
- `ResearchStartPersistenceTests` 两项：当前索引为 `offline_deterministic_1024_v1`，夹具却硬编码 DashScope profile。诊断断言明确复现两者不同，生产服务正确拒绝不匹配文档。改为通过实际 EmbeddingIndexProperties 生成 source_document 字段与向量 metadata，保留文档资格、事务、幂等与取消断言；并检查夹具确有当前向量。没有修改生产校验或重新启用本地密钥导入。

本轮不改变生产代码、日常开关或 Prompt v6，不增加真实模型调用；实现冻结仍为 acceptance-v17。新测试改动不涉及该冻结清单中的文件，旧锁保持原样。

修复后再次运行完整测试：667 项中 647 通过、20 项按既有条件跳过，0 failures、0 errors，BUILD SUCCESS（3 分 48 秒）。两个启动/文档测试、三个研究启动/取消/接口测试全部通过。acceptance-v17 资产与源码哈希匹配，diff 检查通过；前端没有改动且该 PR 的前端 CI 已通过，本轮不重复前端构建。

远端通过与否须以推送本地修复后的实际 CI 为准，不自动推送、关闭检查或合并 PR。
