# Engineering Specification: Agentic SDLC Orchestrator and URL Shortener

Status: IMPLEMENTED (this is the pre-implementation contract; the code is the source of truth)
Version: 1.0
Audience: implementers of the three Maven modules, and reviewers who want the full design rationale.

> Implementation note. The modules were built from this specification and then hardened after an
> independent review. Where the implementation deliberately refines the text below (for example:
> checkpoints are taken per stage activation, risk acceptance is carried within a plan version,
> acceptance into context happens only after the exit gate and any approval, denied artifacts are
> removed from history, a human resume extends an exhausted budget, `ResilientStageAgent` was not
> needed because the engine owns retries), `docs/ORCHESTRATION.md` describes the behaviour as
> implemented and lists the differences.

This document is the single source of truth for what is built. It was produced in the
requirements/architecture phase and is intentionally precise: package layout, state machines,
interfaces, JSON contracts, policy rules, and test obligations. The shorter reviewer-facing
overview lives in `docs/ARCHITECTURE.md`; this file is the implementation contract behind it.

---

## 0. Requirements coverage

| Requirement | Where it is satisfied |
|---|---|
| 1. Requirement understanding (intent, ambiguity, normalization) | `intake` stage: `RequirementSpec`, `AmbiguityReport`, clarification gate (section 5.6.1) |
| 2. Task decomposition with dependencies and sequencing | `decompose` stage: `TaskPlan` validated as a DAG, critical path, parallel groups (5.6.2) |
| 3. Codebase reasoning (brownfield) | `impact` stage: `CodebaseIndex` + `ImpactAnalyzer` over the real `url-shortener` source (5.6.3, 5.9) |
| 4. Workflow orchestration (critical differentiator) | `orchestrator-core` engine: explicit DAG, entry/exit gates, parallel joins, lineage, human gates, bounded retries, fallback, saga rollback, safe-stop, policy guardrails, audit log, reliability metrics, re-planning (section 5) |
| 5. Engineering output generation | `url-shortener` service (section 4) and the artifacts produced by stages (5.6) |
| 6. Validation and risk control | `verify` + `security-scan` stages, `PolicyEngine`, `docs/RISKS_AND_TRADEOFFS.md` |
| 7. Controlled autonomy | Autonomy levels, risk-based approvals, budgets, operator identity on every governance action (5.7, 5.8) |
| 8. Final engineering summary | `docs/ENGINEERING_SUMMARY.md` |
| Deliverable: runnable prototype | Two Spring Boot apps, `./mvnw verify`, demo scripts |
| Deliverable: three scenarios | Scenario catalog: greenfield, brownfield, ambiguous (+ policy-denial, fault-injection, replan) (section 8) |

---

## 1. System overview

Two planes, one repository:

```
+------------------------------------------------------------------+
|  CONTROL PLANE  orchestrator-app (Spring Boot, :8090)            |
|  REST API + operator console  ->  orchestrator-core engine       |
|  persistence (H2/JPA)  ->  agents (deterministic | Anthropic |   |
|  Ollama)  ->  policy engine  ->  audit + metrics                 |
|                      |  reads source tree (codebase reasoning)   |
+----------------------|-------------------------------------------+
                       v
+------------------------------------------------------------------+
|  WORKLOAD  url-shortener (Spring Boot, :8080)                    |
|  links API, redirects, click analytics, reliability controls     |
+------------------------------------------------------------------+
```

- `url-shortener` is the production-grade service the platform is demonstrated on. It is independently
  deployable and has no dependency on the orchestrator.
- `orchestrator-core` is a framework-independent Java library (no Spring) implementing the
  agentic SDLC engine. It is unit-tested in isolation with fake agents and an in-memory store.
- `orchestrator-app` wires the engine into a Spring Boot control plane: REST API, operator
  console, JPA persistence, LLM providers, Micrometer metrics, scenario catalog.

Why one repository: the brownfield scenario needs the orchestrator to reason over a real
codebase. The `url-shortener` module is that codebase. The control plane indexes it from disk.

---

## 2. Technology baseline and conventions

| Item | Decision |
|---|---|
| Language | Java 25 (LTS). Records, sealed interfaces, pattern-matching `switch`, virtual threads are encouraged. No Lombok. |
| Framework | Spring Boot 4.1.2 / Spring Framework 7 / Jakarta EE 11 / Hibernate 7.4 / Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation.*`). |
| Build | Maven 3.9.16 via `./mvnw`; multi-module; enforcer requires Java 25+. JaCoCo report on `verify`. |
| Testing | JUnit 6 (Jupiter), AssertJ, Mockito 5, Awaitility, ArchUnit 1.5. Spring test slices: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-restclient-test`. Use `@MockitoBean` (never `@MockBean`). `@SpringBootTest` needs `@AutoConfigureMockMvc` to get `MockMvc`/`MockMvcTester`. |
| Starters | `spring-boot-starter-webmvc` (not `-web`), `-validation`, `-data-jpa`, `-flyway`, `-actuator`, `-cache`, `-restclient`. |
| JSON | Jackson 3. Spring auto-configures a `tools.jackson.databind.json.JsonMapper` bean. Dates serialize as ISO-8601 strings by default. |
| Persistence | Flyway migrations (`db/migration/V1__*.sql`), H2 file database by default under `./data/` (git-ignored), PostgreSQL profile for the shortener. |
| Errors | RFC 9457 Problem Details (`spring.mvc.problemdetails.enabled=true` plus `@RestControllerAdvice`). |
| Threads | `spring.threads.virtual.enabled=true` in both apps. Engine executes stages on virtual threads. |
| Nullability | JSpecify `@NullMarked` at package level in core; `@Nullable` on nullable members. |
| Style | 4-space indent, 120-col soft limit, constructor injection, final fields, immutable collections returned, package-private where possible, no static mutable state, no `System.out`. Javadoc on public types in core. |
| IDs | `UUID.randomUUID().toString()` for runs/artifacts. |
| Logging | SLF4J. Structured key=value messages. Never log secrets, API keys, or full requirement text at INFO (DEBUG only). |
| Ports | shortener `8080`, orchestrator `8090`. |

Build commands (from repository root):

```
./mvnw -B verify                                   # everything, with tests and JaCoCo
./mvnw -pl url-shortener spring-boot:run           # shortener on :8080
./mvnw -pl orchestrator-app spring-boot:run        # orchestrator on :8090 (console at /)
```

---

## 3. Repository layout

```
agentic-sdlc-url-shortener/
  pom.xml                           parent: Boot 4.1.2, Java 25, dependency/plugin management
  mvnw, mvnw.cmd, .mvn/wrapper/     Maven wrapper (3.9.16, script-only)
  .github/workflows/ci.yml          build+test+coverage, container image build
  url-shortener/                    workload service (section 4)
  orchestrator-core/                engine library (section 5)
  orchestrator-app/                 control plane app + console (sections 6, 7)
  docs/
    design/engineering-spec.md      this document
    ARCHITECTURE.md                 reviewer overview, diagrams (Mermaid), key decisions
    ORCHESTRATION.md                DAG, state machines, gates, recovery, metrics definitions
    SCENARIOS.md                    the three required scenarios + failure-path scenarios
    TESTING.md                      testing approach, how to run, what is covered
    RISKS_AND_TRADEOFFS.md          risks, failure scenarios, trade-offs, guardrails
    ENGINEERING_SUMMARY.md          final engineering summary (plan, artifacts, assumptions, limitations)
    AI_ASSISTANCE.md                how AI tooling was used; what humans owned
    api/url-shortener.openapi.yaml  API contract of the shortener
    api/orchestrator.openapi.yaml   API contract of the control plane
    adr/0001-....md                 architecture decision records
  scripts/demo.sh, scripts/demo.ps1 end-to-end demo via curl
  docker-compose.yml                optional PostgreSQL for the shortener
  url-shortener/Dockerfile, orchestrator-app/Dockerfile
```

---

## 4. `url-shortener` module

Root package `com.agenticsdlc.shortener`.

### 4.1 Package layout

```
com.agenticsdlc.shortener
  UrlShortenerApplication
  api/            LinkController, RedirectController, AnalyticsController, dto/*, ApiExceptionHandler
  domain/         Link (entity), ClickEvent (entity), LinkStatus (enum), IdempotencyRecord (entity)
  repository/     LinkRepository, ClickEventRepository, IdempotencyRecordRepository (Spring Data JPA)
  service/        LinkService, ShortCodeGenerator, UrlValidator, AnalyticsService, ClickRecorder,
                  IdempotencyService, ClientIpResolver, UserAgentClassifier
  config/         ShortenerProperties (@ConfigurationProperties record), CacheConfig, AsyncConfig,
                  RateLimitFilter + TokenBucketRateLimiter, OpenApiConfig, ClockConfig
  resources/      application.yml, application-postgres.yml, db/migration/V1__init.sql
```

### 4.2 REST API (`/api/v1`)

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/links` | Create short link. Body `CreateLinkRequest { url (required, <=2048), customAlias?, ttlSeconds? , expiresAt? }` (ttlSeconds and expiresAt are mutually exclusive). Header `Idempotency-Key` optional (<=128 chars). | `201` + `Location` + `LinkResponse` | `400` validation, `409` alias taken, `422` URL rejected by policy (private host, scheme, credentials), `429` rate limited |
| GET | `/api/v1/links/{code}` | Link metadata | `200 LinkResponse` | `404` |
| DELETE | `/api/v1/links/{code}` | Deactivate (soft delete, status `DISABLED`) | `204` | `404` |
| GET | `/api/v1/links/{code}/analytics?days=7` | Aggregated analytics (`days` 1..90, default 7) | `200 AnalyticsResponse` | `404` |
| GET | `/{code}` | Redirect | `302 Found` with `Location`, `Cache-Control: no-store` | `404` unknown, `410 Gone` expired or disabled |

`LinkResponse { code, shortUrl, originalUrl, status (ACTIVE|DISABLED|EXPIRED), createdAt, expiresAt?, clickCount }`.
`AnalyticsResponse { code, windowDays, totalClicks, uniqueVisitors, clicksByDay: [{date, clicks}], topReferrers: [{referrer, clicks}] (max 10, "direct" when none), byUserAgentFamily: [{family, clicks}] }`.

Rules:
- Codes: random Base62, length `shortener.code-length` (default 7), generated with `SecureRandom`; on unique-constraint collision retry up to 3 times, then `503` with Problem Detail. Rationale: unpredictable codes resist enumeration; 62^7 keyspace makes collisions negligible.
- Custom alias: `^[A-Za-z0-9_-]{4,32}$`, case-sensitive, rejected if in the reserved set (`api`, `actuator`, `swagger-ui`, `v3`, `health`, `admin`, `login`, `static`, `error`).
- URL validation (`UrlValidator`): scheme `http`/`https` only; must have a host; no userinfo; length <= 2048; host must not be `localhost`, an IP literal in loopback/private/link-local/multicast/unspecified ranges, the cloud metadata address `169.254.169.254`, or `.local`/`.internal` TLDs. Blocking is configurable (`shortener.block-private-targets=true`). DNS resolution is deliberately not performed (documented trade-off: a hostname that resolves to a private address at redirect time is not detected).
- Expiry: `expiresAt` must be in the future and at most `shortener.max-ttl` (default 365 days) ahead. Expired links return `410`. Status is derived (`EXPIRED`) when `expiresAt < now`; the stored status remains `ACTIVE` unless disabled.
- Redirect uses `302` (not `301`) so clients do not cache permanently and expiry/deactivation/analytics stay effective (documented trade-off).
- Click recording is asynchronous: `RedirectController` publishes a `ClickRecorded` application event; `ClickRecorder` handles it with `@Async` on a dedicated executor and persists `ClickEvent { linkId, occurredAt, referrerHost (nullable), userAgentFamily, ipHash }`. `ipHash` = SHA-256 of `clientIp + ":" + dailySalt` (salt derived from `shortener.analytics.ip-salt` + UTC date). Raw IPs are never stored (privacy/compliance decision). `Link.clickCount` is incremented with a single `UPDATE ... SET click_count = click_count + 1` query.
- Caching: Caffeine cache `links-by-code` (max 10 000, expire after write 60 s) around the lookup used by redirects; evicted on delete.
- Rate limiting: `RateLimitFilter` applies a token bucket per client IP to `POST /api/v1/links` (`shortener.rate-limit.capacity=20`, `refill-per-minute=20`); exceeds -> `429` with `Retry-After` header. In-memory (documented: use a distributed limiter in a multi-node deployment).
- Idempotency: when `Idempotency-Key` is present, `IdempotencyService` returns the stored response (status + body JSON) for the same key + same request fingerprint within 24 h; same key with a different fingerprint -> `422`.
- `ClientIpResolver` honours `X-Forwarded-For` only when `shortener.trust-forwarded-headers=true`.
- Actuator: `health`, `info`, `metrics` exposed. Custom Micrometer meters: `shortener.links.created`, `shortener.redirects` (tag `outcome` = `redirected|not_found|gone`), `shortener.redirect.latency` timer.
- OpenAPI UI via springdoc at `/swagger-ui.html`; the committed contract is `docs/api/url-shortener.openapi.yaml` (hand-maintained, must match the controllers).

### 4.3 Data model (Flyway `V1__init.sql`, H2 and PostgreSQL compatible SQL)

```
links (id BIGINT identity PK, code VARCHAR(32) UNIQUE NOT NULL, original_url VARCHAR(2048) NOT NULL,
       status VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NULL,
       click_count BIGINT NOT NULL DEFAULT 0, custom_alias BOOLEAN NOT NULL DEFAULT FALSE)
