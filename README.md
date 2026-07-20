# ContextPilot

ContextPilot 是一个基于 Spring AI 的学习型知识库与任务助手。MVP 采用前后端分离的模块化单体架构，提供文档摄取、检索增强生成、引用、流式回答、会话历史、调用记录和反馈能力。

## 环境要求

- Java 21
- Maven 3.6.3+（仓库已包含 Maven Wrapper）
- Node.js 22.12+
- Docker Desktop / Docker Compose

如果 Windows 同时安装了多个 JDK，可在当前 PowerShell 会话中切换后再运行 Maven Wrapper：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 本地启动

1. 执行 `java -version`，确认当前终端使用的是 JDK 21；Maven 构建会拒绝其他 Java 版本。
2. 复制 `.env.example` 为 `.env`，供 Docker Compose 读取本地数据库密码。不要提交 `.env`。
3. 启动 PostgreSQL 与 pgvector：`docker compose up -d`。
4. 在 IDEA 运行配置或当前终端中设置模型相关环境变量；Spring Boot 不会自动读取根目录 `.env`。从其他工作目录启动后端时，还需要将 `STORAGE_ROOT` 设置为仓库根目录下 `data/uploads/` 的绝对路径。
5. 启动后端：进入 `backend`，执行 `./mvnw spring-boot:run`；Windows PowerShell 使用 `.\mvnw.cmd spring-boot:run`。
6. 启动前端：进入 `frontend`，执行 `npm ci` 后运行 `npm run dev`。

本机 PostgreSQL 映射端口为 `15432`，默认数据库名和用户均为 `context_pilot`。后端默认不启用模型调用；配置 `SPRING_AI_CHAT_MODEL=deepseek`、`SPRING_AI_EMBEDDING_MODEL=openai` 及对应 API Key 后再启用。

## 数据流向

上传文件保存在被 Git 忽略的 `data/uploads/`。文档分段发送到阿里云百炼 `text-embedding-v4` 生成 1024 维向量，检索命中的分段发送到 DeepSeek 生成回答；模型密钥只允许保存在后端环境变量中。

正式的需求、架构、数据模型、接口、评估和本地开发约定位于 [`docs/`](docs/)；数据库结构以 Flyway 迁移脚本为准。
