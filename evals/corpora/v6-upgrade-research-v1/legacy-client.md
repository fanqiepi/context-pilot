# 旧版接入方案（公开合成，仅用于评估）

## tools
客户端把所有工具注册为全局工具，框架自动循环执行；应用只记录最终答案。
## timeout
所有外部调用使用 120 秒超时，没有任务总截止时间，也没有调用次数计数。
## api
旧接口为 POST /v1/answer，发送 input 字段；接收 text 字段作为最终正文。
## configuration
旧配置为平铺的 JSON，模型名存放在 modelName 字段。