click_events (id BIGINT identity PK, link_id BIGINT NOT NULL FK->links, occurred_at TIMESTAMP NOT NULL,
       referrer_host VARCHAR(255) NULL, user_agent_family VARCHAR(32) NOT NULL, ip_hash VARCHAR(64) NOT NULL)
       index (link_id, occurred_at)
idempotency_records (idem_key VARCHAR(128) PK, fingerprint VARCHAR(64) NOT NULL, status INT NOT NULL,
       body CLOB NOT NULL, created_at TIMESTAMP NOT NULL)
```

Use `TIMESTAMP` (not `TIMESTAMP WITH TIME ZONE`) with `Instant` mapped via Hibernate default; store UTC.
`spring.jpa.hibernate.ddl-auto=validate`.

### 4.4 Configuration (`application.yml`)

```yaml
server.port: 8080
spring:
  threads.virtual.enabled: true
  datasource.url: jdbc:h2:file:./data/url-shortener;AUTO_SERVER=TRUE
  jpa.hibernate.ddl-auto: validate
  jpa.open-in-view: false
  mvc.problemdetails.enabled: true
management.endpoints.web.exposure.include: health,info,metrics
shortener:
  base-url: http://localhost:8080
  code-length: 7
  max-ttl: 365d
  block-private-targets: true
  trust-forwarded-headers: false
  rate-limit: { enabled: true, capacity: 20, refill-per-minute: 20 }
  analytics: { ip-salt: change-me-in-production }
```

`application-postgres.yml` switches the datasource to `jdbc:postgresql://localhost:5432/shortener`
(user/password from env `SHORTENER_DB_USER` / `SHORTENER_DB_PASSWORD`). `docker-compose.yml` at the
repo root starts PostgreSQL 17 for that profile.

### 4.5 Tests (minimum)

- Unit: `ShortCodeGeneratorTest` (length, alphabet, randomness sanity), `UrlValidatorTest`
  (parameterized: accepted and rejected URLs incl. `127.0.0.1`, `10.0.0.1`, `169.254.169.254`,
  `[::1]`, `localhost`, `ftp://`, userinfo, too long), `UserAgentClassifierTest`,
  `TokenBucketRateLimiterTest` (uses injected `Clock`), `LinkServiceTest` (Mockito: alias conflict,
  expiry computation, deactivate, collision retry).
- Slice: `LinkControllerTest` (`@WebMvcTest` + `@MockitoBean LinkService`: validation errors as Problem
  Details, 201 with Location, 409, 422), `LinkRepositoryTest` (`@DataJpaTest` with Flyway on H2 in-memory).
- Integration: `UrlShortenerIntegrationTest` (`@SpringBootTest` + `@AutoConfigureMockMvc`, in-memory H2
  profile `test`): create -> redirect 302 -> click recorded (Awaitility) -> analytics reflects click ->
  delete -> 410; expired link -> 410; idempotent replay returns same code; rate limit returns 429 when
  capacity is 2 in the test profile.
- Architecture: `ArchitectureTest` (ArchUnit): `api` may depend on `service` and `domain`; `service`
  must not depend on `api`; `domain` depends on nothing in the module; no field injection.

---

## 5. `orchestrator-core` module

Root package `com.agenticsdlc.orchestrator.core`. No Spring dependency. Dependencies: Jackson 3
databind, SLF4J API, JSpecify.

### 5.1 Package layout

```
com.agenticsdlc.orchestrator.core
  graph/       StageDefinition, GateSpec, ApprovalRequirement, RetryPolicy, WorkflowDefinition,
               WorkflowGraph (validation + topology), WorkflowDefinitions (default SDLC graph)
  model/       Run, RunStatus, RunOptions, AutonomyLevel, RunBudget, FaultSpec, FaultKind,
               StageExecution, StageStatus, Approval, ApprovalKind, ApprovalStatus, RejectDisposition,
               Clarification, ClarificationQuestion, Checkpoint, Compensation, HaltReason, RunCounters
  artifact/    Artifact, ArtifactKind, Provenance, and payload records (section 5.6)
  agent/       AgentRole, StageAgent, StageContext, StageResult, StageFailure, FailureKind,
               AgentRegistry, ResilientStageAgent, FaultInjectingStageAgent
  agent/deterministic/   one agent per role (section 5.6)
  agent/llm/   LlmClient (SPI), LlmRequest, LlmResponse, LlmUsage, LlmStageAgent, PromptTemplates,
               JsonSchemas (record -> JSON schema), LlmException
  policy/      PolicyEngine, PolicyRule, PolicyContext, PolicyDecision, Verdict, PolicyFinding,
               Severity, RiskLevel, RiskAssessment, rules/* (section 5.7)
  policy/pack/ PolicyPack, DeclarativeRule, Condition, Effect, PolicyPackLoader, DeclarativePolicyRule (5.7.1)
  workspace/   CodebaseIndex, CodebaseIndexer, IndexedType, TypeKind, Endpoint, ImpactAnalyzer,
               WorkspaceSandbox, BuildRunner, ProcessBuildRunner, BuildResult, DependencyInventory (5.6.9)
  engine/      OrchestrationEngine, Scheduler, StageExecutor, GateEvaluator, ReplanService,
               RollbackService, RunRegistry (locks + cache), EngineConfig, EngineListener
  audit/       AuditEvent, AuditEventType, Actor, AuditSink, InMemoryAuditSink
  metrics/     ReliabilityMetrics (record), MetricsCalculator, MetricsRecorder (SPI), NoopMetricsRecorder,
               LlmPriceBook, LlmPrice (5.10.1)
  evaluation/  ArtifactScorer(s), Score, Criterion, RunEvaluator, EvaluationReport, EvaluationSuite,
               EvaluationSuiteRunner, SuiteResult (5.13)
  port/        RunStore (SPI), InMemoryRunStore
  json/        JsonCodec (Jackson 3 based: canonical serialization, hashing, typed parsing)
  util/        Hashes (SHA-256 hex), Ids, Clocks
```

### 5.2 Workflow graph

```java
public record StageDefinition(
    String id,                      // "intake"
    String name,                    // "Requirement understanding"
    AgentRole role,                 // REQUIREMENTS
    Set<String> dependsOn,          // predecessor stage ids
    GateSpec gates,                 // entry/exit gate behaviour
    RetryPolicy retryPolicy,        // maxAttempts (incl. first), initial backoff, multiplier
    Duration timeout,               // per attempt
    boolean fallbackToDeterministic // when the primary (LLM) agent is exhausted
) {}

public record GateSpec(ApprovalRequirement entryApproval, ApprovalRequirement exitApproval) {}
public enum ApprovalRequirement { NEVER, RISK_BASED, ALWAYS }
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier) {}
public record WorkflowDefinition(String id, int version, List<StageDefinition> stages) {}
```

`WorkflowGraph` wraps a definition and provides: validation (unique ids, known dependencies,
acyclic; throws `InvalidWorkflowException`), `topologicalOrder()`, `layers()` (longest-path layering
for UI), `downstreamOf(stageId)` (transitive successors), `predecessors(stageId)`.

Default SDLC workflow (`WorkflowDefinitions.defaultSdlc()`, id `sdlc-default`, version 1):

| id | role | dependsOn | entry | exit | retries | timeout |
|---|---|---|---|---|---|---|
| `intake` | REQUIREMENTS | - | NEVER | NEVER (clarification gate is intrinsic, see 5.4) | 3 | 3 min |
| `decompose` | PLANNING | intake | NEVER | NEVER | 3 | 3 min |
| `impact` | CODEBASE_ANALYSIS | intake | NEVER | NEVER | 3 | 3 min |
| `design` | ARCHITECTURE | decompose, impact | NEVER | RISK_BASED | 3 | 3 min |
| `implement` | IMPLEMENTATION | design | NEVER | RISK_BASED (policy may require, e.g. protected paths) | 3 | 5 min |
| `test-design` | TEST_DESIGN | design | NEVER | NEVER | 3 | 3 min |
| `verify` | VERIFICATION | implement, test-design | NEVER | NEVER | 2 | 10 min |
| `document` | DOCUMENTATION | implement | NEVER | NEVER | 3 | 3 min |
| `security-scan` | SECURITY_REVIEW | implement | NEVER | NEVER | 2 | 3 min |
| `release-readiness` | RELEASE | verify, document, security-scan | NEVER | ALWAYS | 2 | 3 min |

Parallel segments: `decompose || impact`; `implement || test-design`; `document || security-scan`.
Synchronization points (joins): `design`, `verify`, `release-readiness`.

### 5.3 Run state

```java
public enum RunStatus { RUNNING, WAITING_HUMAN, SAFE_STOPPED, COMPLETED, ABORTED }
public enum StageStatus { PENDING, RUNNING, RETRY_SCHEDULED, AWAITING_APPROVAL, AWAITING_CLARIFICATION,
                          SUCCEEDED, FAILED, STALE, CANCELLED }
public enum AutonomyLevel { FULL_AUTO, GUIDED, STRICT }
public enum HaltReason { OPERATOR_STOP, POLICY_DENIED, AGENT_FAILURE_EXHAUSTED, BUDGET_EXCEEDED,
                         INTERRUPTED_BY_RESTART, ROLLBACK_FAILED }
```

`Run` is a mutable aggregate (mutated only under the run lock, see 5.5) holding: id, scenario key,
original requirement, active requirement (after clarification/replan), `RunOptions`, `RunBudget`,
`RunStatus`, `@Nullable HaltReason haltReason`, `haltDetail`, `planVersion`, timestamps
(`createdAt`, `startedAt`, `endedAt`), `Map<String, StageExecution> stages` (one per definition,
insertion-ordered), `List<Artifact> artifacts` (full history, all versions), `Map<String,Integer>
acceptedVersions` (stage id -> accepted artifact version), `List<Approval> approvals`,
`@Nullable Clarification clarification`, `Deque<Checkpoint> checkpoints`, `List<Compensation>
compensations`, `RunCounters counters` (retries, fallbacks, rollbacks, replans, feedbackLoops,
approvalsRequested/Approved/Rejected/Revoked, agentCalls, llmInputTokens, llmOutputTokens),
`List<Interval> humanWaitIntervals`, `@Nullable Instant firstFailureAt`, `@Nullable Instant recoveredAt`,
`long auditSeq`.

`StageExecution` holds: stageId, status, attempts, `@Nullable String activeAgent` (e.g. `deterministic`,
`anthropic:claude-opus-5-5`, `fallback:deterministic`), startedAt/endedAt of the latest attempt,
`@Nullable String lastError`, `List<String> feedback` (reviewer comments, verify failures), `@Nullable
Instant nextAttemptAt`, `@Nullable Approval pendingApproval`.

`RunOptions { AutonomyLevel autonomy, @Nullable String providerId, List<FaultSpec> faults,
@Nullable RunBudget budgetOverride, WorkspaceMode workspace (NONE|REPO), Map<String,String> tags }`.

`RunBudget { int maxFeedbackLoops=2, int maxReplans=3, int maxAgentCalls=40, long maxLlmTokens=200_000,
Duration maxActiveDuration=PT2H }` (per-stage attempts come from `RetryPolicy`).

`FaultSpec { String stageId, int failTimes, FaultKind kind }` with
`FaultKind { TRANSIENT, FATAL, TIMEOUT, INVALID_OUTPUT }`. Faults are applied by
`FaultInjectingStageAgent`, which wraps the real agent only when the run has faults for that stage.
Fault state is per run (counter of injected failures so far).

