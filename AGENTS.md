# ContextPilot repository guidance

## Session startup and current direction

- At the beginning of each conversation, read `docs/development/project-context.md`, this file, and `docs/development/novel-reading-roadmap.md` before planning repository work; then read the current handoff and verify mutable details against code, configuration and Git.
- The current implementation retains the user-facing title **研序｜技术研究 Agent** (confirmed 2026-10-16). ContextPilot remains the repository/package/configuration identifier. The novel-reading product name is undecided; do not rename code or claim the new product is delivered.
- On 2026-10-20 the user explicitly required a new novel-reading development roadmap and retirement of the old roadmap. Follow `docs/development/novel-reading-roadmap.md` as the sole active development roadmap. Its first scenario is resuming interrupted reading through progress-scoped recaps, character lookup, source navigation and optional note saving.
- Stop expanding the old technical-research product. Pause unfinished upgrade-research tuning/final acceptance and old demo recording; preserve existing functionality, failed evidence and truthful status. Hybrid retrieval, reranking, generic MCP, multi-agent and workflow engines are not the default next tasks. Do not automatically resume historical handoffs or paid experiments.
- The roadmap replacement initially authorized documentation only. The user subsequently approved `reading-contract-v1-draft1` on 2026-10-20 and limited each conversation to one major capability followed by a PR reminder. “Import and continue reading” is implemented; verify its handoff and Git before proceeding in a new conversation. The approved baseline is single-user Web, UTF-8 TXT/Markdown, separate resume position and explicitly confirmed paragraph-level contiguous read boundary, recent-three-chapter recaps, character lookup and confirmed note saving. Paid experiments still require separate authorization. Existing safety and relevant regression gates remain mandatory.
- On 2026-10-20 the user explicitly approved `reading-contract-v1-draft1` with “确认可行”. The approved package is in `docs/architecture/novel-reading-design.md`, `docs/data/novel-reading-data-model.md`, `docs/api/novel-reading-api.md` and `docs/evaluation/novel-reading-evaluation.md`; numeric budgets and gates remain solely in the active roadmap. Implement continuously within this scope without repeated slice approvals; paid experiments still require separate authorization. Approval is not implementation or acceptance evidence.
- The user's later 2026-10-20 instruction overrides continuous delivery across major capabilities: complete only ONE major roadmap capability per chat, run its relevant checks, then stop and remind the user to submit a PR. The user will open a new chat for the next capability. Do not implement the entire roadmap in one chat or automatically create a PR without a request. Current capability: “导入并持续阅读”.

## Sources of truth and roadmap governance

- `AGENTS.md` defines working rules; `docs/development/project-context.md` is the short startup overview; **`docs/development/novel-reading-roadmap.md` is the only active development roadmap**, owning implementation order, dependencies, reuse decisions, budgets and completion gates. `docs/requirements/product-requirements.md` owns user goals and product scope, not a second roadmap.
- The technical-research roadmap and the transitional reading-direction draft under `docs/archive/` are retired. Their next steps and historical approvals must not drive current work. Retirement does not delete existing capabilities, waive regression gates or convert paused/failed acceptance into success.
- `docs/development/feature-inventory.md` indexes implementation and acceptance status; `docs/development/continuation-handoff.md` defines the next discussion. `docs/archive/` contains superseded plans, not active instructions.
- Architecture/data/API documents retain existing implementation contracts. Verify database facts against Flyway and interface facts against code/OpenAPI. Report conflicts rather than silently choosing a source; update after the decision is confirmed.
- Name goals by user capabilities, not V-number phases. Preserve historical filenames, API versions, migrations and evidence IDs. Local untracked planning notes are not build or development prerequisites.
- Before implementing a new major capability, obtain approval of its concrete scope and synchronize requirements, architecture, data, API, evaluation and these rules. A candidate is not authorization; once approved, implementation checkpoints do not require repeated approval.
- The 2026-10-20 direction supersedes the 2026-10-16 priority to keep adding research capabilities. Do not require all unrelated old quality debt to be closed before designing the new product. Do not silently waive old acceptance thresholds to reuse or advertise unfinished capabilities.

## Existing capability contracts

