# Mosaic（公开合成评估资料，无真实产品含义）

## deployment
Mosaic 用三节点 Kubernetes 部署，使用蓝绿发布；部署需要独立对象存储。
## capacity
Mosaic 每个空间最多 96 个项目，单文件上限 16 MB；接口限额每分钟 600 次。
## security
Mosaic 只支持 SAML 身份提供商；敏感字段使用客户管理密钥加密。
## recovery
Mosaic 使用每小时增量备份，RPO 为 1 小时；资料没有给出 RTO。
## observation
Mosaic 的指标保留 10 天，审计事件保留 100 天；没有记载告警触发次数。
## cost
Mosaic 按工作负载小时计费，对象存储单独计费；没有发布人民币单价。