### 5.4 Stage state machine

```
PENDING --deps SUCCEEDED, entry gate ALLOW--> RUNNING
PENDING --entry gate REQUIRE_APPROVAL--------> AWAITING_APPROVAL(ENTRY) --approve--> RUNNING
RUNNING --agent success, exit gate ALLOW-----> SUCCEEDED
RUNNING --agent success, exit REQUIRE_APPROVAL> AWAITING_APPROVAL(EXIT) --approve--> SUCCEEDED
RUNNING --agent needs clarification (intake)-> AWAITING_CLARIFICATION --clarify--> RUNNING
RUNNING --retryable failure, attempts left---> RETRY_SCHEDULED --backoff elapsed--> RUNNING
RUNNING --attempts exhausted, fallback avail-> RUNNING (activeAgent = fallback:deterministic)
RUNNING --attempts exhausted, no fallback----> FAILED  => RollbackService => run SAFE_STOPPED
RUNNING --exit gate DENY (policy)------------> FAILED  => rollback => SAFE_STOPPED(POLICY_DENIED)
AWAITING_APPROVAL --reject(REWORK)-----------> RUNNING (feedback appended; counts as feedback loop)
AWAITING_APPROVAL --reject(ABORT)------------> CANCELLED, run ABORTED
SUCCEEDED --upstream changed (replan/rework)-> STALE --deps SUCCEEDED--> RUNNING (new artifact version)
any non-terminal --run stopped---------------> stays as is (in-flight attempt is cancelled cooperatively)
PENDING/STALE --run aborted------------------> CANCELLED
```

Entry gate evaluation = `PolicyEngine.evaluate(ENTRY, ctx)` combined with `GateSpec.entryApproval`
and `AutonomyLevel` (STRICT => every stage entry requires approval). Exit gate evaluation =
artifact validation (Jakarta-style invariants implemented in the payload records' compact
constructors or `validate()` methods) + `PolicyEngine.evaluate(EXIT, ctx)` + `GateSpec.exitApproval`
(`ALWAYS` => approval; `RISK_BASED` => approval when the run's current `RiskAssessment.level >=
MEDIUM` in GUIDED, never in FULL_AUTO except `release-readiness` which is `ALWAYS` regardless).

Intrinsic clarification gate: the `intake` agent returns `StageResult.needsClarification(questions)`
when `AmbiguityReport.score >= EngineConfig.ambiguityThreshold` (default `0.5`) and no clarification
answers are present in the run. Once answers are provided, intake re-runs with them.

Verification feedback loop (non-linear execution): when `verify` produces a `VerificationReport`
with `passed=false`, the engine does not fail the run. It appends the report's
`feedbackForImplementation` to `implement.feedback`, marks `implement` STALE, increments
`counters.feedbackLoops`, invalidates downstream (`verify`, `document`, `security-scan`,
`release-readiness`) and continues. When `counters.feedbackLoops > budget.maxFeedbackLoops`,
`verify` is marked FAILED with `lastError = "verification kept failing after N feedback loops"`
and the run safe-stops (`AGENT_FAILURE_EXHAUSTED`).

### 5.5 Engine semantics

`OrchestrationEngine` public API (all methods validate state and throw
`IllegalStateException`/`RunNotFoundException`/`InvalidCommandException` as appropriate):

```java
Run start(String requirement, String scenarioKey, RunOptions options);
Run approve(String runId, String stageId, Actor actor, @Nullable String comment);
Run reject(String runId, String stageId, Actor actor, String reason, RejectDisposition disposition);
Run clarify(String runId, Actor actor, Map<String, String> answers);
Run replan(String runId, Actor actor, String changedRequirement);
Run stop(String runId, Actor actor, String reason);
Run resume(String runId, Actor actor);
Run get(String runId);            // snapshot copy, safe to serialize
List<Run> list();                 // snapshot copies
void recoverInterrupted();        // on startup: RUNNING attempts -> PENDING, run SAFE_STOPPED(INTERRUPTED_BY_RESTART)
```

Threading model:
- `RunRegistry` keeps a `ConcurrentHashMap<String, RunHolder>` where `RunHolder { Run run;
  ReentrantLock lock; Map<String, Future<?>> inFlight; }`. All mutations happen inside
  `withLock(runId, fn)`. After every mutation the engine calls `RunStore.save(run)` and flushes
  pending audit events to `AuditSink` while still holding the lock (durability before visibility).
- Registry eviction (bounded memory): the registry is a cache. A run that is not executing on this node
  (not RUNNING, no in-flight attempt, no lease held, last commit succeeded) is evicted after
  `EngineConfig.registryRetention` (default 1 h) without a change; terminal runs (COMPLETED, ABORTED) beyond
  `registryMaxTerminalRuns` (default 500) are evicted least recently ended first. Eviction runs after every
  command, in `list()` and on `evictIdleRuns()`. No data is lost: `get` falls back to `RunStore.load`, `list`
  merges the cache with `RunStore.loadAll()` (the cached copy wins unless the store is newer), and a command
  on an evicted run loads it again first (rehydration).
- Run leases (multi-node): a `RunLeaseProvider` (port, default `InMemoryRunLeaseProvider`) leases a run to
  the node (`EngineConfig.nodeId`) that executes its stages, from the command or event that schedules work
  until the run waits for a human, is safe-stopped or ends. It is renewed at every mutation and every
  `leaseTtl / 3` (default TTL 60 s) while an attempt runs. A mutation of a run leased by another node throws
  `RunLeasedElsewhereException` (an `InvalidRunStateException`, HTTP 409); reads need no lease. After
  acquiring a lease the engine reloads the run if the store holds a newer state; an expired lease can be
  taken over; `close()` releases the node's leases and `recoverInterrupted()` skips runs leased by a live
  node. `nodeId` is recorded in RUN_CREATED, RUN_RESUMED, REPLAN, RUN_INTERRUPTED and STAGE_STARTED data.
  The per-run lock remains the mechanism within one node.
- `pump(run)` (called after every mutation): compute ready stages via `Scheduler` (status PENDING or
  STALE, all predecessors SUCCEEDED, run status RUNNING or WAITING_HUMAN); for each ready stage
  evaluate the entry gate; stages allowed to run are submitted to an `ExecutorService` built from
  `Thread.ofVirtual().name("sdlc-stage-", 0).factory()`. Each submission records a `Checkpoint`
  (copy of `acceptedVersions`, `planVersion`, list of compensation ids) tagged with the stage id.
- Stage execution (`StageExecutor`, off-lock): build a `StageContext` (immutable view: run id, active
  requirement, clarification answers, accepted upstream artifacts, feedback, attempt number, plan
  version, workspace index, options). Call `agent.execute(ctx)` with a timeout (`Future.get(timeout)`
  on a nested virtual thread). Map outcome to `StageResult` (`success(payload, rationale,
  compensations)`, `needsClarification(questions)`, `failure(StageFailure)`). Exceptions become
  `StageFailure(kind=TRANSIENT|FATAL|TIMEOUT|INVALID_OUTPUT, message)`.
- Completion handler re-enters `withLock` and applies the result: on success create an `Artifact`
  (version = previous accepted version + 1, hash = SHA-256 of canonical JSON of the payload,
  `Provenance { derivedFrom = Map<stageId, version> of the artifacts the context exposed, agentRole,
  providerId, model, promptHash (null for deterministic), inputTokens, outputTokens, latencyMs,
  attempt }`), append it to `run.artifacts`, update `acceptedVersions`, evaluate exit gate, set status,
  audit, then `pump`. If the new artifact hash differs from the previously accepted version's hash
  (rework/replan case), `ReplanService.invalidateDownstream(run, stageId, reason)` marks SUCCEEDED
  successors STALE and revokes their approvals (`APPROVAL_REVOKED` audit).
- Retry: on retryable failure with attempts left, status `RETRY_SCHEDULED`, `nextAttemptAt = now +
  backoff` (initialBackoff * multiplier^(attempt-1), capped at 30 s). A single scheduler thread
  (`ScheduledExecutorService`) wakes the run at `nextAttemptAt` and sets the stage back to RUNNING via
  `pump`. In tests `EngineConfig.backoffScale` can be set to 0 to make retries immediate.
- Fallback: when attempts are exhausted and `fallbackToDeterministic` is true and the active agent is
  not already deterministic, switch `activeAgent` to `fallback:deterministic`, reset attempts to 0,
  increment `counters.fallbacks`, audit `FALLBACK_ENGAGED`, continue.
- Failure handling: `RollbackService.rollback(run, stageId, reason)` executes compensations registered
  after the stage's checkpoint in reverse order (each wrapped; failures are audited as
  `COMPENSATION_FAILED` and set `HaltReason.ROLLBACK_FAILED`), restores `acceptedVersions` and
  `planVersion` from the checkpoint, marks the stage FAILED, increments `counters.rollbacks`, cancels
  in-flight futures for the run, sets `RunStatus.SAFE_STOPPED` with the halt reason, and audits
  `ROLLBACK_COMPLETED` and `SAFE_STOP`.
- Budgets: before submitting any stage, `pump` checks `counters.agentCalls < maxAgentCalls`,
  `llm tokens < maxLlmTokens`, active duration `< maxActiveDuration`. On breach: safe-stop with
  `BUDGET_EXCEEDED` and a detail naming the budget.
- Human wait accounting: when the run transitions into `WAITING_HUMAN` (no RUNNING/RETRY_SCHEDULED
  stages and at least one AWAITING_* stage) an interval opens; it closes when a human command moves
  the run back to RUNNING.
- Completion: when every stage is SUCCEEDED the run becomes `COMPLETED`, `endedAt` is set, the
  `release-readiness` artifact's change record is finalized with the approvals list (approver,
  timestamp, comment) by the engine (not the agent) so that approvals are authoritative.
  Signed change records: at that moment `EngineConfig.changeRecordSigner` (SPI `ChangeRecordSigner`;
  default `UnsignedChangeRecordSigner`, no signature; `HmacChangeRecordSigner` = HmacSHA256, key of at
  least 32 bytes, hex value, constant-time verification) signs the canonical JSON of the finalized record
  without its `signature` member, stored as `ChangeRecord.signature { algorithm, keyId, value, signedAt }`.
  The ARTIFACT_ACCEPTED event of the finalized record carries `signatureAlgorithm` and `keyId` (and
  `signatureError` when the signer failed; the run still completes, unsigned).
- Commands: `approve`/`reject` require the stage to be `AWAITING_APPROVAL`; `clarify` requires
  `intake` to be `AWAITING_CLARIFICATION`; `replan` is allowed in RUNNING, WAITING_HUMAN and
  SAFE_STOPPED (not after COMPLETED/ABORTED) and requires `counters.replans < maxReplans`; `stop`
  requires RUNNING or WAITING_HUMAN; `resume` requires SAFE_STOPPED and `haltReason != POLICY_DENIED`
  (a policy denial needs a changed requirement, i.e. `replan`). `resume` resets FAILED stages to
  PENDING with attempts 0, clears `haltReason`, status RUNNING, pumps.
- `replan(changedRequirement)`: sets the active requirement, `planVersion++`, marks `intake` and all
  downstream SUCCEEDED/AWAITING/RETRY stages STALE (artifact history kept), revokes their approvals,
  cancels in-flight futures of invalidated stages, increments `counters.replans`, audits `REPLAN`
  with the list of invalidated stages, clears any halt, sets RUNNING, pumps. Stages that are not
  downstream of the change keep their accepted artifacts (none in the default graph since intake is
  the root, but the implementation must be generic: `invalidateDownstream(run, fromStage)`).
- Every state transition emits an `AuditEvent` through `AuditSink.append` (buffered per command and
  flushed under the lock). `AuditEventType` values: `RUN_CREATED, STAGE_READY, GATE_EVALUATED,
  APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED, APPROVAL_REVOKED, CLARIFICATION_REQUESTED,
  CLARIFICATION_PROVIDED, STAGE_STARTED, AGENT_DECISION, ARTIFACT_ACCEPTED, STAGE_SUCCEEDED,
  STAGE_FAILED, RETRY_SCHEDULED, FALLBACK_ENGAGED, FEEDBACK_LOOP, POLICY_FINDING, POLICY_DENIED,
  CHECKPOINT_CREATED, COMPENSATION_EXECUTED, COMPENSATION_FAILED, ROLLBACK_COMPLETED, REPLAN,
  STAGE_INVALIDATED, BUDGET_EXCEEDED, SAFE_STOP, RUN_RESUMED, RUN_COMPLETED, RUN_ABORTED,
  RUN_INTERRUPTED`. `AuditEvent { long seq, Instant timestamp, String runId, @Nullable String stageId,
  AuditEventType type, Actor actor, String message, Map<String, Object> data, @Nullable String prevHash,
  @Nullable String hash }`.
  `Actor { ActorType type (SYSTEM|AGENT|HUMAN), String id }`.
- Audit hash chain: every event is chained per run when it is created. `hash` is the lowercase hex
  SHA-256 of the UTF-8 canonical JSON (`JsonCodec.strictCanonicalJson`: keys sorted at every level, no
  whitespace, normalized numbers) of `{seq, timestamp (ISO-8601 UTC), runId, stageId ("" when null), type,
  actorType, actorId, message, data, prevHash}`; `prevHash` is the previous event's hash, or
  `AuditChain.GENESIS` (64 zeros) for the first event. The chain head is stored in the run snapshot
  (`auditHeadHash`) and resynchronized with `AuditSink.lastEvent` when a run is loaded or a commit fails;
  a mutation that is not committed rolls the sequence and head back, so the chain has no gaps.
  `AuditChain.verify(events)` reports content changes, broken links, sequence gaps and unsigned (legacy)
  events as a `ChainVerification`. `InMemoryAuditSink` rejects a batch that does not continue the stored
  chain. Engine messages are capped at 2000 characters before hashing.
- `EngineListener` (optional callback) receives `onRunChanged(Run)` after each mutation so the app can
  push updates (not required by the UI, which polls).

### 5.6 Stage agents and artifact payloads

`StageAgent`:

```java
public interface StageAgent {
    AgentRole role();
    String id();                              // "deterministic" | "anthropic:<model>" | "ollama:<model>"
    StageResult execute(StageContext context) throws Exception;
}
```

`AgentRegistry` maps `AgentRole -> StageAgent` for a provider id, exposes
`agentFor(role, providerId)` and `deterministic(role)`. `ResilientStageAgent` is not needed as a
separate class if the engine performs retries/fallback (preferred; keep retry logic in one place).

All payload records live in `artifact/` and must: be Jackson-3 serializable with public canonical
constructors (records), contain only `String`, numbers, `boolean`, enums, `List<...>`, nested records
and `Map<String, String>` (so that JSON schemas can be derived and LLM structured outputs work), and
validate their invariants in `validate()` (throw `InvalidArtifactException` with a message listing
problems). Each has a `markdown()` rendering used by the console and documentation.

Common: `RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }`, `RiskAssessment { RiskLevel level, List<String>
factors }`, `ArtifactKind { REQUIREMENT_SPEC, TASK_PLAN, IMPACT_ANALYSIS, DESIGN_SPEC, CHANGE_SET,
TEST_PLAN, VERIFICATION_REPORT, DOCUMENTATION_BUNDLE, SECURITY_REVIEW, RELEASE_READINESS }`.

#### 5.6.1 `intake` -> `RequirementSpec`

```
RequirementSpec { String normalizedStatement; String intent; Classification classification (GREENFIELD|BROWNFIELD|AMBIGUOUS);
  List<String> scopeIn; List<String> scopeOut; List<Requirement> functional; List<Requirement> nonFunctional;
  List<AcceptanceCriterion> acceptanceCriteria; List<String> assumptions; List<String> openQuestions;
  AmbiguityReport ambiguity; RiskAssessment preliminaryRisk }