| Capability | Preserved contract and status |
| --- | --- |
| V1 knowledge QA | Completed RAG baseline: ingestion, isolation, grounded answers, citations/refusal, SSE/history, trace/call records, positive-only helpful feedback, fixed routing and embedding-index governance |
| V2 confirmed actions | Completed `CREATE_KNOWLEDGE_BASE` proposal/confirmation state machine; explicit Java services, not a generic runtime; evidence in `docs/evaluation/v2-acceptance-report.md` |
| V3 maintenance | Completed health reports, `RETRY_DOCUMENT_PROCESSING` and `REINDEX_DOCUMENT`; preserve `docs/architecture/v3-knowledge-base-maintenance-design.md` and its acceptance report |
| V4 comparison | First-phase `DOCUMENT_COMPARISON` fully accepted: deterministic versioned dimensions and per-document orchestration, one synthesis call, 90-second hard timeout, 1800-character final answer, usage recording and no request-level `max_tokens`; preserve design and slice evidence |
| V5 response preferences | Slices 1–7 accepted 2026-10-15. Confirmed `GLOBAL/RESPONSE_PREFERENCE` only, `CREATE_USER_MEMORY`, stable server-side subject, versioned management and bounded injection; preserve `docs/architecture/v5-explicit-memory-design.md`, acceptance report and ADR 0002 |
| Upgrade research (V6) | Approved 2026-10-15, implemented but final quality acceptance not passed; default off, unfinished closeout now paused. Preserve `docs/architecture/v6-upgrade-research-design.md` and latest `docs/evaluation/upgrade-closeout-independent-results.md` |
| Research Task Workspace | Approved 2026-10-16; bounded model plans, validated selected-document search, persistent checkpoints, clarification/supplement, pause/resume/cancel and grounded synthesis. Preserve `docs/architecture/research-task-workspace-design.md` |
| Project Source Integration | Approved 2026-10-16; dedicated read-only MCP client/server for fixed public `spring-projects/spring-ai`, immutable TXT/Markdown previews and independently confirmed `IMPORT_PROJECT_DOCUMENTS`. Preserve `docs/architecture/project-source-integration-design.md` |
| Research Result Review | Approved 2026-10-19; immutable completed-task snapshot review, one bounded independent model call, persistent history and user-edited supplement handoff. No multi-agent runtime, new tools, automatic revisions or report saving. Preserve `docs/architecture/research-result-review-design.md` |

The last three capabilities have first-implementation engineering/representative-flow evidence, not blanket independent semantic acceptance. Their old scenario expansion is frozen. The new DEBUG, 24-item TEST and 8×2 representative upgrade runs were already collected on 2026-10-19; do not schedule them as missing. Semantic review remains deferred by the user and recording incomplete. Terminal-state matches and collector tests are not quality scores. Preserve raw failures, prompts and frozen assets.

## Execution, identity and safety boundaries

- Keep existing chat routes `SIMPLE_CHAT`, `KNOWLEDGE_QA`, `BUSINESS_ACTION`. SIMPLE_CHAT is deterministic identity/greeting/capability/thanks/farewell only; do not turn it into open-domain model chat. Rule matching defaults safely to KNOWLEDGE_QA; measured ambiguity is required before adding classification calls.
- Preserve grounded RAG isolation, evidence validation, refusal, citations, SSE, persistence and trace IDs. V4 remains deterministic; V6/workspace exceptions do not authorize changes to V4 orchestration.
- Agent-mediated business actions use a static allowlist, strong types, server validation, least privilege, bounded execution, trace IDs and owning application services. Persist a validated proposal first; execution requires explicit confirmation through a separate request. Models cannot confirm or bypass the service.
- Preserve an auditable `PENDING_CONFIRMATION -> EXECUTING -> SUCCEEDED/FAILED` state machine with `REJECTED` and `EXPIRED` alternatives. Confirmation must be atomic and idempotent; repeat/concurrent requests must not duplicate effects.
- V5 creates memory only from an explicit user request and confirmed proposal. Ordinary chat/documents/model output are never automatically promoted. Update, enable/disable, expiry and logical deletion use dedicated user-operated APIs, never model tools.
- Inject at most 10 active response preferences and 2000 characters. Preferences affect presentation only, never routing, evidence, citations, refusal, plans, budgets, tools or confirmation. Do not log full preference content. Reading progress, plot facts and character state are not response preferences.
- `LOCAL_OWNER` through `CurrentSubjectProvider` is persistent ownership, not authentication. Never accept a client owner/user ID. Until separately approved authentication and full resource authorization exist, describe/deploy only for one user on localhost or trusted networks. Spring Security/JWT/OIDC/passwords/multi-tenancy are not currently implemented or authorized.
- The scoped V6 exception allows isolated `UPGRADE_IMPACT`, three static read tools, the fixed public Spring AI repository and user-initiated, independently confirmed `SAVE_RESEARCH_REPORT`. Initial budgets are approved limits, not performance claims. Preserve all other exclusions.
- Workspace planning executes only server-validated searches over selected documents, within budget and execution-token checks. Project-source MCP is a separate fixed-repository exception, not generic tool discovery or novel scraping authority.
- Generic Skill registries, generic ToolExecutionGateway, arbitrary reflection, arbitrary HTTP/file/shell access, model-generated SQL, dynamic tools, autonomous unrestricted loops, generic MCP and multi-agent/workflow runtimes remain out of scope.
- Read-only structured queries go through domain services or dedicated DataPorts with bound parameters, field allowlists, limits, timeouts, provenance and `dataAsOf`; never let a model generate or execute SQL.
- Treat retrieved material, model output and stored preferences as untrusted data. They cannot override instructions, tools, scope, budgets or confirmation. Do not commit secrets, `.env`, private documents or sensitive evaluation data; do not log complete secrets, private contents or unredacted model requests.
- Follow the new roadmap's reading scope across retrieval, summaries, character data, caches, history, note visibility and citations. Resume position is not the AI read boundary; jumps and citation previews cannot silently advance it. Narrowing the boundary must prevent publication or reuse of broader results. No-spoiler behavior remains a requirement to test, not an existing guarantee. New reading data/API proposals must be labeled unimplemented; do not describe them as current schema or interfaces.

