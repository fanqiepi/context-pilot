# 本地开发

## 前置条件

- JDK 21
- Node.js 22.12 或更高版本
- Docker Desktop 和 Docker Compose

后端 Maven 构建会校验 Java 版本。执行 `java -version` 和 `node --version` 确认当前终端环境。

## 数据库

从仓库根目录执行：

```powershell
docker compose up -d
docker compose ps
```

本地数据库地址为 `127.0.0.1:15432`，数据库名和用户名均为 `context_pilot`。Docker 初始化脚本启用 pgvector，Flyway 在后端启动时创建和校验应用表。

Docker 初始化脚本只在数据卷首次创建时执行。使用非 Docker PostgreSQL 时，数据库管理员需要先执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

## 上传目录

默认配置假设从 `backend/` 启动后端，并将 `STORAGE_ROOT` 解析为 `../data/uploads`。从 IDEA 或其他工作目录启动时，应显式设置为仓库根目录 `data/uploads/` 的绝对路径。

运行数据不得提交 Git。后续文件读写必须通过 `StorageService`，不能由 controller 或业务服务直接拼接路径。

文档实际存储键由后端生成，结构为 `knowledge-bases/{knowledgeBaseId}/documents/{documentId}/source.{ext}`；原始文件名不会作为磁盘路径。默认单文件限制为 20 MiB，可通过 `DOCUMENT_MAX_FILE_SIZE` 调整；multipart 请求限制通过 `DOCUMENT_MAX_REQUEST_SIZE` 单独配置，并应略大于单文件限制。

上传支持 UTF-8 编码的 TXT、Markdown 和带有效文件头的 PDF。扫描版 PDF 是否可处理将在后续解析阶段判断。

## 启动和验证

后端：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
cd frontend
npm ci
npm run typecheck
npm run dev
```

模型集成默认关闭。启用前需要在后端进程中设置 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`SPRING_AI_CHAT_MODEL=deepseek` 和 `SPRING_AI_EMBEDDING_MODEL=openai`。