Requirement { String id (FR-1 / NFR-1); String text; Priority priority (MUST|SHOULD|COULD); String category (nonFunctional only: PERFORMANCE|RELIABILITY|SECURITY|COMPLIANCE|OPERABILITY|USABILITY|OTHER); boolean measurable }
AcceptanceCriterion { String id (AC-1); String given; String when; String then; List<String> traces (requirement ids) }
AmbiguityReport { double score (0..1); List<AmbiguityFinding> findings; List<ClarificationQuestion> questions }
AmbiguityFinding { String code; String description; double weight }
ClarificationQuestion { String id (q1..); String question; String why; List<String> suggestedAnswers }
```

Deterministic `RequirementsAgent` algorithm:
1. Normalize whitespace; split sentences; tokenize (lowercase, strip punctuation).
2. Ambiguity scoring (weights sum > 1, clamp to 1.0): `SHORT_STATEMENT` (< 60 chars, 0.25);
   `VAGUE_QUALIFIER_WITHOUT_METRIC` (any of better, faster, improve, improved, enhance, optimize,
   robust, reliable, scalable, modern, clean, nicer, performant, with no digit anywhere, 0.30);
   `NO_MEASURABLE_TARGET` (no digits and no unit words ms, seconds, rps, %, percent, users, 0.15);
   `NO_DOMAIN_NOUN` (none of api, endpoint, service, link, url, redirect, analytics, click, database,
   schema, cache, latency, throughput, alias, expiry, ttl, test, documentation, deploy, 0.20);
   `OPEN_ENDED_PHRASE` (etc, and so on, somehow, whatever, as needed, 0.10);
   `CONFLICTING_TERMS` (both "no database" and "persist"/"store"; or "synchronous" and "async", 0.20);
   `UNSPECIFIED_SCOPE` (contains "everything", "all the", "entire system", 0.10).
   Questions are generated per fired finding (deterministic texts, e.g. for VAGUE_QUALIFIER: "Which
   operations must be faster (redirect, create, analytics) and what is the target p95 latency?").
3. Classification: if score >= threshold -> AMBIGUOUS; else BROWNFIELD if the text contains any of
   existing, current, add to, extend, migrate, refactor, legacy, bulk, "the shortener", or if
   `context.workspaceIndex()` is present and `options.workspace == REPO` and the text does not start
   with build/create/implement a new; otherwise GREENFIELD.
4. Requirements extraction: each sentence containing must/should/shall/need/support/provide/allow/
   expose/record/prevent -> a requirement; category by keywords (latency/rps/throughput -> PERFORMANCE,
   available/uptime/retry/fail -> RELIABILITY, private/ssrf/auth/validate -> SECURITY, pii/retention/
   audit -> COMPLIANCE, health/metrics/log -> OPERABILITY); `measurable` when the sentence has a digit.
   Priority MUST when must/shall/required, SHOULD when should, else COULD. Ensure at least one FR; if
   none extracted, create FR-1 from the normalized statement.
5. Acceptance criteria: one per FR in Given/When/Then form derived from templates; NFRs with numbers
   get a measurable criterion.
6. Assumptions: standard list (single region, HTTP JSON APIs, Java/Spring stack, existing CI);
   plus "clarification answers are authoritative" when answers exist. Answers are merged: the
   normalized statement becomes `original + " Clarifications: " + joined answers`.
7. Preliminary risk: HIGH if security/compliance keywords or schema words, MEDIUM for public API
   change words (api, endpoint) or performance targets, else LOW.

#### 5.6.2 `decompose` -> `TaskPlan`

```
TaskPlan { List<Task> tasks; List<String> criticalPath (task ids); List<List<String>> parallelGroups; String sequencingRationale }
Task { String id (T1..); String title; String description; TaskType type (ANALYSIS|DESIGN|IMPLEMENT|TEST|DOCS|OPS|GOVERNANCE);
  List<String> dependsOn; Estimate estimate (S|M|L); Owner owner (AGENT|HUMAN); List<String> traces (requirement ids); String acceptance }
```

Deterministic `PlanningAgent`: for each FR create DESIGN -> IMPLEMENT -> TEST tasks chained; NFRs with
PERFORMANCE/RELIABILITY produce OPS tasks (observability, load test) depending on the implement tasks;
SECURITY/COMPLIANCE produce GOVERNANCE tasks owned by HUMAN (review/approval). Add one ANALYSIS task
first (impact review) when classification is BROWNFIELD, a DOCS task last, and a GOVERNANCE "release
approval" task owned by HUMAN at the end. Validate the task graph is acyclic; compute the critical
path by longest path with estimates S=1, M=2, L=3; parallel groups = tasks per topological layer.

#### 5.6.3 `impact` -> `ImpactAnalysis`

```
ImpactAnalysis { String workspaceRoot (or "none"); int indexedFiles; boolean existingCodebase;
  List<ImpactedComponent> components; List<ImpactedEndpoint> endpoints; List<String> entities; List<String> migrations;
  List<DataFlow> dataFlows; List<String> riskFactors; List<String> suggestedTests; String summary }
ImpactedComponent { String name; String type (CONTROLLER|SERVICE|REPOSITORY|ENTITY|CONFIG|FILTER|TEST|RESOURCE|OTHER); String path; double confidence (0..1); List<String> reasons; ChangeNature nature (MODIFY|EXTEND|NEW|REGRESSION_RISK) }
ImpactedEndpoint { String method; String path; String controller }
DataFlow { String from; String to; String via }
```

Deterministic `CodebaseReasoningAgent` uses `ImpactAnalyzer` (5.9). With `WorkspaceMode.NONE` or an
empty index it returns `existingCodebase=false` and proposes NEW components derived from the
requirement spec (controller/service/repository/entity per noun group), with the summary "No existing
codebase; greenfield module structure proposed".

#### 5.6.4 `design` -> `DesignSpec`

```
DesignSpec { String overview; List<Component> components; List<ApiChange> apiChanges; List<DataModelChange> dataModelChanges;
  List<String> sequenceFlows; List<NfrStrategy> nfrStrategies; List<String> securityConsiderations; DataHandling dataHandling;
  List<Alternative> alternativesConsidered; RiskAssessment risk; List<Decision> decisions }
Component { String name; String responsibility; String type; boolean isNew }
ApiChange { String method; String path; String summary; String requestSchema; String responseSchema; List<String> errorResponses; boolean breaking }
DataModelChange { String entity; String change; String migration; boolean backwardCompatible }
NfrStrategy { String requirementId; String strategy }
DataHandling { List<String> piiFields; String retention; String protection }
Alternative { String option; List<String> pros; List<String> cons; boolean chosen }
Decision { String title; String decision; String rationale }
```

Deterministic `ArchitectureAgent`: derive components from impact (MODIFY/EXTEND existing or NEW),
API changes from FR verbs (create/list/get/delete/redirect/bulk...), data model changes from nouns
(alias, expiry, click, analytics...), NFR strategies by category (PERFORMANCE -> caching + async +
index; RELIABILITY -> health checks, timeouts, retries, idempotency; SECURITY -> validation, allow-lists,
rate limiting; COMPLIANCE -> hashing, retention), data handling (detect PII words: ip, email, name,
user agent; default retention "90 days", protection "hashed with rotating salt"), always at least two
alternatives with one chosen, risk = max(preliminary risk, HIGH if any DataModelChange not backward
compatible or any breaking API change, MEDIUM if any ApiChange or DataModelChange).

#### 5.6.5 `implement` -> `ChangeSet`

```
ChangeSet { String baseRevision; List<FileChange> changes; String buildCommand; List<String> risks; String rollbackPlan; List<String> traces (task ids) }
FileChange { String path; Operation operation (CREATE|MODIFY|DELETE); String language; String summary; ContentKind contentKind (SKELETON|FULL|DIFF|NONE); String content; List<String> traces }
```

Deterministic `ImplementationAgent`: one `FileChange` per impacted/new component with
`contentKind=SKELETON` and a generated Java skeleton (package, class, constructor injection,
method stubs with `// TODO implement: <task>`), a Flyway migration file when data model changes exist
(`V<N>__<slug>.sql` with real DDL derived from `DataModelChange`), and one test file per production
file (`<Name>Test.java` skeleton with test method names from acceptance criteria). Paths are placed
under the impacted component's module (`url-shortener/src/main/java/...`) or
`url-shortener/src/...` for new components. `buildCommand = "./mvnw -pl url-shortener verify"`.
`rollbackPlan` = revert commit + Flyway undo note. Feedback from previous verify failures (if any)
is reflected by adding a `// Addressed feedback: ...` comment and, for a missing-test finding, the
missing test file.

LLM `ImplementationAgent` requests `contentKind=FULL` content. Content must be included for every
CREATE/MODIFY.

Compensation: when the run's `options.workspace == REPO` and `EngineConfig.sandboxEnabled` is true,
the implementation agent materializes the change set into a `WorkspaceSandbox` (copy of the workspace
under `./sandbox/<runId>/`) and registers a compensation `DELETE_SANDBOX <path>`. Default
`sandboxEnabled=false` (documented optional capability).

#### 5.6.6 `test-design` -> `TestPlan`

