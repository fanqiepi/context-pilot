# V3 知识库健康检查与维护固定评估基线

> 评估日期：2026-08-15（Asia/Shanghai）
>
> 数据集：`v3-health-maintenance-v1`
>
> 配置：`v3-health-maintenance-v1`
>
> 结论：通过

## 环境与入口

- Microsoft OpenJDK 21.0.3。
- Docker Desktop 29.6.1。
- PostgreSQL 17.10 与 `pgvector/pgvector:0.8.2-pg17-bookworm`。
- Flyway V1-V14 全量迁移。
- 在 `backend/` 执行 `.\mvnw.cmd -Pv3-evaluation test`。

专项 Profile 强制启用 Docker 前置检查，运行 V1/V2 固定评估回归、V3 确定性数据集和选定的真实 PostgreSQL/pgvector 生命周期测试。真实环境不可用或测试被跳过时不能生成通过结论。

## 固定指标

| 指标 | 结果 | 阈值 | 结论 |
| --- | --- | --- | --- |
| 健康意图路由准确率 | 16/16，100% | 100% | 通过 |
| 健康意图误触发率 | 0/10，0% | 0% | 通过 |
| 健康状态分类准确率 | 12/12，100% | 100% | 通过 |
| 完整性分类准确率 | 12/12，100% | 100% | 通过 |
| 修复资格判断准确率 | 12/12，100% | 100% | 通过 |
| 生命周期安全覆盖 | 9/9 类场景 | 100% | 通过 |
| 历史与 trace 完整性 | 覆盖且全部通过 | 100% | 通过 |

路由案例固定重复运行 3 次，结果无波动。健康案例覆盖 `EMPTY`、`HEALTHY`、`IN_PROGRESS`、`ATTENTION_REQUIRED`、`UNKNOWN`，以及 `COMPLETE`、`PARTIAL`、`TRUNCATED`；问题类型覆盖处理失败、profile 未知、profile 过期和当前 profile 向量缺失。

## 自动化结果

| 验证项 | 结果 |
| --- | --- |
| `.\mvnw.cmd -Pv3-evaluation test` | 118 项测试，0 失败，0 错误，0 跳过，构建成功 |
| V1/V2 路由与动作安全回归 | 通过 |
| PostgreSQL/pgvector 隔离、实际向量与 Flyway V1-V14 | 通过 |
| 不可变报告、可信明细选择与历史恢复 | 通过 |
| 未确认零副作用、实时双重校验、重复/并发确认 | 通过 |
| 提交后派发、队列拒绝保持 `PENDING`、有界恢复与原子抢占 | 通过 |

## 基线边界

本报告只使用公开、确定性的合成输入，不包含真实用户文档、密钥或未脱敏请求。固定评估证明当前规则与受控生命周期达到配置阈值，不替代真实页面像素级验收，也不授权 V4 或任何通用工具、动态执行与批量修复能力。
