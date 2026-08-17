# ContextPilot evaluation assets

The `evals/` directory contains public, versioned, deterministic evaluation inputs and reports.
It must not contain real user documents, credentials, or unredacted production requests.

## Run the V2 routing and action-safety evaluation

Prerequisites: JDK 21 and Docker with access to the `pgvector/pgvector:0.8.2-pg17-bookworm`
image. From `backend/` on Windows:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv2-evaluation test
```

The Maven profile runs only `*EvaluationTests`. Routing and parameter cases are offline and
deterministic. Lifecycle cases use Testcontainers and real PostgreSQL/pgvector so atomic and
concurrent confirmation behavior is evaluated against the production persistence implementation.

When the dataset, routing rules, action validation, state transitions, or capability version
changes, create a new dataset/config version and add a new dated report under `reports/`.

## Run the V3 health-maintenance evaluation

Prerequisites are the same as V2. From `backend/` on Windows:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv3-evaluation test
```

The profile runs the V1/V2 evaluation regression, the deterministic V3 routing and health-rule
datasets, and selected real PostgreSQL/pgvector lifecycle tests for immutable reports, trusted
issue selection, confirmation safety, transaction-after-commit dispatch, and bounded recovery.
Docker is mandatory: the profile fails instead of publishing a baseline when the real lifecycle
environment is unavailable.

## Run the V4 Slice 1 document-comparison evaluation

V4 Slice 1 is intentionally isolated from production runtime code. From `backend/` on Windows:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv4-evaluation test
```

The profile loads the public synthetic corpus, 32 semantic cases, 12 deferred lifecycle case
definitions, and the fixed V4 budget. It compares the current global single-turn Top-K shape with
an evaluation-only bounded plan/execution/evidence-ledger prototype. The prototype performs no
network or external model calls and is not reachable from production controllers, services, SSE,
or the frontend.

The eight representative cases are repeated three times to verify deterministic prototype
stability. This does not substitute for live model, PostgreSQL/pgvector, persistence, SSE, restart,
or page evaluation; those results must be recorded separately when their corresponding slice is
implemented and authorized.

### Run the V4 live chat-model comparison

Create the ignored `backend/config/application-local-secrets.yml` from its tracked example, fill
the DeepSeek key, and run from `backend/`:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv4-live-model-evaluation test
```

The live profile runs the same eight representative cases three times for both strategies. It uses
DeepSeek for Planner and Synthesizer calls, while keeping retrieval deterministic so model behavior
is isolated from embedding variance. It disables Flyway, embedding, and VectorStore auto-
configuration; therefore it does not call DashScope or validate real pgvector retrieval.

The sanitized per-run metrics are written to `backend/target/v4-live-model-results.json`. They
contain case IDs, pass/fail metrics, token counts, latency, and safe error codes, but no prompts,
model answers, document contents, or credentials. The fixed versioned summary is stored under
`evals/reports/`.

## Verify the V4 production implementation

The Slice 1 prototype remains evaluation-only. The authorized production baseline uses the
versioned deterministic planner in `src/main`, Flyway V15, persisted research runs, formal API/SSE,
and the Vue research UI. Run the full backend suite, the `v4-evaluation` profile, and the frontend
production build; the current evidence and remaining external-provider gate are recorded in
`docs/evaluation/v4-implementation-acceptance-report.md`.
