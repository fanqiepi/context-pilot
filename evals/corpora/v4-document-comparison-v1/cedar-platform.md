# Cedar Platform（合成评估资料）

## deployment
Cedar 由托管控制面协调边缘代理，代理通过签名安装包部署。

## limits
Cedar 每个租户最多注册 500 个边缘代理，单次任务负载上限为 2 GB。

## security
Cedar 为每个代理签发设备证书，并通过策略签名验证任务来源。

## observability
Cedar 汇总边缘心跳、任务延迟和错误率，指标保留 14 天。

## recovery
Cedar 控制面每小时复制状态，边缘代理断线后可从检查点恢复。

## cost
Cedar 按活跃代理数量分档计费，数据回传流量另行计量。

## data_residency
Cedar 允许为元数据选择中国、新加坡或德国驻留区域。
