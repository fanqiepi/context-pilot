# ContextPilot repository guidance

## Required session startup

- At the beginning of every new conversation, read `docs/development/project-context.md` before inspecting, planning, or changing the repository.
- Use that document as the versioned project overview, then verify any changeable details against the current code and configuration.

## Project context

- ContextPilot is a learning-oriented, controllable, and evaluable personal knowledge-work assistant built with Spring AI. Its user-facing product carrier is a single-user knowledge workspace; its engineering purpose is to explore modern agent capabilities through coherent, testable scenarios rather than by accumulating frameworks.
- V1, the functional RAG baseline, is complete. It covers document ingestion, retrieval-augmented generation, citations, streaming responses, conversation history, call records, positive-only helpful feedback, fixed capability routing, and embedding-index governance.
- The roadmap advances by major version. Only the current version is specified in implementation detail; the next version defines a scenario and boundary; later versions are directional candidates and are not implementation authorization.
- V2 is the current authorized version. It adds the persisted, confirmable `CREATE_KNOWLEDGE_BASE` business action while preserving the modular monolith and existing RAG behavior.
- V3 is only a planned outline for a knowledge-base maintenance assistant that can inspect health and, after confirmation, retry document processing or request reindexing. Do not implement V3 until V2 is complete and the V3 contracts are explicitly approved.
- V4 and later may explore bounded planning, explicit memory, external-tool interoperability such as MCP, and multi-agent patterns, but none of these are currently authorized. Do not introduce agents, LangGraph or other workflow engines, MCP integration, microservices, message queues, multi-tenancy, plugin systems, dynamic tools, or model-generated SQL under the current scope.

## Current authorized improvement scope

- Treat current work as V2, not as unfinished MVP delivery and not as permission to implement later roadmap versions. Preserve the completed V1 baseline while adding one controlled action.

- Route each chat request to one of three explicit, versioned capabilities: `SIMPLE_CHAT`, `KNOWLEDGE_QA`, or `BUSINESS_ACTION`.
- Keep `SIMPLE_CHAT` narrow: identity, greeting, capability description, thanks, and farewell continue to use deterministic replies. Do not silently turn it into unrestricted open-domain model answering.
- Preserve the current grounded RAG path for `KNOWLEDGE_QA`, including knowledge-base isolation, evidence validation, citations, refusal behavior, SSE, persistence, and trace IDs.
- The first authorized business action is `CREATE_KNOWLEDGE_BASE`, exposed through a statically registered, strongly typed application action that delegates to the existing `KnowledgeBaseService`.
- A business request must produce a validated, persisted action proposal first. The real action must not execute until the user explicitly confirms it through a separate request.
- Persist an auditable action state machine such as `PENDING_CONFIRMATION -> EXECUTING -> SUCCEEDED/FAILED`, with `REJECTED` and `EXPIRED` terminal alternatives. Confirmation must be atomic and idempotent so repeated requests cannot execute the action twice.
- Route matching should start with deterministic rules and default safely to `KNOWLEDGE_QA`; do not add an extra model classification call until measured ambiguity justifies it.
- Keep the implementation specific to the approved capability and action. A generic Skill registry, generic `ToolExecutionGateway`, arbitrary reflection, arbitrary HTTP/file/shell access, and autonomous tool loops remain out of scope.

## Roadmap governance

- Use `docs/requirements/product-requirements.md` as the single detailed roadmap. Avoid parallel phase systems such as P/N/I/A-D numbering for future work.
- Describe V1 as a completed baseline, V2 in full detail, V3 as a bounded scenario outline, and V4+ only as goals and entry conditions.
- A roadmap candidate is not authorization. Before starting a later major version, update this file and the relevant requirements, architecture, data, API, and evaluation documents with an explicitly approved scope.
- Plan capabilities from user scenarios first. Frameworks such as LangGraph, MCP, workflow runtimes, or multi-agent libraries are implementation candidates, not roadmap goals by themselves.
- Keep the three top-level routing classes stable for the current scope: `SIMPLE_CHAT`, `KNOWLEDGE_QA`, and `BUSINESS_ACTION`. Future knowledge tasks or business actions should normally be modeled beneath these classes unless a later approved design demonstrates the need for another top-level route.
- Evaluation, traceability, human confirmation, bounded execution, and failure semantics are cross-version quality gates rather than optional cleanup work.

## Sources of truth