```
TestPlan { String strategy; List<TestLevel> levels; List<TestCase> cases; Map<String, String> coverageMatrix (requirement id -> comma-separated case ids); List<String> risksNotCovered }
TestLevel { Level level (UNIT|SLICE|INTEGRATION|CONTRACT|PERFORMANCE|SECURITY); String scope; String tooling }
TestCase { String id (TC-1); String title; Level level; CaseType type (POSITIVE|NEGATIVE|BOUNDARY|RECOVERY|SECURITY); String preconditions; List<String> steps; String expected; List<String> traces }
```

Deterministic: for each acceptance criterion one POSITIVE and one NEGATIVE case; for each
PERFORMANCE NFR a PERFORMANCE case; for SECURITY a SECURITY case; RECOVERY cases for reliability
NFRs; coverage matrix must cover every requirement id or list it in `risksNotCovered`.

#### 5.6.7 `verify` -> `VerificationReport`

```
VerificationReport { List<Check> structuralChecks; Traceability traceability; @Nullable BuildOutcome build; boolean passed; List<String> feedbackForImplementation }
Check { String name; boolean passed; String details }
Traceability { int requirementsTotal; int requirementsCovered; List<String> uncovered }
BuildOutcome { boolean executed; String command; int exitCode; long durationMs; String summary; List<String> failures }
```

Deterministic `VerificationAgent` checks: `paths-within-allowed-roots` (every path starts with an
allowed root: `url-shortener/`, `orchestrator-core/`, `orchestrator-app/`, `docs/`), `no-secrets`
(regexes: `AKIA[0-9A-Z]{16}`, `sk-ant-[A-Za-z0-9_-]{10,}`, `ghp_[A-Za-z0-9]{20,}`,
`(?i)password\s*[:=]\s*\S+`), `tests-accompany-production-changes` (each `src/main` Java change has
a `src/test` change whose file name contains the production type name, or the change set has a
documented justification in `risks`), `migration-present-when-schema-changes`, `api-changes-
implemented` (each design `ApiChange.path` appears in some controller change summary/content),
`content-present` (no CREATE/MODIFY with `contentKind=NONE`), `traceability` (every FR id traced by
at least one test case; uncovered listed). `passed = all structural checks passed && uncovered empty
&& (build == null || build.exitCode == 0)`. Feedback lists each failed check in imperative form.

Optional real build (`EngineConfig.verifyBuildEnabled=false` by default): when enabled and a sandbox
exists, `BuildRunner.run(sandboxPath, buildCommand, timeout)` executes the project's `mvnw` in the
sandbox with a 10-minute timeout, captures the exit code and the last 50 lines, and parses surefire
summary lines ("Tests run: N, Failures: F, Errors: E"). `ProcessBuildRunner` uses `ProcessBuilder`,
resolves `mvnw.cmd` on Windows, never runs anything outside the sandbox directory, and rejects commands
not starting with `./mvnw`, `mvnw`, or `mvn`.

#### 5.6.8 `document` -> `DocumentationBundle`

```
DocumentationBundle { String readmeSection; String apiReference; String operationsRunbook; String changelogEntry; List<String> adrs; }
```
Deterministic: generated Markdown from the design spec and change set (API table from `apiChanges`,
runbook with config keys, health checks and rollback plan, changelog entry from the normalized
statement, ADRs from `DesignSpec.decisions`).

#### 5.6.9 `security-scan` -> `SecurityReview`

```
SecurityReview { List<Threat> threats; List<Check> checks; List<Finding> findings; List<String> complianceNotes; boolean passed;
  List<DependencyRecord> dependencies }
Threat { String id; String category (STRIDE: SPOOFING|TAMPERING|REPUDIATION|INFORMATION_DISCLOSURE|DENIAL_OF_SERVICE|ELEVATION_OF_PRIVILEGE); String description; String mitigation; String status (MITIGATED|ACCEPTED|OPEN) }
Finding { Severity severity (LOW|MEDIUM|HIGH|CRITICAL); String title; String recommendation }
DependencyRecord { String module (POM path); String groupId; String artifactId; String version (resolved, or "managed" | "unknown" | "none");
  String scope ("build" for plugins); Kind kind (DEPENDENCY|PLUGIN); String section (dependencies|dependencyManagement|build/plugins|build/pluginManagement, "profile:<id>/" prefix) }
```

Dependency inventory (REPO runs; `workspace/DependencyInventory`, applied by `agent/DependencyReview` in both the
deterministic and the LLM agent, so the inventory is always read from the build files and never taken from model
output): every `pom.xml` under the run's sandbox (when it exists) or the workspace root, skipping `target/`, `.git/`,
`node_modules/`, `sandbox/`, `.idea/`, parsed with the JDK XML parser in secure mode (DOCTYPE rejected, no external
entities or DTDs, no XInclude). Properties are resolved from the module and its in-workspace parents. Flags: `system`
scope and `LATEST`/`RELEASE` HIGH, version ranges and SNAPSHOT versions of artifacts not built in the workspace MEDIUM,
duplicate declarations and plugins without a managed version LOW (a plugin whose parent POM lies outside the
workspace gets version `unknown` and no flag), unparseable POMs MEDIUM. One finding per flag kind (title prefix
`Dependency hygiene: `) and a `dependency-inventory` check (passed when no flag is HIGH). This is an inventory and
hygiene check, not a CVE scan. Optional external scanner: `EngineConfig.dependencyScanCommand` (default null) runs in
the run's sandbox through the same `BuildRunner` and allow-list as `buildCommand` (Maven commands only) with
`dependencyScanTimeout` (default 5 min); only the exit status and the first 2000 characters of the captured output
(credential-like tokens redacted) are recorded in a `dependency-scan` check; a non-zero exit is a HIGH finding, a
scan that could not run or timed out MEDIUM, a configured scan without a sandbox an advisory check only.

Deterministic: STRIDE threats templated from the API changes and data handling; checks: input
validation present in design (`securityConsiderations` mentions validation), SSRF/open-redirect
guard when URLs are handled, no raw PII (data handling protection not empty when piiFields non-empty),
rate limiting for public create endpoints, no secrets in change set, mutating endpoints documented
with auth note. `passed = no CRITICAL/HIGH findings`. A failed scan yields `StageResult.success` with
`passed=false`; the release stage turns that into NO_GO (so humans see it) rather than a hard failure.

#### 5.6.10 `release-readiness` -> `ReleaseReadiness`

```
ReleaseReadiness { List<ChecklistItem> checklist; GoNoGo decision (GO|GO_WITH_CONDITIONS|NO_GO); List<String> conditions; ChangeRecord changeRecord; String releaseNotes }
ChecklistItem { String item; String status (PASS|FAIL|WAIVED|N_A); String evidence }
ChangeRecord { String summary; RiskLevel riskLevel; String rollbackPlan; List<ApprovalRecord> approvals; List<String> artifactIds; String policyVerdict; @Nullable Signature signature }
Signature { String algorithm; String keyId; String value; String signedAt }   // engine-written at completion (5.5); agents never sign
ApprovalRecord { String stageId; String approver; String decidedAt; String comment }
```
Deterministic: checklist from upstream artifacts (verification passed, security passed, docs present,
traceability complete, rollback plan present, approvals for design if required). `decision = NO_GO`
when verification or security failed; `GO_WITH_CONDITIONS` when there are MEDIUM findings or
waived items; else `GO`. `approvals` are filled by the engine at completion (section 5.5).

#### 5.6.11 LLM agents

