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