- This committed `AGENTS.md` defines working and safety instructions for repository assistants.
- `docs/development/project-context.md` is the committed overview that a new conversation must read first.
- Product requirements, architecture documents, application configuration, and Flyway migrations remain the detailed sources of truth for their respective areas.
- Local planning notes may provide additional background, but they are intentionally not tracked and must not be required to build, test, or understand the repository.
- When implementation and documentation disagree, do not silently choose one. Report the conflict and update the relevant document after the decision is confirmed.

## Approved architecture

- Backend: Java 21, Spring Boot 4.1.x, Spring AI 2.0.x, Maven.
- Frontend: Vue 3, TypeScript, Vite, Element Plus.
- Chat model: DeepSeek API through Spring AI's native DeepSeek integration; use `deepseek-v4-flash` as the default model.
- Embedding model: DashScope `qwen3.7-text-embedding` through its OpenAI-compatible endpoint, fixed at 1024 dimensions for the current index profile.
- Storage: PostgreSQL with pgvector; do not add a separate vector database under the current authorized scope.
- Persistence: use MyBatis-Plus for business tables and Spring AI `PgVectorStore` for vector operations.
- Documents: support TXT, Markdown, and text-based PDF through Spring AI readers; scanned-PDF OCR is outside the current authorized scope.
- Files: store uploads behind a `StorageService` in the gitignored `data/uploads/` directory.
- Background processing: use a bounded application `TaskExecutor`; do not add a message queue under the current authorized scope.
- Architecture: frontend/backend separation with a modular monolith backend.
- Streaming: SSE. Secrets and model credentials remain backend-only environment variables.
- Current access model: single user with no login or role system.
- V2 orchestration: explicit Java application services and persisted state transitions; no LangGraph dependency or external workflow runtime.

## Working agreements

- Use `develop` as the integration and day-to-day development branch. Create feature branches from `develop` when isolation is useful.
- Do not commit directly to `main`. Changes reach `main` only through a reviewed pull request or an explicitly reviewed merge from `develop`.
- Use English Conventional Commits type prefixes such as `fix`, `feat`, `docs`, and `chore`; write the title description after the prefix and the commit body in Chinese.
- Keep `main` releasable. Before merging `develop` into `main`, run the relevant backend and frontend verification commands and review the complete diff.
- Organize backend code by business capability, not as one repository-wide controller/service/mapper hierarchy.
- Keep model-provider details behind Spring AI or a small application-level abstraction.
- Use package root `io.github.fanqiepi.contextpilot` and database name `context_pilot`.
- Business tables use MyBatis-Plus logical deletion with `deleted = 0` for active rows and `deleted = 1` for deleted rows. Ordinary CRUD must not physically delete business records or uploaded files; any future purge must be an explicit maintenance workflow.
- Treat prompts, evaluation cases, database migrations, and architecture decisions as versioned project assets.
- Prefer the smallest change that completes the current requirement. Explain any new production dependency before adding it.
- Preserve user changes and avoid unrelated rewrites.
- Documentation content may be Chinese; code identifiers, package names, database identifiers, and file paths should use clear English names.

## AI and data safety

- Never commit `.env`, API keys, database passwords, private documents, or sensitive evaluation data.
- Do not log complete secrets, full private document contents, or unredacted model requests by default.
- Treat retrieved documents and model output as untrusted data. Do not convert them directly into executable commands or unrestricted tool calls.
- Business actions must use a static allowlist, strong request types, server-side validation, least privilege, bounded execution, trace IDs, and auditable records.
- Never execute a side-effecting action before an active persisted proposal has been explicitly confirmed. Model output may suggest an action but cannot confirm it or bypass the application service that owns the business operation.

## Verification

- Run the smallest relevant tests after a change and report what was or was not verified.
- On the local Windows development machine, JDK 21 is installed at `F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9`; set `JAVA_HOME` to this directory before running backend commands when the active JDK is not Java 21.
- Backend test: from `backend/`, run `.\mvnw.cmd test` on Windows or `./mvnw test` on Unix-like systems.
- Backend build: from `backend/`, run `.\mvnw.cmd package` on Windows or `./mvnw package` on Unix-like systems.
- Backend local start: from `backend/`, run `.\mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` on Unix-like systems.
- Frontend dependency install: from `frontend/`, run `npm ci`.
- Frontend type check: from `frontend/`, run `npm run typecheck`.
- Frontend build: from `frontend/`, run `npm run build`.
- Frontend local start: from `frontend/`, run `npm run dev`.
- Local PostgreSQL and pgvector: from the repository root, run `docker compose up -d` and inspect it with `docker compose ps`.
- When behavior, architecture, prompts, or evaluation criteria change, update the corresponding project documentation in the same change.