`LlmStageAgent<T>` is generic over the payload record: builds a system prompt (`PromptTemplates.system(role)`:
role description, governance constraints: "You operate inside a governed SDLC pipeline. Only reference
files that appear in the provided codebase index. Never include secrets. Respond only with the
requested JSON.") and a user prompt containing the active requirement, clarification answers, the
accepted upstream artifacts as JSON, and any feedback. It calls `LlmClient.complete(LlmRequest, Class<T>)`
and validates the returned payload (`validate()`); invalid payloads throw `StageFailure(INVALID_OUTPUT)`
(retryable; the next attempt includes the validation error in the prompt). Provenance records the
provider id, model, prompt hash and usage.

```java
public interface LlmClient {
    String providerId();                     // "anthropic" | "ollama"
    String model();
    boolean isAvailable();                   // cheap check, cached for 30 s
    <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) throws LlmException;
}
public record LlmRequest(String system, String user, int maxOutputTokens, Duration timeout) {}
public record LlmResponse<T>(T output, String rawText, LlmUsage usage, long latencyMs, String model) {}
public record LlmUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {}  // 2-arg constructor: cache counts 0
public class LlmException extends Exception { boolean retryable; }
```

`inputTokens` counts every input token, prompt cache reads and writes included; the cache counts are parts of it
(uncached input = input - cacheRead - cacheCreation). `LlmTrace`, `Provenance` and `RunCounters` carry the cache
counts too (compatibility constructors keep the old shapes; new persisted fields are boxed so older JSON reads).

`JsonSchemas.forRecord(Class<?>)` produces a JSON-schema `Map<String,Object>` (type object,
properties, required = all components, `additionalProperties=false`, enums as `enum` arrays, lists as
`array`, nested records recursively, `Map<String,String>` as object with `additionalProperties:
{type: string}`). Used by the Ollama client (`format`) and included in prompts as guidance.

### 5.7 Policy engine

```java
public interface PolicyRule { String id(); Optional<PolicyFinding> evaluate(PolicyContext ctx); }
public record PolicyContext(GatePhase phase (RUN_START|ENTRY|EXIT), String stageId, Run runSnapshot,
                            @Nullable Object candidatePayload, AutonomyLevel autonomy) {}
public record PolicyFinding(String ruleId, Severity severity, Verdict verdict, String message) {}
public enum Verdict { ALLOW, REQUIRE_APPROVAL, DENY }
public record PolicyDecision(Verdict verdict, List<PolicyFinding> findings) {}   // verdict = max severity order DENY > REQUIRE_APPROVAL > ALLOW
```

Rules (all deterministic, each with unit tests):

| id | phase | logic | verdict |
|---|---|---|---|
| `SEC-001 ProhibitedIntent` | RUN_START, EXIT(intake) | requirement or normalized statement matches: disable/remove/bypass + (validation, authentication, auth, security, approval, approvals, tests, testing, audit, logging), "skip tests", "ignore compliance", "allow any destination", "log full ip", "store plaintext password", "exfiltrate" | DENY |
| `SEC-002 SecretLeak` | EXIT(any) | candidate payload canonical JSON matches secret regexes (5.6.7) | DENY |
| `SEC-003 ProtectedPath` | EXIT(implement) | any `FileChange.path` matches `**/security/**`, `**/application-prod*.yml`, `.github/**`, `**/pom.xml` | REQUIRE_APPROVAL |
| `CMP-001 PiiHandling` | EXIT(design) | `dataHandling.piiFields` non-empty and (`protection` blank or `retention` blank) | REQUIRE_APPROVAL (HIGH finding) |
| `CMP-002 PiiProhibited` | RUN_START, EXIT(intake) | text asks to store raw/full client IP, email in clear, or "without hashing" | DENY |
| `CHG-001 ChangeSize` | EXIT(implement) | > 25 files or > 1500 content lines | REQUIRE_APPROVAL |
| `CHG-002 ReleaseApproval` | EXIT(release-readiness) | always | REQUIRE_APPROVAL |
| `CHG-003 NoGoRelease` | EXIT(release-readiness) | `decision == NO_GO` | REQUIRE_APPROVAL with HIGH finding (humans may still approve with a waiver comment; the approval comment is recorded) |
| `GOV-001 RiskBasedDesignApproval` | EXIT(design) | autonomy GUIDED and risk >= MEDIUM, or autonomy STRICT | REQUIRE_APPROVAL |
| `GOV-002 StrictEntryApproval` | ENTRY(any except intake) | autonomy STRICT | REQUIRE_APPROVAL |
| `GOV-003 AutonomyFloor` | EXIT(release-readiness) | FULL_AUTO still requires release approval (same as CHG-002; present to make the boundary explicit) | REQUIRE_APPROVAL |

`PolicyEngine.evaluate(ctx)` runs every rule, audits each finding (`POLICY_FINDING`), and combines.
A DENY at RUN_START makes `start` create the run, audit `POLICY_DENIED`, and immediately safe-stop it
with `HaltReason.POLICY_DENIED` (so the denial is visible and auditable, not a 400 error).

#### 5.7.1 Policy packs (policy as code)

`policy/pack/PolicyPack { name, version, List<DeclarativeRule> rules, List<String> disabledRules }`, loaded with
`PolicyPackLoader.fromJson(json)` (YAML is converted to JSON by the app) and activated with
`PolicyEngine.withDefaultRulesAnd(pack)`: built-in rules minus `disabledRules`, then the pack's rules (adapters,
pack order). `DeclarativeRule { id ([A-Z]{2,5}-\d{3}, never a built-in id), description, phase (RUN_START|ENTRY|EXIT),
stages (empty = all; must be empty for RUN_START), when (Condition), effect, severity (default by effect), message }`.
Effects: `DENY` -> verdict DENY (default HIGH), `REQUIRE_APPROVAL` -> REQUIRE_APPROVAL (default MEDIUM), `WARN` ->
an ALLOW finding with severity LOW (never blocks; audited and shown as a gate note). Conditions (JSON `type`
discriminator): `intentMatches {regexes}` (requirement, clarification answers, normalized statement;
case-insensitive), `pathMatches {globs}` (candidate change set or impact paths, else the accepted ones; glob syntax
of SEC-003), `payloadMatches {regexes}` (candidate payload's strict canonical JSON; EXIT rules only),
`riskAtLeast {level}`, `changeSizeOver {files, lines}`, `releaseDecisionIs {decision}`, `autonomyIs {autonomy}`,
`allOf`/`anyOf {conditions}`, `not {condition}` (nesting depth at most 16). Loading is strict and reports every
problem with its JSON path: malformed JSON or duplicate keys, unknown fields, unknown enum values or condition types,
invalid regexes or globs, duplicate ids, built-in id collisions, disabling an unknown rule. `CHG-002`, `GOV-003` and
`SEC-002` (`PolicyPack.NON_DISABLEABLE_RULE_IDS`: the release gate and the secret-leak guard) can never be disabled.
`PolicyPack.hash()` is the SHA-256 of the pack's strict canonical JSON; when a pack is active, RUN_CREATED and every
POLICY_DENIED audit event carry `policyPack {name, version, hash}`. Sample: `policy/sample-policy-pack.json`
(immutable V1 migration, security code approval, PII warning, CRITICAL risk in FULL_AUTO).

### 5.8 Governance summary (what "controlled autonomy" means here)

- Agents never approve anything. Approvals carry a human `Actor.id` (from `X-Operator-Id`).
- Autonomy levels bound how often humans are consulted; the release gate can never be removed.
- Every agent output passes artifact validation and policy evaluation before acceptance.
- Budgets bound retries, feedback loops, re-plans, total agent calls, tokens and active time.
- Rollback is explicit (saga compensations + checkpoint restore) and always followed by a safe-stop.
- The audit log is append-only and every artifact carries provenance to its inputs (decision lineage).

### 5.9 Codebase index and impact analysis

`CodebaseIndexer.index(Path root)` walks the tree (skips `target`, `.git`, `node_modules`, `data`,
`sandbox`): for every `*.java` under `src/main/java` or `src/test/java` records `IndexedType { String
fqcn, String simpleName, String path (relative, forward slashes), String module (first path segment),
TypeKind kind, List<Endpoint> endpoints, @Nullable String table, Set<String> references (simple names
of other indexed types used), boolean test }`. Kind detection by regex on the file text:
`@RestController|@Controller` -> CONTROLLER; `@Service` -> SERVICE; `@Repository` or `extends
(Jpa|Crud|ListCrud)Repository` -> REPOSITORY; `@Entity` -> ENTITY; `@Configuration|@ConfigurationProperties`
-> CONFIG; `extends OncePerRequestFilter|implements Filter` -> FILTER; under `src/test` -> TEST; else
COMPONENT. Endpoints: class-level `@RequestMapping("...")` prefix + method-level
`@(Get|Post|Put|Delete|Patch)Mapping("...")` or `@RequestMapping(method=...)`. Also indexes
`src/main/resources/db/migration/*.sql` as RESOURCE entries (`migrations` list) and `application*.yml`.
`CodebaseIndex` exposes: `types()`, `byKind(kind)`, `find(simpleName)`, `dependentsOf(simpleName)`,
`endpoints()`, `migrations()`, `summary()` (counts per module and kind).

`ImpactAnalyzer.analyze(index, requirementSpec)`: build the keyword set from the normalized statement,
requirement texts and scope (tokens length >= 4, minus stop words), expanded with a synonym map
(`alias -> alias, code, slug`; `expire -> expiry, expires, ttl`; `analytics -> click, clicks, analytics,
stats, referrer`; `redirect -> redirect, resolve, location`; `bulk -> bulk, batch, list, multiple`;
`rate -> ratelimit, bucket, throttle`; `security -> validator, ssrf, private, auth`; `qr -> qr, image,
png`; `performance -> cache, caffeine, latency, index`). Score each type: +0.5 if the simple name
contains a keyword, +0.3 per keyword found in the path, +0.1 per keyword in content (cap 0.4);
controllers and services whose endpoints match requirement verbs get +0.2. Types with score >= 0.3 are
directly impacted (`MODIFY` or `EXTEND` when the requirement adds behaviour); their dependents and
dependencies (one hop) are added with confidence * 0.6 as `REGRESSION_RISK`; entities impacted add the
latest migration plus a proposed new migration; tests referencing impacted types become
`suggestedTests` along with proposed new test names. Data flows are derived as
`controller -> service -> repository -> table` chains for impacted controllers. Risk factors: schema
change, public API change, filter/security change, shared service change (> 3 dependents).

### 5.10 Reliability metrics

`MetricsCalculator.compute(Collection<Run> runs, Clock clock) -> ReliabilityMetrics`:

```
ReliabilityMetrics {
  RunStats runs { total, completed, aborted, safeStopped, inProgress, waitingHuman, successRate = completed / (completed + aborted) (0 when no terminal runs) }
  StageStats stages { executions (attempts), retries, retryRate = retries / executions, fallbacks, rollbacks, rollbackFrequency = rollbacks / total runs, feedbackLoops, replans }
  LatencyStats latency { endToEndMsAvg, endToEndMsP50, endToEndMsP95, activeMsAvg, humanWaitMsAvg, agentCallMsAvg, agentCallMsP95 } (over completed runs; agent-call stats over all artifacts' provenance latency)
  RecoveryStats recovery { incidents (runs with firstFailureAt), recovered (runs with recoveredAt), mttrMs (mean of recoveredAt - firstFailureAt), unrecovered }
  ApprovalStats approvals { requested, approved, rejected, revoked, meanDecisionMs }
  LlmStats llm { calls, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, @Nullable estimatedCostUsd, unpricedCalls }
}
```

Definitions: `firstFailureAt` is set on the first failed attempt in a run; `recoveredAt` is set when
the failed stage later SUCCEEDS (via retry or fallback). End-to-end latency = `endedAt - startedAt`;
active = end-to-end minus the sum of `humanWaitIntervals`. Percentiles use nearest-rank.

`MetricsRecorder` SPI (Micrometer adapter in the app): `stageCompleted(stageId, status, durationMs)`,
`retry(stageId)`, `fallback(stageId)`, `rollback(runId)`, `runCompleted(durationMs, activeMs)`,
`agentCall(providerId, role, latencyMs, inputTokens, outputTokens)`, `approvalDecision(decisionMs, approved)`.
The engine calls the default overload `agentCall(..., cacheReadTokens, cacheCreationTokens)` (delegates to the
5-argument method) and the default no-op `llmCost(providerId, role, @Nullable costMicroUsd)` for every attempt with
LLM usage.

#### 5.10.1 LLM cost model

`metrics/LlmPriceBook` maps `(providerId, model)` to `LlmPrice { inputPerMillionUsd, outputPerMillionUsd,
cacheReadPerMillionUsd, cacheWritePerMillionUsd }`; lookup (case-insensitive): exact model, else the longest model
prefix ending at a name boundary (`claude-opus-5` prices `claude-opus-5-5`, `gpt-4` does not price `gpt-4o`), else the
provider default. The core ships no prices: `EngineConfig.priceBook` defaults to `LlmPriceBook.EMPTY` and the app maps
operator configuration. Cost in micro-dollars = uncached input x input price + output x output price + cache reads x
cache read price + cache writes x cache write price (tokens x USD per million = micro-dollars), summed exactly and
rounded half up. The engine prices each call when it records its usage: `RunCounters.llmCostMicroUsd` (null as soon as
one call cannot be priced; `llmUnpricedCalls` counts those), `Provenance.costMicroUsd`, and the AGENT_DECISION audit
data (`provider`, `model`, `inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheCreationTokens`, `costMicroUsd`
when priced). `LlmStats.estimatedCostUsd` sums the known run costs (a lower bound when `unpricedCalls > 0`; null when
no run with LLM calls could be priced).

### 5.11 Ports

```java
public interface RunStore { void save(Run run); Optional<Run> load(String id); List<Run> loadAll(); }
public interface AuditSink { void append(List<AuditEvent> events); List<AuditEvent> forRun(String runId, long afterSeq);
                             default Optional<AuditEvent> lastEvent(String runId); }
public interface RunLeaseProvider { Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl);
                                    boolean renew(RunLease lease, Duration ttl); void release(RunLease lease);
                                    Optional<String> holderOf(String runId); }
public record RunLease(String runId, String ownerId, String token, Instant expiresAt) {}
```

Sinks must store `prevHash`/`hash` and every hashed field verbatim, and should override `lastEvent` with a
bounded query. A lease provider grants a new token only when the run has no valid lease (not re-entrant),
renews by token (also after expiry if nobody took over) and judges expiry with its own clock.
`InMemoryRunLeaseProvider` is the default (one per engine unless shared).

`InMemoryRunStore` and `InMemoryAuditSink` live in core (used by tests and as default). The JPA
adapters live in the app. `Run` must be serializable to JSON via `JsonCodec` (the JPA store persists
the aggregate as one JSON document); provide a `RunSnapshot` record mirror if the mutable aggregate is
awkward to serialize directly, with `Run.toSnapshot()` / `Run.fromSnapshot()`.

`OrchestrationEngine.list()` is authoritative but expensive: every call reads every run of the store
(`RunStore.loadAll()`) and merges the cached runs. `OrchestrationEngine.listCached()` returns only the runs cached in
the node's registry (no store access; evicted runs and runs this node never loaded are missing, and runs changed by
another node may be stale) for frequent, cheap reads such as console polling.

- `WorkflowGraphTest`: validation (duplicate id, unknown dependency, cycle), topological order, layers,
  downstream closure for the default graph (e.g. downstream of `design` = implement, test-design,
  verify, document, security-scan, release-readiness).
- `OrchestrationEngineTest` (fake agents, in-memory store, immediate backoff): greenfield happy path
  pauses at design approval (GUIDED + MEDIUM risk) and release approval, completes after both;
  FULL_AUTO only pauses at release; STRICT pauses at every entry; parallel branches both run (assert
  via a latch-based fake agent that `implement` and `test-design` overlap); join waits for both;
  ambiguity -> AWAITING_CLARIFICATION -> clarify -> continues; TRANSIENT fault x2 -> two retries then
  success, counters and `firstFailureAt/recoveredAt` set; TRANSIENT fault x99 -> fallback engaged and
  run continues; FATAL fault on a stage without fallback -> rollback executed (compensation callback
  invoked), run SAFE_STOPPED, checkpoint restored; policy denial at RUN_START -> SAFE_STOPPED(POLICY_DENIED)
  and `resume` rejected, `replan` with a clean requirement proceeds; verify failure -> feedback loop ->
  implement re-runs with feedback -> second verify passes; feedback loops exceeding budget -> safe-stop;
  replan after design approval -> downstream STALE, approval REVOKED, re-approval required; budget
  `maxAgentCalls` exceeded -> SAFE_STOPPED(BUDGET_EXCEEDED); stop/resume; recoverInterrupted.
- `PolicyEngineTest` / per-rule tests.
- `RequirementsAgentTest` (ambiguity scoring table-driven; classification), `PlanningAgentTest` (DAG,
  critical path), `ImpactAnalyzerTest` (against a synthetic temp workspace with controller/service/
  repository/entity files), `VerificationAgentTest`, `SecurityReviewAgentTest`, `ReleaseAgentTest`.
- `MetricsCalculatorTest` (hand-built runs with known timings).
- `JsonSchemasTest`, `JsonCodecTest` (canonical hash stable across key order).
- `CoreArchitectureTest` (ArchUnit): no `org.springframework` dependency anywhere in core; `engine`
  does not depend on `agent.deterministic`; `model` and `artifact` depend only on `util`/`json`;
  `evaluation` does not depend on concrete agents and nothing else depends on `evaluation`; `policy.pack`
  depends only on the policy layer and the domain.

### 5.13 Evaluation harness

