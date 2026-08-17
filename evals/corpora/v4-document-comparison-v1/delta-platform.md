# Delta Platform（合成评估资料）

## deployment
Delta 使用虚拟机镜像部署，要求独立 PostgreSQL 和对象存储服务。

## limits
Delta 单实例最多连接 200 个数据源，每个导入任务上限为 10 GB。

## security
Delta 集成企业 LDAP，并使用客户管理的密钥加密敏感字段。

## observability
Delta 提供 Grafana 仪表盘、告警 Webhook 和七天查询日志。

## recovery
Delta 支持跨可用区热备，合成目标为 RPO 十五分钟、RTO 三十分钟。

## cost
Delta 按虚拟机核心数订阅，外部数据库与对象存储成本由用户承担。

## airgap
Delta 提供隔离网安装包和离线补丁清单，不要求运行时访问公网。
