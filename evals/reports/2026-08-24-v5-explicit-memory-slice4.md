# V5 显式记忆 Slice 4 管理闭环报告

> 日期：2026-08-24
> 结论：Slice 4 已完成并通过本地工程、真实 PostgreSQL 生命周期与页面验收；下一批为 Slices 5+6。

## 1. 本次交付

- 新增 `GET /api/user-memories`，只查询服务端 `CurrentSubjectProvider` 确定的当前主体，支持状态与过期过滤，并按更新时间和 ID 稳定倒序；
- 新增详情接口，返回当前正文、来源、状态、聚合版本和不包含历史正文的 revision 摘要；
- 新增强类型 `PATCH`，要求正数 `version`，支持内容纠正、启用/禁用和过期时间调整；每次实际变更新增不可变 `MANAGEMENT` revision，并以所有者和版本条件原子更新聚合；
- 新增幂等逻辑删除；其他主体下的相同 ID 与不存在资源统一返回 `USER_MEMORY_NOT_FOUND`；
- 重新启用或纠正为活动偏好时锁定当前 `app_user`，复核活动上限与精确内容哈希，固定返回 `USER_MEMORY_DUPLICATE`、`USER_MEMORY_LIMIT_REACHED` 或 `USER_MEMORY_VERSION_CONFLICT`；
- 新增 `/memories` 页面，展示内容、状态、来源、revision、更新时间和过期时间；纠正、启停和删除均要求明确确认，页面不展示或提交所有者、内容哈希与内部 Prompt。

## 2. 独立验证

### 后端控制器合同

```powershell
cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd '-Dtest=UserMemoryControllerTests' test
```

结果：7 项通过，0 失败、0 错误、0 跳过，包含客户端提交 `ownerUserId` 时的拒绝案例。

### PostgreSQL 17 / pgvector 生命周期

```powershell
.\mvnw.cmd '-Dtest=UserMemoryManagementPersistenceTests,UserMemoryPersistenceTests,CreateUserMemoryActionPersistenceTests' test
```

结果：8 项通过，0 失败、0 错误、0 跳过。覆盖创建基线回归、列表/详情、不可变 revision、内容纠正、状态禁用/启用、重复启用冲突、旧版本冲突、过期过滤以及重复逻辑删除。

### 前端工程门禁

```powershell
cd frontend
npm run typecheck
npm run build
```

结果：TypeScript 类型检查与 Vite 生产构建均通过；仅保留既有大 chunk 警告，没有构建错误。

### 全量回归与冻结评估

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd -Pv5-evaluation test
```

结果：后端全量 366 项测试中 363 项通过、0 失败、0 错误、3 项按既有环境条件跳过；`v5-explicit-memory-v1` 冻结评估 30/30 通过。

### 页面验收

- 使用当前 Slice 4 后端和本地 Vite 页面检查 `/memories`；
- 桌面与 390 × 844 窄屏均能展示导航、单用户限制、统计、筛选和空状态；
- 窄屏 `document.body.scrollWidth = 375`、`document.documentElement.scrollWidth = 375`，小于 390px viewport，无水平溢出；
- 浏览器控制台无错误或警告，API 加载不出现错误提示。

## 3. 安全与边界复核

- 没有增加登录、多用户、客户端主体字段、自动记忆或模型触发管理操作；
- 管理接口只允许固定的 `RESPONSE_PREFERENCE/GLOBAL` 聚合，历史摘要不重新暴露旧正文；
- 本次没有接入普通 RAG 或 V4 Synthesizer Prompt，也没有实现 `memory_context` SSE 或历史消息关联；这些仍属于 Slices 5+6；
- 逻辑删除保留数据库审计记录，但删除后普通查询与详情不再返回该资源。

## 4. 后续入口

下一开发批次按已批准顺序实现 Slice 5 后再实现 Slice 6：固定预算受限注入、`message_memory_context`、单次忽略、SSE 事件、历史恢复和读取失败安全降级。不得因同一批次交付而合并切片提交或跳过独立验证。
