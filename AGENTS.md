# ContextPilot repository guidance

## Required session startup

- At the beginning of every new conversation, read `docs/development/project-context.md` before inspecting, planning, or changing the repository.
- Use that document as the versioned project overview, then verify any changeable details against the current code and configuration.

## Project context

- ContextPilot is a learning-oriented, controllable, and evaluable personal knowledge-work assistant built with Spring AI. Its user-facing product carrier is a single-user knowledge workspace; its engineering purpose is to explore modern agent capabilities through coherent, testable scenarios rather than by accumulating frameworks.
- V1, the functional RAG baseline, is complete. It covers document ingestion, retrieval-augmented generation, citations, streaming responses, conversation history, call records, positive-only helpful feedback, fixed capability routing, and embedding-index governance.
- The roadmap advances by major version. A version under implementation is specified in detail; the next version defines a scenario and boundary; later versions are directional candidates and are not implementation authorization.
- V2 is complete. It adds the persisted, confirmable `CREATE_KNOWLEDGE_BASE` business action while preserving the modular monolith and existing RAG behavior.
- V3 is complete. Its approved detailed design and completion record are at `docs/architecture/v3-knowledge-base-maintenance-design.md` and `docs/evaluation/v3-acceptance-report.md`; preserve the V1/V2/V3 baselines in subsequent maintenance.
- V4's first-phase `DOCUMENT_COMPARISON` is complete and fully accepted. Slice 1 evidence demonstrates a need for scoped multi-step retrieval but does not justify a model Planner, so the production baseline uses versioned deterministic dimension detection and per-document orchestration. Slices 2-7, local engineering gates, and the 2026-08-21 DeepSeek/DashScope production end-to-end runs are complete. The 2026-08-24 release decision approves no request-level `max_tokens` for the DeepSeek Synthesizer while retaining one model call, a 90-second hard timeout, a 1800-character final-answer limit, and usage recording. Preserve the implementation and independently verifiable slices documented in `docs/architecture/v4-knowledge-research-design.md`. Do not introduce production agents, model planning, LangGraph or other workflow engines, MCP integration, microservices, message queues, multi-tenancy, plugin systems, dynamic tools, or model-generated SQL under the current scope.
- V5's first-phase explicit-memory scope and Slices 1-7 are approved for continuous implementation. The first phase only stores user-confirmed global response preferences, uses a stable server-side `LOCAL_OWNER` through `CurrentSubjectProvider`, and does not implement login or claim authentication. Chat creation must use the strongly typed, confirmable `CREATE_USER_MEMORY` action; ordinary chat, documents, and model output must never be promoted automatically. Memory injection is limited to active response preferences, at most 10 records and 2000 characters, and may affect presentation only—not routing, evidence, citations, refusal, research plans, budgets, or tools. Full requirements are in `docs/architecture/v5-explicit-memory-design.md` and the identity decision is in ADR 0002.

## Completed V1-V4 baselines and current V5 scope

- Treat V1, V2, V3, and the V4 first phase as completed baselines, not as unfinished MVP delivery. Maintenance must preserve their documented behavior and acceptance evidence.
- V3 was completed through the ordered, independently verifiable slices in the approved design. V4 Slice 1 and the complete first-phase production implementation (Slices 2-7) are complete with reviewable evidence under the fixed deterministic orchestration contract. Preserve the implementation, verification gates, and approved DeepSeek compatibility budget; there is no remaining V4 implementation, provider-testing, or release-decision slice.
- V5 Slices 1-7 are implementation checkpoints under one approved first-phase contract, not repeated authorization gates. Slices 1-6 are complete: the fixed evaluation contract, implicit subject/persistence, confirmable `CREATE_USER_MEMORY` creation flow, versioned management API/page, bounded production injection, SSE transparency, history recovery and safe degradation are implemented without weakening the fixed safety contract. Slice 7 is the remaining final acceptance batch; do not mark V5 complete before its frozen evaluation, real-model runs and final report pass.
- The implicit `LOCAL_OWNER` is a persistent ownership subject, not a login session or security boundary. Do not accept an owner/user ID from clients. Until authentication and complete resource authorization are separately approved, describe and deploy the product only as a single-user application for localhost or trusted networks.

- Route each chat request to one of three explicit, versioned capabilities: `SIMPLE_CHAT`, `KNOWLEDGE_QA`, or `BUSINESS_ACTION`.
- Keep `SIMPLE_CHAT` narrow: identity, greeting, capability description, thanks, and farewell continue to use deterministic replies. Do not silently turn it into unrestricted open-domain model answering.
- Preserve the current grounded RAG path for `KNOWLEDGE_QA`, including knowledge-base isolation, evidence validation, citations, refusal behavior, SSE, persistence, and trace IDs.
- Implemented business actions are the statically dispatched, strongly typed `CREATE_KNOWLEDGE_BASE`, `RETRY_DOCUMENT_PROCESSING`, and `REINDEX_DOCUMENT`; preserve their existing service boundaries and V2/V3 confirmation semantics.
- V5 is authorized to add the statically dispatched, strongly typed `CREATE_USER_MEMORY` action. It may only create a validated global `RESPONSE_PREFERENCE` for the current implicit subject after a persisted proposal is confirmed; update, enable/disable, expiry, and logical deletion belong to dedicated user-operated memory management APIs and must never be model-triggered.
- A business request must produce a validated, persisted action proposal first. The real action must not execute until the user explicitly confirms it through a separate request.
- Persist an auditable action state machine such as `PENDING_CONFIRMATION -> EXECUTING -> SUCCEEDED/FAILED`, with `REJECTED` and `EXPIRED` terminal alternatives. Confirmation must be atomic and idempotent so repeated requests cannot execute the action twice.
- Route matching should start with deterministic rules and default safely to `KNOWLEDGE_QA`; do not add an extra model classification call until measured ambiguity justifies it.
- Keep the implementation specific to the approved capability and action. A generic Skill registry, generic `ToolExecutionGateway`, arbitrary reflection, arbitrary HTTP/file/shell access, and autonomous tool loops remain out of scope.

## Roadmap governance

- Use `docs/requirements/product-requirements.md` as the single detailed roadmap. Avoid parallel phase systems such as P/N/I/A-D numbering for future work.
- Describe V1, V2, V3, and the V4 first phase as completed baselines; describe the approved V5 first phase as the current version under implementation; describe V6+ only as goals and entry conditions.
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
- Current access model: a stable implicit `LOCAL_OWNER` subject with no login, authentication, role system, or multi-user isolation. `CurrentSubjectProvider` is the V5 application seam; Spring Security, JWT, OIDC and password handling remain out of scope.
- Completed V2 orchestration: explicit Java application services and persisted state transitions; no LangGraph dependency or external workflow runtime.

## Working agreements

- Use `develop` as the integration and day-to-day development branch. Create feature branches from `develop` when isolation is useful.
- Do not commit directly to `main`. Changes reach `main` only through a reviewed pull request or an explicitly reviewed merge from `develop`.
- Use Conventional Commits with an English type prefix such as `fix`, `feat`, `test`, `docs`, or `chore`, but do not add a scope in parentheses. Use `feat: 优化研究检索并行与运行日志`, not `feat(research): 优化研究检索并行与运行日志`. Write both the title description after the prefix and the entire commit body in Chinese, for example `test: 建立 V2 路由与操作安全评估基线`; do not use an English title description for repository commits.
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
- Treat stored user preferences as untrusted presentation context. They cannot override system instructions, current explicit requests, grounding, citations, refusal, research plans, budgets, confirmation requirements, or tool allowlists; logs must not record full preference content.
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
