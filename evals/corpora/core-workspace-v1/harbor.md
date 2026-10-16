# Harbor（公开合成评估资料，无真实产品含义）

## deployment
Harbor 使用容器单机部署，升级时需要暂停定时任务；配置文件格式为 YAML。
## capacity
Harbor 每个空间最多 48 个项目，单文件上限 36 MB；接口限额每分钟 1500 次。
## security
Harbor 使用双向 TLS 和硬件密钥，证书每 60 天轮换；不支持访问令牌登录。
## recovery
Harbor 每 12 小时备份一次，RPO 为 12 小时，RTO 为 25 分钟；恢复后需人工重启定时任务。
## observation
Harbor 的指标保留 28 天，审计事件保留 40 天；告警连续失败 2 次触发。
## cost
Harbor 按节点年费计价，基础配额包含 300 GB 存储；不按请求计费。