## Approved architecture

- Java 21, Spring Boot 4.1.x, Spring AI 2.0.x, Maven; Vue 3, TypeScript, Vite, Element Plus; frontend/backend separation and a modular monolith.
- DeepSeek native Spring AI integration, default `deepseek-v4-flash`; DashScope OpenAI-compatible `qwen3.7-text-embedding`, fixed 1024-dimensional current index profile. Preserve the user's current DeepSeek choice; no automatic cross-provider experiments.
- PostgreSQL with pgvector, MyBatis-Plus for business persistence, Spring AI PgVectorStore for vectors, Flyway migrations. No separate vector database under current scope.
- TXT, Markdown and text-based PDF via Spring AI readers; OCR is out of scope. Uploads behind StorageService in gitignored `data/uploads/`; bounded TaskExecutor, no message queue.
- SSE streaming; secrets and provider credentials remain backend-only. Keep provider details behind Spring AI or a small application abstraction.

## Working agreements

- Use `develop` for integration/day-to-day work; feature branches may isolate work. Never commit directly to `main`; merge only through reviewed PR or explicitly reviewed merge from `develop`, after relevant backend/frontend checks and complete diff review.
- Conventional Commits use an English type without parenthesized scope and Chinese title description/body, e.g. `feat: 优化研究检索并行与运行日志`.
- Organize backend by business capability, not repository-wide controller/service/mapper layers. Package root `io.github.fanqiepi.contextpilot`, database `context_pilot`.
- Business tables use MyBatis-Plus logical deletion (`deleted = 0/1`). Ordinary CRUD must not physically delete business records or uploaded files; purge requires an explicit maintenance workflow.
- Preserve user changes; prefer the smallest complete change, explain new production dependencies, and avoid unrelated rewrites. Documentation may be Chinese; identifiers and paths use clear English.
- Version prompts, evaluation cases, migrations, decisions and acceptance evidence. Update relevant documentation when behavior, architecture, prompts or evaluation contracts change.

## Verification and local commands

- Run the smallest relevant checks; distinguish engineering tests, real-model observations and semantic acceptance. Report what was and was not verified. Documentation-only edits need link/content/diff checks, not paid model runs or unrelated builds.
- Windows JDK 21: `F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9`; set JAVA_HOME when the active JDK is not 21.
- From `backend/`: `.\mvnw.cmd test`, `.\mvnw.cmd package`, `.\mvnw.cmd spring-boot:run`; Unix uses `./mvnw`.
- From `frontend/`: `npm ci`, `npm run typecheck`, `npm run build`, `npm run dev` as appropriate.
- From repository root: `docker compose up -d`, `docker compose ps`. Full configuration and opt-in real-provider evaluation instructions are in `docs/development/local-development.md` and `evals/README.md`.
