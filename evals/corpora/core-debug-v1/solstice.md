# Solstice（仅调试用公开合成资料）

## deployment
Solstice 使用无服务器函数部署，发布前运行数据格式检查。
## capacity
Solstice 单次任务最长 8 分钟，队列最多 30 个任务。
## recovery
Solstice 每日导出一次任务索引，没有公开恢复耗时。
