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

上传支持 UTF-8 编码的 TXT、Markdown 和带有效文件头的 PDF。文档解析使用 Spring AI 的文本、Markdown 和按页 PDF reader；不含可提取文本的扫描版 PDF 会被拒绝，当前范围不提供 OCR。

真实 Embedding 默认索引身份为 `dashscope_qwen3_7_1024_v1`，`offline` Profile 使用隔离的 `offline_deterministic_1024_v1`。不要让两个环境复用同一个 `EMBEDDING_PROFILE_ID`。模型、维度或影响向量语义的预处理变化后，应设置新的 profile ID，启动应用完成迁移，再在资料库页面对旧文档执行“重建索引”。

文本切分默认每块最多 1200 个字符并重叠 150 个字符，可分别通过 `DOCUMENT_CHUNK_MAX_CHARACTERS` 和 `DOCUMENT_CHUNK_OVERLAP_CHARACTERS` 覆盖。重叠值必须大于等于 0 且小于块大小。自动处理使用有界线程池，默认 1 个核心线程、最多 2 个线程、队列容量 50；每个文档默认最多进行 3 次处理尝试。

## 启动和验证

后端：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

不配置任何模型 API Key 时，可使用离线 Profile 验证“上传、异步解析、切分、pgvector 入库、检索、删除清理”完整流程：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'offline'
.\mvnw.cmd spring-boot:run
```

离线 Profile 使用确定性的 1024 维测试向量，只适合功能测试，不代表真实语义检索效果。默认 Profile 不自动处理新上传文档，文档会保持 `PENDING`。

前端：

```powershell
cd frontend
npm ci
npm run typecheck
npm run dev
```

前端开发代理默认指向 `http://127.0.0.1:18080`；后端使用其他端口时，可通过 `VITE_API_PROXY_TARGET` 覆盖代理目标。

真实模型集成默认关闭。后续启用真实服务时，才需要在后端进程中设置 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 和对应 Spring AI 模型配置。

后端默认监听 `18080`，Knife4j 接口文档位于 `http://localhost:18080/doc.html`，原始 OpenAPI JSON 位于 `http://localhost:18080/v3/api-docs`。如需使用其他端口，可设置 `SERVER_PORT`；修改后还应同步调整前端开发代理目标。
