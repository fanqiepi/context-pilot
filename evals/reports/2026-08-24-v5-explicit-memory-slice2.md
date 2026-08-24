# V5 显式记忆 Slice 2 验收记录

## 结论

V5 Slice 2“隐式用户与记忆持久化”已完成。生产代码已建立服务端派生的 `CurrentSubjectProvider`、固定 `LOCAL_OWNER` 种子、回答偏好聚合、不可变 revision 和消息记忆关联表；当前没有登录、认证、多用户隔离、管理 API 或 Prompt 注入。

## 实现证据

- Flyway `V16__create_explicit_user_memory.sql` 创建 `app_user`、`user_memory`、`user_memory_revision` 和 `message_memory_context`；
- `LOCAL_OWNER` 使用稳定 UUID `00000000-0000-0000-0000-000000000001`，客户端没有所有者输入入口；
- 当前 revision 使用可延迟复合外键，创建事务提交时必须存在对应不可变 revision；
- 聚合创建锁定当前 `app_user` 行，并在所有查询和计数条件中包含服务端解析的 `owner_user_id`；
- 内容执行 NFKC、首尾清理、连续空白折叠、1-300 字符校验和 SHA-256 精确哈希；
- 数据结构冻结 100 条未删除、20 条活动记忆的应用层上限，并为后续管理和注入保留受限索引。

## 验证

2026-08-24 使用 JDK 21 和本机 Docker 执行：

```text
.\mvnw.cmd '-Dtest=LocalSingleUserSubjectProviderTests,UserMemoryContentTests,UserMemoryPersistenceTests,DatabaseMigrationTests' test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

实际 PostgreSQL 17 / pgvector 容器完成 V1-V16 全量迁移。持久化测试验证稳定主体、聚合与 revision 创建、规范化精确去重，以及使用随机其他 owner 查询时不返回记忆或正文。

## 未包含范围

- `CREATE_USER_MEMORY` 聊天路由、Action 参数、提案和确认执行属于 Slice 3；
- 管理 API、纠正/状态/过期/逻辑删除属于 Slice 4；
- Prompt 注入、SSE 和历史透明度属于 Slices 5-6；
- 当前主体不提供身份认证，部署仍只适用于本机或可信网络。