`evaluation/ArtifactScorers` holds one deterministic scorer per artifact kind; each returns `Score(value 0..1,
List<Criterion(name, weight, value, rationale)>)`, the weighted mean of criteria computed from the artifacts (and the
codebase index for REPO runs; criteria that do not apply get weight 0). Examples: requirement spec (acceptance
criteria per FR, measurable NFRs, ambiguity resolved), task plan (FR coverage, dependency order, impact analysis
first in brownfield, human release gate), impact (consistent with the workspace, indexed paths), design (risk with
factors, decisions with rationale, NFR strategies, known types in REPO runs), change set (content completeness with
skeletons at 0.25, tests per production file, parseable diffs on indexed files), test plan (FR coverage, negative
cases), verification (blocking checks, traceability, advisory count, build), documentation (sections, API coverage,
rollback in the runbook), security review (no HIGH/CRITICAL finding, STRIDE coverage, treated threats, dependency
inventory), release readiness (decision consistent with checklist, verification, security and skeleton changes).
`RunEvaluator.evaluate(run[, index])` is pure: it scores the accepted artifact of each stage (or the latest one under
review, noted) and returns `EvaluationReport(runId, stageScores, overall = mean, notes)` with `markdown()`.
`EvaluationSuite` (bundled `evaluation/default-suite.json`, 10 cases: greenfield GUIDED and FULL_AUTO, brownfield
REPO, clarification answered and unanswered, two policy denials, STRICT, a HIGH-risk security change, a NO_GO
release) is run by `EvaluationSuiteRunner.run(engineFactory, suite)`, which drives each case through a fresh engine
(answers clarifications with the first suggested answers and approves gates as the human operator identity
`evaluation-runner` with comment "auto-approved by the evaluation runner", never waives a NO_GO release, stops at the
first human gate when `expectCompleted` is false) and checks `expectClarification`, `expectPolicyDenial`,
`expectCompleted`, `expectedRiskAtLeast`, `minOverallScore` and `expectedReleaseDecision`. The deterministic agents
pass the bundled suite. Scores measure structural quality, not the correctness of generated code.

---

## 6. `orchestrator-app` module

Root package `com.agenticsdlc.orchestrator.app`.

### 6.1 Package layout

```
com.agenticsdlc.orchestrator.app
  OrchestratorApplication
  config/        OrchestratorProperties (@ConfigurationProperties "sdlc"), EngineConfiguration (beans: WorkflowGraph,
                 AgentRegistry, PolicyEngine, OrchestrationEngine, CodebaseIndex, JsonCodec), ClockConfig, OpenApiConfig, WebConfig
  api/           RunController, ScenarioController, MetricsController, WorkflowController, WorkspaceController,
                 ProviderController, dto/* (RunView, StageView, ApprovalView, PendingActionView, GraphView, ArtifactSummaryView,
                 ArtifactView, AuditEventView, LineageView, ReliabilityMetricsView, ScenarioView, ProviderView, requests),
                 RunViewMapper, ApiExceptionHandler (Problem Details), OperatorIdentityResolver (X-Operator-Id)
  persistence/   RunEntity, AuditEventEntity, ArtifactEntity, Spring Data repositories, JpaRunStore, JpaAuditSink
  llm/           AnthropicLlmClient, OllamaLlmClient, LlmProperties, LlmClientsConfiguration
  scenario/      ScenarioCatalog, ScenarioDefinition
  metrics/       MicrometerMetricsRecorder
  security/      ApiKeyAuthFilter
  startup/       StartupRecovery (calls engine.recoverInterrupted()), WorkspaceIndexWarmup
  resources/     application.yml, db/migration/V1__orchestrator_schema.sql, static/ (console)
```

### 6.2 REST API (`/api/v1`)

All mutating governance endpoints require header `X-Operator-Id` (1..64 chars, `[A-Za-z0-9._@-]+`);
missing -> `400` Problem Detail `operator identity required`. When `sdlc.security.api-key` is set, all
`/api/**` requests require `X-Api-Key` to match (else `401`); when empty the filter logs a WARN once
at startup ("API authentication disabled: local development mode").

| Method | Path | Body / params | Response |
|---|---|---|---|
| POST | `/api/v1/runs` | `CreateRunRequest { requirement (10..4000 chars), scenario? (default "custom"), autonomy? (default GUIDED), provider? , faults?: [{stageId, failTimes, kind}], workspace? (REPO default; NONE), budget?: {maxFeedbackLoops?, maxReplans?, maxAgentCalls?, maxLlmTokens?} }` | `201` + `Location` + `RunView` |
| GET | `/api/v1/runs` | - | `List<RunSummaryView { id, scenario, status, haltReason, requirement (truncated 120), planVersion, createdAt, updatedAt, pendingActions (count), progress { succeeded, total } }>` newest first |
| GET | `/api/v1/runs/{id}` | - | `RunView` (section 6.3) |
| GET | `/api/v1/runs/{id}/graph` | - | `GraphView { nodes: [{ id, name, status, layer, role, activeAgent, attempts, approval: ApprovalView?, artifactVersion }], edges: [{from, to}] }` |
| GET | `/api/v1/runs/{id}/artifacts` | - | `List<ArtifactSummaryView { id, stageId, kind, version, hash, accepted (boolean), createdAt, provenance }>` |
| GET | `/api/v1/runs/{id}/artifacts/{stageId}` | `version?` (default accepted) | `ArtifactView { summary fields + payload (JSON object) + markdown }` |
| GET | `/api/v1/runs/{id}/audit` | `afterSeq?` (default 0), `limit?` (default 500) | `List<AuditEventView { seq, timestamp, stageId, type, actor: {type, id}, message, data }>` |
| GET | `/api/v1/runs/{id}/lineage` | - | `LineageView { nodes: [{ artifactId, stageId, kind, version, hash, accepted }], edges: [{ from, to }] }` where edges point from an upstream artifact to the artifact derived from it |
| POST | `/api/v1/runs/{id}/stages/{stageId}/approve` | `{ comment? }` | `200 RunView` |
| POST | `/api/v1/runs/{id}/stages/{stageId}/reject` | `{ reason (required), disposition: REWORK or ABORT }` | `200 RunView` |
| POST | `/api/v1/runs/{id}/clarify` | `{ answers: { "q1": "..." } }` (every open question must be answered) | `200 RunView` |
| POST | `/api/v1/runs/{id}/replan` | `{ requirement }` | `200 RunView` |
| POST | `/api/v1/runs/{id}/stop` | `{ reason? }` | `200 RunView` |
| POST | `/api/v1/runs/{id}/resume` | `{}` | `200 RunView` |
| GET | `/api/v1/metrics/reliability` | - | `ReliabilityMetricsView` (mirrors `ReliabilityMetrics`) |
| GET | `/api/v1/workflows/default` | - | `WorkflowView { id, version, stages: [{ id, name, role, dependsOn, entryApproval, exitApproval, maxAttempts, timeoutSeconds, fallbackToDeterministic }], layers: [[stageId]] }` |
| GET | `/api/v1/scenarios` | - | `List<ScenarioView { key, title, kind (GREENFIELD|BROWNFIELD|AMBIGUOUS|POLICY_DENIAL|FAULT_RETRY|FAULT_FALLBACK|REPLAN), description, requirement, autonomy, workspace, faults, walkthrough: [String] }>` |
| POST | `/api/v1/scenarios/{key}/run` | `provider?` query | `201 RunView` |
| GET | `/api/v1/workspace/index` | - | `WorkspaceIndexView { root, indexedAt, counts: {module -> {kind -> n}}, types: [{ fqcn, kind, path, module, endpoints: [{method, path}], references: n }], endpoints: [...], migrations: [...] }` |
| POST | `/api/v1/workspace/reindex` | - | `200 WorkspaceIndexView` |
| GET | `/api/v1/providers` | - | `List<ProviderView { id, available, model, detail }>` |

Error mapping (`ApiExceptionHandler`): validation -> `400`; `RunNotFoundException` -> `404`;
`IllegalStateException`/`InvalidCommandException` -> `409` with the engine message; everything else ->
`500` without internals. All as `application/problem+json`.

### 6.3 `RunView`

```json
{
  "id": "…", "scenario": "greenfield", "status": "WAITING_HUMAN", "haltReason": null, "haltDetail": null,
  "requirement": "…", "activeRequirement": "…", "autonomy": "GUIDED", "provider": "deterministic", "workspace": "NONE",
  "planVersion": 1, "createdAt": "…", "startedAt": "…", "updatedAt": "…", "endedAt": null,
  "stages": [ { "id": "intake", "name": "Requirement understanding", "role": "REQUIREMENTS", "dependsOn": [],
                "status": "SUCCEEDED", "attempts": 1, "activeAgent": "deterministic", "startedAt": "…", "endedAt": "…",
                "durationMs": 12, "lastError": null, "artifactVersion": 1, "artifactKind": "REQUIREMENT_SPEC",
                "approval": null, "feedback": [], "nextAttemptAt": null } ],
  "pendingActions": [ { "type": "APPROVAL", "stageId": "design", "gateKind": "EXIT", "reason": "GOV-001: risk MEDIUM (public API change)", "requestedAt": "…" },
                      { "type": "CLARIFICATION", "stageId": "intake", "questions": [ { "id": "q1", "question": "…", "why": "…", "suggestedAnswers": ["…"] } ] } ],
  "clarification": { "questions": [...], "answers": { "q1": "…" } },
  "budget": { "maxFeedbackLoops": 2, "maxReplans": 3, "maxAgentCalls": 40, "maxLlmTokens": 200000, "agentCallsUsed": 7, "llmTokensUsed": 0 },
  "counters": { "retries": 0, "fallbacks": 0, "rollbacks": 0, "replans": 0, "feedbackLoops": 0, "approvalsRequested": 1, "approvalsGranted": 0, "approvalsRejected": 0, "approvalsRevoked": 0 },
  "timing": { "endToEndMs": null, "activeMs": 1234, "humanWaitMs": 5000, "firstFailureAt": null, "recoveredAt": null, "mttrMs": null },
  "risk": { "level": "MEDIUM", "factors": ["public API change"] },
  "policyFindings": [ { "ruleId": "GOV-001", "severity": "MEDIUM", "verdict": "REQUIRE_APPROVAL", "message": "…", "stageId": "design", "timestamp": "…" } ]
}
```

`ApprovalView { kind (ENTRY|EXIT), status (PENDING|APPROVED|REJECTED|REVOKED), requestedAt, decidedAt, decidedBy, comment, reason }`.

### 6.4 Persistence

Flyway `V1__orchestrator_schema.sql`:

```
runs (id VARCHAR(64) PK, scenario VARCHAR(64), status VARCHAR(32), halt_reason VARCHAR(64) NULL, requirement VARCHAR(4000),
      plan_version INT, created_at TIMESTAMP, updated_at TIMESTAMP, ended_at TIMESTAMP NULL, snapshot_json CLOB NOT NULL, version BIGINT NOT NULL)
audit_events (id BIGINT identity PK, run_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL, ts TIMESTAMP NOT NULL, stage_id VARCHAR(64) NULL,
      type VARCHAR(64) NOT NULL, actor_type VARCHAR(16) NOT NULL, actor_id VARCHAR(128) NOT NULL, message VARCHAR(2000) NOT NULL, data_json CLOB NULL)
      unique (run_id, seq), index (run_id)
artifacts (id VARCHAR(64) PK, run_id VARCHAR(64) NOT NULL, stage_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL, version INT NOT NULL,
      hash VARCHAR(64) NOT NULL, payload_json CLOB NOT NULL, provenance_json CLOB NOT NULL, created_at TIMESTAMP NOT NULL)
      unique (run_id, stage_id, version), index (run_id)
```

`JpaRunStore.save` upserts `runs` (optimistic `@Version`) and inserts any artifacts not yet persisted
(by id). `JpaAuditSink.append` batch-inserts. `load`/`loadAll` deserialize `snapshot_json` via
`JsonCodec`. Datasource `jdbc:h2:file:./data/orchestrator;AUTO_SERVER=TRUE`; tests use
`jdbc:h2:mem:orchestrator-test;DB_CLOSE_DELAY=-1`.

### 6.5 LLM clients

`LlmProperties` (`sdlc.llm.*`):

```yaml
sdlc:
  agents:
    default-provider: deterministic         # deterministic | anthropic | ollama
  llm:
    anthropic: { enabled: true, model: claude-opus-5-5, api-key: ${ANTHROPIC_API_KEY:}, max-output-tokens: 16000, timeout: 180s }
    ollama:    { enabled: true, base-url: http://localhost:11434, model: qwen2.5-coder:7b, timeout: 240s }
```

