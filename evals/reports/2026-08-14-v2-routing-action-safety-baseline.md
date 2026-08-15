# V2 路由与操作安全基线报告（2026-08-14）

## 结论

状态：**通过**。

固定数据集 `v2-routing-action-safety` 版本 `1.0.0` 在能力版本 `v1`、动作
`CREATE_KNOWLEDGE_BASE` 下达到全部配置阈值。测试未调用聊天模型或 Embedding 服务；
持久化、状态迁移和并发确认使用真实 PostgreSQL/pgvector 容器执行。

## 评估资产

- 数据集：`evals/datasets/v2-routing-action-safety-v1.json`
- 配置：`evals/configs/v2-routing-action-safety-v1.json`
- 测试入口：`backend/pom.xml` 中的 Maven Profile `v2-evaluation`
- 路由、参数、篡改与内部失败测试：`V2RoutingActionEvaluationTests`
- PostgreSQL 状态机测试：`V2ActionSafetyEvaluationTests`
- Docker 前置条件测试：`V2EnvironmentEvaluationTests`

数据集共 41 条固定案例：24 条路由、8 条参数、1 条客户端篡改合同和 8 条动作生命周期案例。

## 运行环境

- 日期与时区：2026-08-14，Asia/Shanghai
- 操作系统：Windows 10 22H2
- Java：Microsoft OpenJDK 21.0.3
- Spring Boot：4.1.0
- Testcontainers：2.0.5
- Docker Desktop Engine：29.6.1
- 数据库镜像：`pgvector/pgvector:0.8.2-pg17-bookworm`
- 容器数据库：PostgreSQL 17.10，Flyway V1-V10

## 结果

| 指标 | 阈值 | 结果 | 判定 |
| --- | ---: | ---: | --- |
| 路由准确率 | 100% | 24/24（100%） | 通过 |
| 业务操作误触发率 | 0% | 0/12（0%） | 通过 |
| 缺名澄清正确率 | 100% | 2/2（100%） | 通过 |
| 强类型参数校验通过率 | 100% | 8/8（100%） | 通过 |
| 客户端动作/参数篡改防护 | 100% | 1/1（100%） | 通过 |
| 动作安全案例通过率 | 100% | 8/8（100%） | 通过 |

24 条路由案例各重复执行 3 次，能力、版本和匹配原因均无波动。动作安全案例覆盖：

- 未确认、已拒绝、已过期提案均不创建目标知识库；
- 重复确认和两个线程并发确认均只创建一个目标知识库；
- 名称冲突进入 `FAILED`，不产生第二个同名知识库；
- 未预期执行异常只返回固定安全摘要；
- 历史恢复返回最终提案状态；
- 所有真实持久化案例均核对能力 ID、能力版本、动作类型和 trace ID。

Maven 最终结果：45 个测试，0 失败，0 错误，0 跳过，`BUILD SUCCESS`。

## 复现命令

在 `backend/` 目录执行：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv2-evaluation test
```

评估 Profile 强制检查 Docker 可用性。若真实 PostgreSQL 生命周期案例被跳过或 Docker 不可用，
本基线不能视为复现成功。

## 边界与后续

- 本报告只评价 V2 确定性能力路由和首个受控动作，不替代 V1 RAG 检索、引用、忠实度和拒答质量基线。
- 固定表达集用于回归和可比较性，不代表开放世界自然语言覆盖率；新增真实歧义样本应先脱敏，再提升数据集版本。
- 当前并发案例验证两个同时确认请求；提高并发规模或改变事务/数据库配置时应增加压力与稳定性评估。
- 路由规则、参数上限、状态机、能力版本或确认 API 合同变化后，本报告失效，必须生成新版本基线。
