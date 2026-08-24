# V5 显式记忆 Slice 6 透明闭环报告

> 日期：2026-08-24
> 结论：Slice 6 已完成并通过 SSE、历史恢复、安全降级、前端构建、全量回归与页面检查；V5 第一阶段只剩 Slice 7 最终验收。

## 1. 本次交付

- 普通 RAG 在 `message -> route` 后、首个 `delta` 前按需发送一次 `memory_context`；V4 研究在计划与步骤之后、最终 `delta` 前发送，偏好不影响计划或检索；
- 非流式回答、流式回答和历史助手消息统一返回可空 `memoryContext`，包含受限摘要、memory ID、revision、数量、字符数与准确截断状态；
- 历史回显按 `message_memory_context` 的已用 revision 恢复，不按当前记忆重算；后续纠正不改写旧消息，逻辑删除后保留 ID/revision 但隐藏正文并显示“该偏好已删除”；
- 无活动偏好、`IGNORE` 或读取异常时不发送事件；读取基础设施缺失时应用上下文仍可启动，实际回答安全降级到无记忆路径；
- `/chat` 输入区新增只作用于下一次发送的“本次忽略长期记忆”开关，发送结束后自动复位；回答增加可展开“本次使用的偏好”卡片；
- Flyway V19 为历史透明度持久化字符数和截断状态，避免刷新后从条目数猜测。

## 2. 独立验证

### 针对性后端门禁

```powershell
cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd '-Dtest=ChatPromptMemoryTests,ChatStreamingServiceTests,ResearchExecutorServiceTests,MemoryContextPersistenceTests,MemoryContextServiceTests,ConversationHistoryServiceTests' test
```

结果：23 项通过，0 失败、0 错误、0 跳过。覆盖普通 SSE 顺序、研究最终综合、预算摘要、精确 revision 历史恢复、删除正文隐藏和读取异常降级。

### 全量后端回归

```powershell
.\mvnw.cmd test
```

结果：372 项测试中 369 项通过、0 失败、0 错误、3 项按既有环境条件跳过；V1-V4 基线、V5 Slices 1-6 与无数据源应用上下文均通过。

### 前端与页面

```powershell
cd frontend
npm run typecheck
npm run build
```

结果：类型检查和生产构建通过，仅保留既有大 chunk 警告。使用本地页面验证 `/chat`：

- 1440 × 900 桌面视口显示单次忽略控件且控制台无错误或警告；
- 390 × 844 窄屏下控件、文本框和发送按钮分行排列，无相互遮挡；
- 页面存在两个独立 switch（多文档比对与单次忽略），记忆标签可见；临时 viewport 已复位。

## 3. 安全与边界复核

- SSE 和历史不返回用户 ID、完整 Prompt、内容哈希或内部错误；
- 删除后历史不会借旧 revision 重新暴露正文；
- `IGNORE` 只影响单次请求，不禁用或删除持久化偏好；
- 页面没有创建新的模型或管理副作用入口；当前仍是本机/可信网络单用户应用，没有认证或多用户隔离。

## 4. Slice 7 入口

最终验收需独立复跑 `v5-explicit-memory-v1`、真实 PostgreSQL 生命周期和全量回归，执行普通 RAG 与 V4 各至少 4 个真实 DeepSeek 代表案例，量化偏好遵循、当前请求覆盖、grounding/引用/计划隔离，并完成含实际 `memory_context` 卡片的最终页面与版本报告。通过前不得把 V5 第一阶段标记完成。
