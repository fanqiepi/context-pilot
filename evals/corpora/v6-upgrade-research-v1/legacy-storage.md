# 旧版存储方案（公开合成，仅用于评估）

## embedding
旧索引使用 embed-small，768 维，profile 为 legacy-small-768；分块大小 900 字。
## retention
调用日志保留 7 天，保存完整 Prompt；知识库索引与模型配置没有绑定。
## queue
任务队列无上限，提交失败在用户请求线程直接执行任务。
