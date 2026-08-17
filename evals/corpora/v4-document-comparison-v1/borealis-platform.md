# Borealis Platform（合成评估资料）

## deployment
Borealis 以单机二进制或三节点集群部署，配置由本地 YAML 文件管理。

## limits
Borealis 每个集群支持 40 个命名空间，批处理并发数默认限制为 64。

## security
Borealis 支持双向 TLS、离线密钥轮换和命名空间级访问策略。

## observability
Borealis 暴露 Prometheus 指标和结构化日志，但不内置分布式追踪。

## recovery
Borealis 每日生成增量备份，合成目标为 RPO 二十四小时、RTO 四小时。

## cost
Borealis 使用固定年度节点许可证，不按请求量收取费用。

## offline_mode
Borealis 可以在断网环境运行，许可证和镜像可通过离线介质导入。
