# ContextPilot repository guidance

## Project context

- ContextPilot is a learning-oriented knowledge base and task assistant built with Spring AI.
- The MVP focuses on document ingestion, retrieval-augmented generation, citations, streaming responses, conversation history, call records, and feedback.
- Keep the MVP small. Do not introduce agents, workflow engines, microservices, message queues, multi-tenancy, or plugin systems unless a later decision explicitly requires them.

## Sources of truth

- This `AGENTS.md` defines the committed MVP boundary, safety requirements, approved technology stack, and repository layout.
- Local planning notes may provide additional background, but they are intentionally not tracked and must not be required to build, test, or understand the repository.
- When implementation and documentation disagree, do not silently choose one. Report the conflict and update the relevant document after the decision is confirmed.

## Approved architecture

- Backend: Java 21, Spring Boot 4.1.x, Spring AI 2.0.x, Maven.
- Frontend: Vue 3, TypeScript, Vite, Element Plus.
- Chat model: DeepSeek API through Spring AI's native DeepSeek integration; use `deepseek-v4-flash` as the default model.
- Embedding model: DashScope `text-embedding-v4` through its OpenAI-compatible endpoint, fixed at 1024 dimensions for the MVP.
- Storage: PostgreSQL with pgvector; do not add a separate vector database for the MVP.
- Persistence: use MyBatis-Plus for business tables and Spring AI `PgVectorStore` for vector operations.
- Documents: support TXT, Markdown, and text-based PDF through Spring AI readers; scanned-PDF OCR is out of scope.
- Files: store uploads behind a `StorageService` in the gitignored `data/uploads/` directory.
- Background processing: use a bounded application `TaskExecutor`; do not add a message queue for the MVP.
- Architecture: frontend/backend separation with a modular monolith backend.
- Streaming: SSE. Secrets and model credentials remain backend-only environment variables.
- MVP access model: single user with no login or role system.

## Working agreements

- Use `develop` as the integration and day-to-day development branch. Create feature branches from `develop` when isolation is useful.
- Do not commit directly to `main`. Changes reach `main` only through a reviewed pull request or an explicitly reviewed merge from `develop`.
- Use English Conventional Commits type prefixes such as `fix`, `feat`, `docs`, and `chore`; write the title description after the prefix and the commit body in Chinese.
- Keep `main` releasable. Before merging `develop` into `main`, run the relevant backend and frontend verification commands and review the complete diff.
- Organize backend code by business capability, not as one repository-wide controller/service/mapper hierarchy.
- Keep model-provider details behind Spring AI or a small application-level abstraction.
- Use package root `io.github.fanqiepi.contextpilot` and database name `context_pilot`.
- Treat prompts, evaluation cases, database migrations, and architecture decisions as versioned project assets.
- Prefer the smallest change that completes the current requirement. Explain any new production dependency before adding it.
- Preserve user changes and avoid unrelated rewrites.
- Documentation content may be Chinese; code identifiers, package names, database identifiers, and file paths should use clear English names.

## AI and data safety

- Never commit `.env`, API keys, database passwords, private documents, or sensitive evaluation data.
- Do not log complete secrets, full private document contents, or unredacted model requests by default.
- Treat retrieved documents and model output as untrusted data. Do not convert them directly into executable commands or unrestricted tool calls.
- Any future tool calling must use an allowlist, validated parameters, least privilege, and auditable records.

## Verification

- Run the smallest relevant tests after a change and report what was or was not verified.
- Backend test: from `backend/`, run `.\mvnw.cmd test` on Windows or `./mvnw test` on Unix-like systems.
- Backend build: from `backend/`, run `.\mvnw.cmd package` on Windows or `./mvnw package` on Unix-like systems.
- Backend local start: from `backend/`, run `.\mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` on Unix-like systems.
- Frontend dependency install: from `frontend/`, run `npm ci`.
- Frontend type check: from `frontend/`, run `npm run typecheck`.
- Frontend build: from `frontend/`, run `npm run build`.
- Frontend local start: from `frontend/`, run `npm run dev`.
- Local PostgreSQL and pgvector: from the repository root, run `docker compose up -d` and inspect it with `docker compose ps`.
- When behavior, architecture, prompts, or evaluation criteria change, update the corresponding project documentation in the same change.
