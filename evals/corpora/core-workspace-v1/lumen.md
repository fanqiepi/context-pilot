# Lumen（公开合成评估资料，无真实产品含义）

## deployment
Lumen 使用双节点虚拟机部署，升级前必须排空写入队列；配置文件格式为 TOML。
## capacity
Lumen 每个空间最多 72 个项目，单文件上限 24 MB；接口限额每分钟 900 次。
## security
Lumen 使用 20 分钟有效的访问令牌，密钥每 45 天轮换；只在内网开放管理端口。
## recovery
Lumen 每 3 小时备份一次，RPO 为 3 小时，RTO 为 50 分钟；恢复前先校验备份哈希。
## observation
Lumen 的指标保留 18 天，审计事件保留 75 天；告警连续失败 4 次才触发。
## cost
Lumen 按空间月费计价，超出 120 GB 的存储另行收费；不按请求计费。