`AnthropicLlmClient` (SDK `com.anthropic:anthropic-java` 3.3.0):
- Build the client lazily: `AnthropicOkHttpClient.builder().apiKey(key).timeout(timeout).build()`.
  `isAvailable()` = key non-blank (no network call).
- Request: `MessageCreateParams.builder().model(model).maxTokens(maxOutputTokens).system(systemPrompt)
  .addUserMessage(userPrompt).outputConfig(outputType)` (typed structured output; returns
  `StructuredMessageCreateParams<T>`), `client.messages().create(params)`; iterate `content()`,
  `block.text()` (Optional) -> `.text()` yields `T`. Check `stopReason()`: `REFUSAL` -> `LlmException`
  non-retryable with message "provider refused"; `MAX_TOKENS` -> retryable. Record `usage().inputTokens()`
  / `outputTokens()`.
- Error mapping: `RateLimitException`, `InternalServerException`, `AnthropicIoException`, and
  `AnthropicServiceException` with status >= 500 or 408/409/429 -> retryable; other
  `AnthropicServiceException` -> non-retryable. Never log the key.
- Do not set `thinking` (Opus 5.5 is adaptive by default). Do not set temperature. Effort is left at
  the model default; expose `sdlc.llm.anthropic.effort` only if the SDK exposes a compatible builder
  method together with the typed output config (check with `javap`; otherwise omit and document).

`OllamaLlmClient` (Spring `RestClient` from the auto-configured `RestClient.Builder`):
`POST {base-url}/api/chat` with `{ "model", "messages": [{"role":"system","content"},{"role":"user","content"}],
"stream": false, "format": <JsonSchemas.forRecord(outputType)>, "options": {"temperature": 0, "num_ctx": 16384} }`;
parse `message.content` as JSON into `T` with the Jackson 3 `JsonMapper`; `prompt_eval_count`/`eval_count`
as usage. `isAvailable()` = `GET /api/tags` returns 200 within 2 s (cached 30 s). Connection errors and
5xx are retryable; 4xx are not.

`LlmClientsConfiguration` builds the `AgentRegistry`: deterministic agents always; for each enabled
LLM client an `LlmStageAgent` per role. `ProviderController` reports availability and detail (e.g.
"ANTHROPIC_API_KEY not set").

### 6.6 Scenario catalog (`ScenarioCatalog`)

| key | kind | requirement (verbatim) | autonomy | workspace | faults |
|---|---|---|---|---|---|
| `greenfield` | GREENFIELD | "Build a URL shortener service: create short links for HTTP and HTTPS URLs with optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per day, top referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 requests per second. Links default to no expiry. The service must prevent redirects to private networks." | GUIDED | NONE | - |
| `brownfield` | BROWNFIELD | "Add a bulk link creation API to the existing URL shortener: accept up to 100 URLs per request, return per-item results with partial-failure semantics, reuse the existing URL validation and custom alias rules, and respect the existing per-client rate limit. Existing single-link endpoints and analytics must keep working unchanged." | GUIDED | REPO | - |
| `ambiguous` | AMBIGUOUS | "Make the shortener faster and more reliable." | GUIDED | REPO | - |
| `policy-denial` | POLICY_DENIAL | "Remove URL validation so marketing can shorten any destination including internal IP addresses, and store the full client IP address with every click for attribution." | GUIDED | REPO | - |
| `fault-retry` | FAULT_RETRY | greenfield requirement | FULL_AUTO | NONE | implement: failTimes 2, TRANSIENT |
| `fault-fallback` | FAULT_FALLBACK | greenfield requirement | FULL_AUTO | NONE | test-design: failTimes 99, TRANSIENT |
| `replan` | REPLAN | greenfield requirement; walkthrough tells the operator to approve design, then call replan with "... and support custom aliases" | GUIDED | NONE | - |

Each scenario has a `walkthrough` list of human-readable steps the console shows.

### 6.7 Other app concerns

- `OrchestratorProperties` (`sdlc.*`): `workspace.root` (default: repository root detected by walking
  up from the working directory until a directory containing `pom.xml` and `url-shortener` is found,
  else the working directory), `engine.ambiguity-threshold=0.5`, `engine.sandbox-enabled=false`,
  `engine.verify-build-enabled=false`, `engine.backoff-scale=1.0`, `security.api-key=` (empty),
  `console.enabled=true`.
- `MicrometerMetricsRecorder` registers: `sdlc.stage.duration` (timer; tags stage, status),
  `sdlc.stage.retries`, `sdlc.stage.fallbacks`, `sdlc.run.rollbacks`, `sdlc.run.completed` (timer),
  `sdlc.agent.call` (timer; tags provider, role), `sdlc.llm.tokens` (counter; tags provider, direction).
- `StartupRecovery` (`ApplicationRunner`): load runs, call `engine.recoverInterrupted()`.
- Actuator exposes `health,info,metrics`. `/actuator/health` includes a custom `workspaceIndex`
  health indicator (UP when the index has > 0 types or workspace mode is NONE).
- Console static files served from `classpath:/static/` at `/`.

### 6.8 App tests (minimum)

- `RunControllerTest` (`@WebMvcTest(RunController.class)` + `@MockitoBean OrchestrationEngine`):
  create validates body, missing `X-Operator-Id` on approve -> 400 Problem Detail, 404 mapping, 409
  mapping.
- `OrchestratorEndToEndTest` (`@SpringBootTest` + `@AutoConfigureMockMvc`, H2 in-memory, backoff scale
  0): run the `greenfield` scenario through the API: expect `WAITING_HUMAN` with a design approval
  pending; approve; expect release approval pending (Awaitility up to 30 s); approve; expect
  `COMPLETED`; audit contains `RUN_COMPLETED`; `GET /artifacts` has 10 accepted artifacts; lineage
  edges non-empty; metrics endpoint reports 1 completed run. Also: `ambiguous` scenario pauses for
  clarification and continues after `clarify`; `policy-denial` scenario is `SAFE_STOPPED` with
  `POLICY_DENIED` and `resume` returns 409; `fault-retry` completes with `counters.retries == 2`.
- `JpaRunStoreTest` (`@DataJpaTest` + Flyway): save/load round trip preserves stages, artifacts and
  approvals; `JpaAuditSinkTest`.
- `OllamaLlmClientTest` (`@RestClientTest` + `MockRestServiceServer`): maps a successful chat response
  to the typed record, maps 500 to retryable `LlmException`.
- `AnthropicLlmClientTest`: unit test for error classification and availability (no network); a live
  test `AnthropicLlmClientLiveTest` annotated `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY",
  matches = ".+")` that asks for a tiny `RequirementSpec`.
- `ScenarioCatalogTest`, `AppArchitectureTest` (ArchUnit: `api` does not depend on `persistence`;
  `persistence` does not depend on `api`; core engine is only used via `OrchestrationEngine`).

---

## 7. Operator console (static, no build step)

Files: `orchestrator-app/src/main/resources/static/index.html`, `app.js`, `styles.css`. Vanilla
ES2022, no frameworks, no CDN (must work offline). Polls `GET /api/v1/runs` every 3 s and the selected
run's `RunView`, `graph`, `audit?afterSeq=`, `artifacts` every 2 s. Operator id is entered once
(top-right) and stored in `localStorage` (`sdlc.operatorId`), sent as `X-Operator-Id`.

Layout:
- Header: product name "SDLC Orchestrator", provider badge(s) from `/api/v1/providers`, operator id
  field, link to `/swagger-ui.html` and `/actuator/health`.
- Left column (320 px): "New run" panel (scenario select populated from `/api/v1/scenarios` with the
  walkthrough shown under it, requirement textarea prefilled from the scenario, autonomy select,
  provider select, workspace select, optional fault row) and the runs list (status pill, scenario,
  progress "7/10", pending-action badge).
- Main area, selected run: status banner (status, halt reason, plan version, timing, budget usage);
  **Pending actions** card with Approve / Reject (reason + disposition) / Clarify (one input per
  question) / Replan (textarea) / Stop / Resume buttons; **Pipeline graph**: SVG rendered from
  `GraphView.layers` (nodes as rounded rectangles colored by status, edges as curves, join nodes
  visibly fed by multiple edges, approval lock icon on gated stages, STALE shown hatched); tabs:
  **Artifacts** (list by stage with version chips; selecting shows the `markdown` rendered with a tiny
  safe Markdown renderer for headings, lists, tables, code, bold, and the raw JSON in a collapsible
  `<pre>`), **Audit** (reverse-chronological timeline, filter by type, auto-follow), **Lineage**
  (SVG graph of artifact versions with edges), **Metrics** (reliability metrics tiles from
  `/api/v1/metrics/reliability`, auto-refresh 5 s), **Policy** (findings table).
- Status colors: PENDING grey, RUNNING blue (animated border), RETRY_SCHEDULED amber, AWAITING_* purple,
  SUCCEEDED green, FAILED red, STALE striped grey, CANCELLED dark grey.
- Accessibility: keyboard-focusable buttons, `aria-live` region for status changes, prefers-color-scheme
  dark mode supported through CSS variables.
- All `fetch` errors show a dismissible toast with the Problem Detail `detail`.
- HTML is escaped before insertion (never `innerHTML` with unescaped server strings; the Markdown
  renderer escapes first, then applies formatting).

---

## 8. Scenarios (documentation + executable)

Each scenario in `docs/SCENARIOS.md` is described in the same structure: requirement, decomposition
(what intake/decompose produce), orchestration (which gates fire, which branches run in parallel,
what humans do), validation (verify/security/release outcomes), and the expected final state. The
seven catalog scenarios in 6.6 are the executable counterparts. The demo scripts run `greenfield`
end-to-end with approvals and print the metrics.

---

## 9. Testing approach and CI

- Unit tests isolate algorithms (ambiguity scoring, planning, impact analysis, policy rules, metrics).
- Engine tests use fake agents and fault injection to prove retries, fallback, rollback, re-planning,
  parallel execution and joins without timing flakiness (latches and Awaitility, backoff scale 0).
- Slice tests validate web and persistence layers with Spring's test slices.
- End-to-end tests drive the real control plane through MockMvc with H2.
- ArchUnit tests enforce module and layer boundaries (architecture as code).
- Live LLM tests are opt-in via environment variables and excluded from CI.
- CI runs `./mvnw -B -ntp verify` on Java 25 and builds both container images.

---

## 10. Documentation deliverables

Written after implementation so that every statement is true of the code: `README.md` (what, why,
quick start, demo, repository map), `docs/ARCHITECTURE.md`, `docs/ORCHESTRATION.md`,
`docs/SCENARIOS.md`, `docs/TESTING.md`, `docs/RISKS_AND_TRADEOFFS.md`, `docs/ENGINEERING_SUMMARY.md`,
`docs/AI_ASSISTANCE.md`, `docs/api/*.openapi.yaml`, `docs/adr/*.md` (ten ADRs listed in section 11).

---

## 11. Architecture decisions (ADR index)

1. Separate control plane from workload; single repository for codebase reasoning.
2. Deterministic agents are the default and the fallback; LLM providers are optional adapters.
3. Static lifecycle DAG with dynamic invalidation (re-planning) instead of a fully generated graph.
4. Saga-style compensations plus checkpoint restore for rollback; rollback always ends in safe-stop.
5. Risk-based human approvals driven by autonomy level and policy, with a non-removable release gate.
6. Aggregate-snapshot persistence (JSON) plus append-only audit and artifact tables.
7. Virtual threads for stage execution; per-run lock for state transitions.
8. Shortener: 302 redirects, random Base62 codes, salted IP hashing, no DNS-time SSRF check.
9. Prototype security: API-key filter and mandatory operator identity; production path is SSO/OIDC.
10. Java 25 and Spring Boot 4.1 as the baseline.

---

## 12. Out of scope / known limitations (to be echoed in docs)

- Single-node engine (in-memory locks); multi-node needs a distributed lock and a work queue. Core now
  provides the lease half (`RunLeaseProvider`, section 5.5); there is still no work queue: a run executes on
  the node that scheduled its work, and a run of a crashed node is recovered (`recoverInterrupted` or
  stop/resume) only after its lease expires.
- Deterministic agents produce skeleton code, not compilable features; LLM mode produces full
  content but is not guaranteed to compile; the optional sandbox build is the validation path.
- No DNS-resolution SSRF check in the shortener; no distributed rate limiter; no authentication on
  the shortener API (documented production path).
- Operator identity is asserted by header, not authenticated (prototype).
- Console polls; no server push.
