# V5 显式记忆 Slice 5 受限注入报告

> 日期：2026-08-24
> 结论：Slice 5 已完成并通过固定预算、Prompt 隔离、PostgreSQL 关联和 V4 综合链路验证；随后进入 Slice 6 透明闭环。

## 1. 本次交付

- 普通 RAG 和 V4 `DOCUMENT_COMPARISON` 最终 Synthesizer 只读取当前 `LOCAL_OWNER` 下 `ACTIVE`、未删除、未过期的 `GLOBAL/RESPONSE_PREFERENCE`；
- 选择顺序固定为 `updated_at DESC, id DESC`，单次最多 10 条、合计最多 2000 字符，超限停止并记录 `truncated`；
- 偏好使用独立 `<user_preferences>` 数据块，标签边界字符会转义，当前明确问题始终位于偏好之后；系统 Prompt 明确偏好只能影响表达，不能改变路由、证据、引用、拒答、研究计划、预算或工具；
- 只有实际进入 Prompt 的 memory revision 才写入 `message_memory_context`，同时保存稳定顺序、字符数与截断状态；
- 聊天请求新增 `memoryMode=USE|IGNORE`，默认 `USE`；`IGNORE` 不读取、不关联，也不修改持久化记忆；
- 研究运行通过 Flyway V18 持久化模式并纳入幂等指纹，后台只使用冻结值；普通 RAG Prompt 升级为 v2，研究综合 Prompt 升级为 v3；
- 记忆读取发生在模型调用审计记录创建后；依赖失败只生成脱敏告警并安全降级为空上下文。

## 2. 独立验证

```powershell
cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd '-Dtest=ChatPromptMemoryTests,MemoryContextServiceTests,MemoryContextPersistenceTests,ResearchExecutorServiceTests' test
```

相关用例验证：XML 边界转义、当前请求优先、`IGNORE` 零读取、读取异常降级、10 条/2000 字符双预算、实际 revision 关联，以及偏好只进入 V4 最终综合而不进入研究计划与检索步骤。Flyway V1-V19 在 PostgreSQL 17 / pgvector Testcontainers 空库上成功执行。

## 3. 安全与边界复核

- 没有自动保存、事实型记忆、向量记忆、客户端所有者或客户端记忆列表；
- 简单回复、拒答、Action、健康报告和研究计划不加载偏好；无可用证据时不会因记忆触发模型调用；
- 日志只记录 trace、消息、数量、字符、截断和 memory ID/revision，不记录完整正文；
- 偏好读取失败不会降低既有 RAG/V4 可用性，关联写入失败也不会留下未审计的模型调用。

## 4. 后续入口

Slice 6 在不改变本切片预算和 Prompt 边界的前提下，闭合 `memory_context` SSE、历史恢复、删除后正文隐藏、页面透明卡片和桌面/窄屏交互。
