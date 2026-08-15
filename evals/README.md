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
