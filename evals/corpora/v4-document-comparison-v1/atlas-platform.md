# Atlas Platform（合成评估资料）

## deployment
Atlas 采用 Kubernetes Helm 部署，控制面和工作节点分离，升级通过滚动发布完成。

## limits
Atlas 单工作区最多 80 个项目，默认 API 峰值为每分钟 1200 次请求。

## security
Atlas 使用短期访问令牌、静态加密和基于角色的项目权限。

## observability
Atlas 导出 OpenTelemetry 指标与追踪，并保留 30 天审计事件。

## recovery
Atlas 每六小时生成快照，官方目标为 RPO 六小时、RTO 两小时。

## cost
Atlas 按工作节点小时和对象存储用量计费，控制面不单独收费。

## compliance
Atlas 的合成资料声明其通过 ISO 27001 和 SOC 2 Type II 审核。
