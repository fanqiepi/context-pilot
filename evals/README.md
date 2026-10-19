# ContextPilot evaluation assets

## 统一 50 项核心实跑

先在仓库根目录运行 `node evals/tools/baseline.mjs validate`。使用 JDK 21，在 `backend/` 显式执行：

```powershell
.\mvnw.cmd '-Dtest=CoreWorkspaceLiveTests' '-Dcontextpilot.evaluation.core-live=true' "-Dcontextpilot.evaluation.code=$(git rev-parse HEAD)" test
```

需要 Docker 和本地 `local-secrets`。该测试创建独立 PostgreSQL/pgvector 数据库，导入冻结公开语料，通过生产 RAG、固定多文档比对、提案确认和偏好注入服务采集结果；会调用 DeepSeek/DashScope。普通 `test`/`package` 跳过此项。输出 `backend/target/core-live-results.json` 包含公开回答、引用、历史、逐条终态、模型审计、冻结资产哈希和生产源码哈希。它只使用公开合成数据，不能改为私人语料后提交输出。

恢复案例明确记录实验边界：CORE-047 取消生产 SSE Flux 的订阅后查询同一运行（不是 TCP 断线实验）；CORE-048 把真实检索回调延迟至取消后；CORE-049 四路并发确认；CORE-050 注入记忆读取异常。其余问题不使用模型或检索结果 Mock。各案例失败继续保留，NOT_RUN 不计为执行成功；结果采集测试通过也不代表 50 项语义验收通过。人工事实/引用评分必须另行完成，不能把终态匹配当作准确率。

冻结 TEST 只用于阶段评估。首次观测结果不得覆盖或用于反复调参后声称独立泛化提升；后续优化使用 DEBUG，并按已有合同另设独立验收输入。

## 研究任务工作台

工程验证：在 `backend/` 使用 JDK 21 执行 `./mvnw.cmd -Dtest=Task*Tests test`；需要 Docker，真实模型默认跳过。公开合成资料的真实 DeepSeek/DashScope 流程验证显式运行 `./mvnw.cmd -Dtest=TaskWorkspaceLiveTests -Dcontextpilot.evaluation.task-live=true test`，需要本地 local-secrets，会产生供应商调用。输出位于 `backend/target/task-workspace-live.json`；不得将流程通过当作独立语义质量验收。当前证据见[工作台验证记录](../docs/evaluation/research-task-workspace-verification.md)。

The `evals/` directory contains public, versioned, deterministic evaluation inputs and reports.
It must not contain real user documents, credentials, or unredacted production requests.

## V6 当前验证状态

V6 已有受限执行、确认保存、页面及工程测试，首轮冻结专项尚未达到验收条件。详见 [实现验证记录](../docs/evaluation/v6-implementation-review.md)。普通后端 `test`/`package` 不自动进行付费调用。

从 `backend/` 使用 JDK 21 执行：

```powershell
.\mvnw.cmd '-Dtest=V6LiveDebugTests' '-Dcontextpilot.evaluation.v6-debug=true' test
.\mvnw.cmd '-Dtest=V6FixtureEvaluationTests' '-Dcontextpilot.evaluation.v6-fixture=true' test
```

两者需要本机 `local-secrets` 配置与 Docker，使用独立数据库和公开资料。前者是可变 GitHub 来源的真实调试；后者使用真实模型/检索与合成 release fixtures 收集冻结结果。结果文件分别是 `backend/target/v6-live-debug.json`、`backend/target/v6-fixture-evaluation.json`。**结果采集成功不代表语义验收通过**；人工事实/引用评分、统一核心基线及 16 次最终生产验收仍须完成。已观察的 TEST 输入不能继续作为调参后的独立泛化证明。

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
production build; completed production and external-provider acceptance evidence is recorded in
`docs/evaluation/v4-implementation-acceptance-report.md`.

## Run the V5 Slice 1 explicit-memory evaluation

V5 Slice 1 is offline and isolated from production memory code. From `backend/` on Windows:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -Pv5-evaluation test
```

The profile loads 12 explicit-creation routing cases, 8 cross-session preference/current-request
cases, 6 safety and budget cases, and 12 deferred PostgreSQL lifecycle definitions. It compares a
frozen no-memory response baseline with an evaluation-only bounded-preference candidate. The
candidate does not call a model, database, production controller, application service, SSE path,
or frontend code.

The eight representative cases are repeated three times. Passing this profile establishes the
versioned Slice 1 contract; it does not claim that production persistence, confirmation,
management APIs, Prompt injection, history, real DeepSeek behavior, or page flows are complete.

## Current acceptance and cross-version reporting

### V5 production and live acceptance checkpoints

From `backend/`, run `./mvnw -Pv5-production-acceptance package` (Windows:
`.\mvnw.cmd -Pv5-production-acceptance package`). This runs the full regression suite,
including the frozen V5 prototype, its production routing counterpart and real PostgreSQL
memory lifecycle tests. The existing environment gates require Docker instead of silently
accepting skipped database tests. Live providers remain opt-in.

Run `.\mvnw.cmd -Pv5-live-model-acceptance test` with the ignored `local-secrets` configuration
to execute `v5-live-memory-v1`. It creates a disposable Testcontainers database, ingests the
public Atlas/Borealis corpus through real DashScope and pgvector, and executes four preference
scenarios on each production RAG/V4 path. Each scenario uses a persisted, separately confirmed
proposal and compares IGNORE with USE in fresh conversations: 16 answer calls in total.
The profile incurs provider charges; it must never be run with private corpus inputs.

`backend/target/v5-live-acceptance-results.json` records sanitized per-run gates, trace IDs,
provider-returned model names, latency and tokens. `backend/target/v5-live-public-answer-review.md`
is a local review artifact containing only these public synthetic answers; it is not committed.
The automatic result explicitly requires manual review: valid citation numbers and style
predicates do not prove semantic grounding. A missing provider, ingestion failure, assertion
failure or unavailable Docker must not be reported as acceptance success. Page/SSE acceptance
and manual answer review remain separate checks.

V5 production Slices 1–7 are complete. The frozen evaluation, real PostgreSQL lifecycle,
full regression, four live DeepSeek scenarios on each RAG/V4 path and page checks passed on
2026-10-15; see [V5 acceptance](../docs/evaluation/v5-acceptance-report.md).
The Slice 1 profile alone is not production acceptance evidence.

The unified 50-task inputs are frozen, and the shared asset validation and baseline/candidate
report entry point is available in [tools](tools/README.md). Complete task execution and human
semantic review remain outstanding. Existing dataset case counts, test methods and repeated executions
must not be added together as independent semantic tasks. Public synthetic source documents are
under `corpora/`. See [evaluation requirements](../docs/evaluation/evaluation-plan.md) for dataset
separation, metrics and reproducibility, and the [dated review](../docs/archive/2026-10-15-career-readiness-review.md)
for current gaps. Keep offline evaluation, live-model/fixed-retrieval experiments and real
embedding/pgvector end-to-end results separate.
