# fixture-2 发布记录（自编合成，非 Spring AI 真实发布声明）

## tools
合成新版把工具执行交还应用层，必须显式声明每个请求可用工具；工具结果必须带原始调用 ID。
## timeout
合成新版要求每任务最多 5 次请求，整体截止 150 秒；单调用最长 45 秒。
## api
合成新版接口为 POST /v2/responses，发送 question 字段，接收 answer 字段。
## configuration
合成新版采用 YAML，模型名字段为 model.name。
## embedding
合成新版默认 embed-large，1024 维；旧索引不得直接复用，必须重建并更换 profile。
## retention
合成新版禁止日志保存完整 Prompt，调用元数据保留 21 天。
## queue
合成新版队列必须有界，拒绝时返回错误，不允许请求线程代为执行。
