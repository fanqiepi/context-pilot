# V5 显式记忆 Slice 3 验收记录

## 结论

V5 Slice 3“创建偏好提案”已完成。用户只有使用冻结的显式句式时才会进入 `BUSINESS_ACTION/v2/CREATE_USER_MEMORY`；系统先持久化不可变提案，独立确认后才为当前服务端主体创建 `GLOBAL/RESPONSE_PREFERENCE`。普通聊天、文档和模型输出没有自动记忆入口。

## 实现证据

- 确定性解析只接受“请记住我的偏好：...”和“记住回答偏好：...”，执行 NFKC、空白折叠、1-300 字符校验，并拒绝附加执行指令；
- `CreateUserMemoryActionParameters` 固定 `RESPONSE_PREFERENCE/GLOBAL`，Action 参数 JSON 不允许额外字段；
- Flyway V17 静态扩展 `action_request`、聊天路由原因和 `result_resource_id -> user_memory` 外键，不增加动态工具；
- 确认执行时通过 `CurrentSubjectProvider` 重新解析 `LOCAL_OWNER`，并使用 Action 中可信的会话/用户消息作为 revision 来源；
- 未确认、拒绝和过期提案零记忆副作用；重复/并发确认只执行一次；不同提案的规范化精确重复返回同一活动记忆；
- 聊天操作卡片支持展示类型、内容、过期时间和确认结果。

## 验证

2026-08-24 使用 JDK 21、PostgreSQL 17 / pgvector Testcontainers 和前端生产构建执行：

```text
.\mvnw.cmd test
Tests run: 356, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS

.\mvnw.cmd -Pv5-evaluation test
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

npm run typecheck
success

npm run build
success
```

全量后端测试包含 V1-V17 迁移、V2/V3 Action 回归、RAG/健康检查/V4 研究基线以及 Slice 3 的真实 PostgreSQL 生命周期。3 项跳过来自既有条件化测试，不是失败。

## 未包含范围

- 管理 API、纠正、启用/禁用、过期调整和逻辑删除属于 Slice 4；
- 普通 RAG/V4 Prompt 注入、单次忽略、SSE 和历史透明度属于 Slices 5-6；
- 当前仍没有登录、认证或多用户隔离，只适用于本机或可信网络。
