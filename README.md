<h1 align="center">Agentic SDLC Orchestrator · URL Shortener</h1>

<p align="center">
A control plane that takes a software requirement through a governed, ten-stage delivery pipeline
(agents do the work, policy and people decide what is accepted), demonstrated on a production-grade URL shortener.
</p>

<p align="center">
  <a href="https://github.com/gaddagada/agentic-sdlc-url-shortener/actions/workflows/ci.yml"><img src="https://github.com/gaddagada/agentic-sdlc-url-shortener/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
  <img src="https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1.2">
  <img src="https://img.shields.io/badge/tests-768-brightgreen" alt="768 tests">
</p>

**What this is**

- **An orchestration control plane** (`orchestrator-core` + `orchestrator-app`) that turns a free-text requirement
  into a reviewable engineering outcome: normalized requirements, a task plan, codebase impact, a design, a change
  set, tests, verification, documentation, a security review and a release decision, each a typed, hashed and
  versioned artifact with provenance, behind policy gates, human approvals, budgets and an append-only audit log.
- **A production-grade workload** (`url-shortener`): a Spring Boot service with SSRF-guarded link creation,
  302 redirects, privacy-preserving click analytics, idempotency, rate limiting and a PostgreSQL profile. It is
  also the real codebase the orchestrator reasons over in brownfield runs.
- **Runnable scenarios**: the three required scenarios (greenfield, brownfield, ambiguous) plus policy denial,
  retry, fallback and re-planning, from the console, a demo script or the REST API. Deterministic agents need no
  API key; Claude (Anthropic) and Ollama are optional providers.

---

## Architecture at a glance

<p align="center"><img src="docs/images/architecture.svg" width="100%" alt="System architecture: the operator console and REST API of the control plane drive the framework-independent orchestrator-core engine, which calls deterministic, Anthropic or Ollama stage agents, persists run snapshots, artifacts and audit events, and reads the url-shortener source tree for codebase reasoning."></p>

- **Two planes, three Maven modules, one repository.** `url-shortener` (port 8080) is the workload and does not
  depend on the orchestrator. `orchestrator-core` is a framework-free Java library (no Spring, enforced by ArchUnit)
  holding the engine, agents, policy rules and metrics. `orchestrator-app` (port 8090) is the Spring Boot control
  plane: REST API, operator console, JPA persistence, LLM adapters and the scenario catalog.
- **Agents propose, the engine decides.** Agents receive an immutable context and return a typed result; only the
  engine changes run state, evaluates gates, schedules successors and records decisions.
- **One repository on purpose:** the control plane indexes the `url-shortener` sources from disk (371 types,
  26 endpoints and 2 migrations across the repository at startup), so brownfield impact analysis is real.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## The orchestration model

<p align="center"><img src="docs/images/pipeline.svg" width="100%" alt="The ten-stage SDLC pipeline: intake, then decompose and impact in parallel, design, then implement and test-design in parallel, verify, document and security-scan, release-readiness; gates at intake (clarification), design and implement (risk-based) and release (always); a bounded feedback loop from verify to implement."></p>

The lifecycle is a validated, versioned DAG of ten stages. `intake` forks into `decompose` and `impact`,
`design` into `implement` and `test-design`, `implement` into `document` and `security-scan`; `design`, `verify`
and `release-readiness` are joins that start only when every predecessor has succeeded. Each stage declares its
retry policy (3 attempts, 2 for `verify`, `security-scan` and `release-readiness`; backoff 2 s then 4 s), a
timeout (3 min, 5 min for `implement`, 10 min for `verify`) and a deterministic fallback. Non-linear execution is
part of the model: a failed verification feeds back to `implement` (at most 2 loops), a rejected approval reworks
the stage, and a re-plan invalidates downstream work and revokes approvals that no longer apply.

<p align="center"><img src="docs/images/governance-loop.svg" width="100%" alt="Controlled autonomy loop: requirement, policy screen at run start, stage agent bounded by retries and budgets, artifact validation, exit gate, optional human approval, acceptance into context with hash and provenance; denials and exhausted failures lead to rollback and safe-stop."></p>

Every requirement is screened by policy before any agent runs. Every stage output must pass its typed invariants,
then the exit gate (policy rules, assessed risk and the run's autonomy level: `FULL_AUTO`, `GUIDED` or `STRICT`).
Only then is it accepted into the context of later stages, with a SHA-256 hash and provenance (agent, model,
prompt hash, tokens, latency and the exact upstream versions it was derived from). Failures end in a retry, a
fallback, or a rollback (compensations in reverse order plus checkpoint restore) followed by a **safe-stop** that
only a human can lift.

**What agents can never do:** approve or reject anything (governance commands require a `HUMAN` actor), change run
state or skip a gate, lower the risk the engine computes from their artifacts, leave side effects that a denial
would not roll back, or write outside their per-run sandbox; a sandbox build runs only Maven, without shell
metacharacters and without credential-like environment variables. The release gate cannot be configured away: it
is required by the `ALWAYS` exit gate, by rule CHG-002 and, in `FULL_AUTO`, by GOV-003.

**Budgets bound autonomy:** 40 agent calls, 600 000 LLM tokens, 2 h of active time, 2 feedback loops and
3 re-plans per run by default. A breach safe-stops the run; only an explicit human resume grants one more default
allowance, and the audit log records it.

Details: [docs/ORCHESTRATION.md](docs/ORCHESTRATION.md) (state machines, gate semantics, failure handling, lineage,
metrics definitions, each with the class and the test that proves it).

---

## Quick start

**Prerequisites:** JDK 25 (for example Eclipse Temurin 25) on the `PATH` or in `JAVA_HOME`. Maven is not needed:
the wrapper downloads Maven 3.9.16. Optional: Docker (PostgreSQL profile, container images), an
`ANTHROPIC_API_KEY`, a local [Ollama](https://ollama.com) with `qwen2.5-coder:7b`, and `curl` plus Python 3 for
`scripts/demo.sh`.

```bash
git clone https://github.com/gaddagada/agentic-sdlc-url-shortener.git
cd agentic-sdlc-url-shortener
./mvnw -B verify          # builds all three modules and runs all 768 tests (Windows: mvnw.cmd -B verify)
```

Start both applications from the repository root, in two terminals. The orchestrator finds the `url-shortener`
sources by walking up from its working directory, and both applications keep their H2 database files in `./data`.

```bash
java -jar url-shortener/target/url-shortener-1.0.0-SNAPSHOT.jar          # http://localhost:8080
java -jar orchestrator-app/target/orchestrator-app-1.0.0-SNAPSHOT.jar    # http://localhost:8090
```

| What | URL |
|---|---|
| Operator console | http://localhost:8090/ |
| Orchestrator API (Swagger UI) | http://localhost:8090/swagger-ui.html |
| Shortener API (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Health (both apps; the orchestrator adds a `workspaceIndex` check) | `/actuator/health` |

On Windows use `mvnw.cmd` instead of `./mvnw`; the `java -jar` commands are the same. `./mvnw -B package -DskipTests`
builds the jars without running the tests.

### Five-minute demo

**In the console** (http://localhost:8090/, enter an operator id at the top right; it is sent as `X-Operator-Id`):

1. Pick the **greenfield** scenario and press **Run scenario**. `intake` normalizes the requirement, then
   `decompose` and `impact` run in parallel.
2. `design` pauses: **Pending actions** shows the GOV-001 approval with the assessed risk factors. Open the
   *Technical design* artifact, then approve it.
3. `implement` and `test-design` run in parallel, `verify` joins them, `document` and `security-scan` run beside
   them. `release-readiness` pauses for the always-required release approval (CHG-002): review the change record
   and approve.
4. The run completes. Inspect the **Audit** tab (89 events in the run recorded for the docs), the **Lineage** tab (which artifact
   version was derived from which) and the **Metrics** tab.

<p align="center"><img src="docs/images/screenshots/console-overview.png" width="100%" alt="Operator console showing a completed brownfield run: status banner with timing and budget usage, run controls, the pipeline graph with all ten stages succeeded, and the rendered requirement specification."></p>

| Clarification before planning (ambiguous scenario) | Policy denial before any agent runs |
|---|---|
| <img src="docs/images/screenshots/console-clarification.png" alt="Ambiguous run waiting at intake with four clarification questions, each with suggested answers." width="100%"> | <img src="docs/images/screenshots/console-policy-denial.png" alt="Policy-denied run: halt banner naming SEC-001 and CMP-002, resume refused, re-plan offered." width="100%"> |

<p align="center"><img src="docs/images/screenshots/console-lineage.png" width="100%" alt="Lineage tab: each box is one artifact version; edges run from an input to the artifact derived from it; superseded versions are faded."></p>

**Scripted alternative.** The demo scripts start a catalog scenario through the REST API, answer clarification
questions with canned answers, approve every gate as operator `demo-operator` (auto-approval for demonstration,
not a review), then print the audit trail, the artifacts and the reliability metrics.

```bash
scripts/demo.sh ambiguous                                   # bash: needs curl and Python 3
PROVIDER=anthropic scripts/demo.sh brownfield               # with Claude; the server needs ANTHROPIC_API_KEY
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo.ps1 -Scenario fault-fallback
```

Deterministic runs finish in a few seconds plus the time humans take to approve.

---

## Scenarios

<p align="center"><img src="docs/images/scenarios.svg" width="100%" alt="The three required scenarios: greenfield (build the shortener, no workspace), brownfield (bulk link creation over the indexed source tree) and ambiguous (clarification before planning), each with its decomposition, orchestration and validation."></p>

| Key | What it demonstrates | Observed in live runs (deterministic agents, 2026-10-30) |
|---|---|---|
| `greenfield` | Build the shortener from scratch in `GUIDED` autonomy, no workspace | pauses at `design` (GOV-001, risk HIGH) and `release-readiness` (CHG-002); 10 agent calls, 89 audit events, decision `GO_WITH_CONDITIONS` |
| `brownfield` | Bulk link creation in the existing service; impact analysis over the indexed `url-shortener` source | `LinkController` and `LinkService` identified as the components to extend; their neighbours flagged as regression risks; same two approvals |
| `ambiguous` | "Make the shortener faster and more reliable." | ambiguity score 0.90: pauses at intake with 4 questions (with suggested answers); after the answers it is classified `BROWNFIELD` with ambiguity 0.00 |
| `policy-denial` | A requirement to remove URL validation and store full client IPs | created `SAFE_STOPPED` / `POLICY_DENIED` (SEC-001, CMP-002) before any agent runs; `resume` returns 409; a compliant re-plan proceeds on plan v2 |
| `fault-retry` | Injected transient fault on `implement` (`FULL_AUTO`) | two failures, retries after 2 s and 4 s, third attempt succeeds; `retries = 2`, MTTR about 6 s |
| `fault-fallback` | `test-design` fails on every attempt of its primary agent | 3 attempts, `FALLBACK_ENGAGED`, `fallback:deterministic` succeeds; `retries = 2`, `fallbacks = 1` |
| `replan` | Requirement change after the design approval | plan v2, all ten stages invalidated, design approval revoked, re-approval required; change record lists only plan-2 approvals |

Run any of them with **Run scenario** in the console, `scripts/demo.sh <key>`, or
`curl -X POST http://localhost:8090/api/v1/scenarios/<key>/run -H "X-Operator-Id: <you>"`. The `replan` scenario
needs the re-plan step from the console or the API (the scripts only approve). Walkthroughs with real artifact
excerpts, audit sequences and a Claude-backed run: [docs/SCENARIOS.md](docs/SCENARIOS.md).

---

## Validation, risk and reliability

<p align="center"><img src="docs/images/reliability-metrics.svg" width="100%" alt="Reliability metrics: success rate, retry rate, rollback frequency, MTTR, end-to-end versus active latency, approval decision time."></p>

- **Artifact validation.** Every payload record validates its own invariants (ids, references, DAG shape of the
  task plan, consistency of `passed` flags with the checks). Wrong kind, unparsable or invalid output is
  `INVALID_OUTPUT`: retried with the error in the next prompt, then the deterministic fallback.
- **Verification and security review as stages.** Seven structural checks (allowed paths, no secrets, tests per
  production file, migration for schema changes, designed endpoints implemented, content present, traceability of
  every functional requirement) plus an optional sandbox build; a STRIDE threat model with checks for input
  validation, SSRF, personal data, rate limiting and secrets. A failed review turns the release into `NO_GO`, which
  can only be approved with a written waiver.
- **Policy rules.** Eleven deterministic rules, evaluated at run start and at every entry and exit gate; a rule that
  throws fails closed.

| Rule | Purpose | Verdict |
|---|---|---|
| SEC-001 ProhibitedIntent | requirement asks to disable, remove, bypass or weaken a control ("skip tests", "turn off validation", ...) | DENY |
| SEC-002 SecretLeak | an artifact contains a credential (AWS, Anthropic, GitHub, Slack, JWT, PEM private key, literal password or generic credential assignment) | DENY |
| SEC-003 ProtectedPath | a change set touches `**/security/**`, `**/application-prod*.yml`, `.github/**`, `**/pom.xml`, the Maven wrapper (`mvnw*`, `.mvn/**`), the control-plane modules (`orchestrator-*/**`), Dockerfiles or compose files | approval |
| CMP-001 PiiHandling | a design declares personal data without protection or retention | approval |
| CMP-002 PiiProhibited | requirement asks to store raw IP addresses or clear-text e-mail, or personal data "without hashing" | DENY |
| CHG-001 ChangeSize | a change set has more than 25 files or 1500 lines | approval |
| CHG-002 ReleaseApproval | every release candidate | approval |
| CHG-003 NoGoRelease | a `NO_GO` release decision (approval needs a waiver comment) | approval |
| GOV-001 RiskBasedDesignApproval | design risk MEDIUM or higher in `GUIDED` (never lower than the engine's own risk floor), always in `STRICT` | approval |
| GOV-002 StrictEntryApproval | `STRICT` autonomy: approval before every stage after `intake` | approval |
| GOV-003 AutonomyFloor | `FULL_AUTO` cannot remove the release approval | approval |

- **Human approvals** carry the operator id, time and comment into the audit log and into the engine-written change
  record; approvals are bound to a plan version (and, at exit gates, to the hash of the artifact under review)
  and revoked when their inputs change.
- **Rollback and safe-stop.** Each stage activation takes a checkpoint; on unrecoverable failure or a policy denial
  the engine runs compensations in reverse order, restores the checkpoint and safe-stops with a halt reason
  (`OPERATOR_STOP`, `POLICY_DENIED`, `AGENT_FAILURE_EXHAUSTED`, `BUDGET_EXCEEDED`, `INTERRUPTED_BY_RESTART`,
  `ROLLBACK_FAILED`). A JVM killed mid-run comes back `SAFE_STOPPED` with `INTERRUPTED_BY_RESTART` and resumes on
  command. A denied artifact (for example one embedding a credential) is discarded and never persisted.
- **Metrics.** `GET /api/v1/metrics/reliability` reports success rate, retry rate, rollback frequency, incidents and
  MTTR, end-to-end, active and human-wait latency (p50, p95), agent-call latency, approval decision time and LLM
  token usage; Micrometer meters (`sdlc.*`) are exposed through Actuator.

Risk register, failure scenarios and trade-offs: [docs/RISKS_AND_TRADEOFFS.md](docs/RISKS_AND_TRADEOFFS.md).

---

## LLM providers

Every stage has a deterministic agent: the default provider and the fallback of every stage. LLM agents serve the
same roles, produce the same typed artifacts and pass the same validation and gates.

| Provider | Model | Needs | How it is called |
|---|---|---|---|
| `deterministic` (default) | rule-based | nothing | in-process, pure functions of the stage context |
| `anthropic` | `claude-opus-5-5` | `ANTHROPIC_API_KEY` in the orchestrator's environment | Anthropic Java SDK 3.3.0, Messages API with JSON structured outputs (the payload's JSON schema, enums included); streamed responses of up to 32 000 output tokens, 300 s request timeout, stage timeouts multiplied by 3 for LLM attempts; SDK retries off, the engine owns retries |
| `ollama` | `qwen2.5-coder:7b` | Ollama at `http://localhost:11434` (`ollama pull qwen2.5-coder:7b`) | `POST /api/chat` with the JSON schema as `format`, temperature 0, 16 384-token context, 240 s timeout |

- **Selecting a provider:** the **Provider** field of the console, `"provider"` in `POST /api/v1/runs`,
  `?provider=` on `POST /api/v1/scenarios/{key}/run`, or the server default
  (`--sdlc.agents.default-provider=anthropic`). `GET /api/v1/providers` reports availability; a run requested for
  an unavailable provider is refused with 409 instead of starting only to fall back.
- **Fallback:** a provider error is classified (rate limits, 5xx and timeouts retryable; refusals and other 4xx
  not), retried with backoff, and after the stage's attempts the deterministic agent takes over that stage only.
  Provenance then shows `fallback:deterministic`; usage, model and prompt hash are recorded for every LLM attempt.
- **Observed:** three live Claude-backed brownfield runs. Every analysis and design stage (requirements, task plan,
  code-grounded impact analysis, design with evaluated alternatives, test plan) succeeded on the first attempt in
  20 to 54 s each. The first run hit the then-default 200 000-token budget; the next two produced complete change
  sets (up to 51 000 streamed output tokens), had them rejected by the Claude verifier (the diffs were written
  without the source of the modified files in view), and safe-stopped after the bounded two feedback loops for a
  human decision instead of releasing unverified code. Ollama on a CPU took about 100 s for one intake call. See
  [docs/SCENARIOS.md](docs/SCENARIOS.md#the-same-scenario-with-an-llm-provider).

---

## The URL shortener service

- Random 7-character Base62 codes from `SecureRandom` (62^7, about 3.5 trillion), custom aliases
  (`[A-Za-z0-9_-]{4,32}`, reserved words rejected), optional `ttlSeconds` or `expiresAt` (at most 365 days).
- `302 Found` with `Cache-Control: no-store`, so expiry, deactivation and analytics stay effective; `410 Gone` for
  expired or disabled links.
- SSRF guard without DNS: http/https only, no userinfo, at most 2048 characters; rejects `localhost`, `.local`,
  `.internal`, and IP literals in loopback, private, link-local (including `169.254.169.254`), CGNAT, multicast,
  unspecified and reserved ranges, IPv6 unique-local, IPv4-mapped and NAT64 forms.
- `Idempotency-Key` with replay (`Idempotent-Replayed: true`); per-client token bucket on every `POST` under
  `/api/v1/links`, matched on the normalized path so encoded or `;`-parameter spellings cannot bypass it (20 per
  minute; `429` with `Retry-After`, `X-RateLimit-*` headers).
- Click recording off the redirect path on a bounded executor (8 threads, 10 000 queued; a saturated recorder drops
  and counts the click instead of slowing redirects); visitors stored only as a daily HMAC-SHA256 of the IP keyed by
  a secret (the placeholder key is refused at startup under the `postgres` and `prod` profiles); referrer host and
  user-agent family only; click events purged after 90 days by a scheduled retention job.
- Caffeine cache for redirect lookups, baseline security headers (`nosniff`, `no-referrer`, `DENY` framing),
  RFC 9457 Problem Details, Micrometer meters, OpenAPI contract test and ArchUnit layering rules.

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/links` | create a short link | 201 + `Location` | 400, 409 alias taken, 422 URL rejected, 429 |
| GET | `/api/v1/links/{code}` | link metadata | 200 | 404 |
| DELETE | `/api/v1/links/{code}` | deactivate (soft delete) | 204 | 404 |
| GET | `/api/v1/links/{code}/analytics?days=7` | clicks, unique visitors, per day, top referrers, user agents (`days` 1 to 90) | 200 | 400, 404 |
| GET | `/{code}` | redirect | 302 | 404, 410 |

```bash
# create (responses abridged from a live run)
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/docs/getting-started","ttlSeconds":86400}'
# 201  {"code":"uXJ4lcc","shortUrl":"http://localhost:8080/uXJ4lcc","status":"ACTIVE","expiresAt":"2026-10-31T06:51:55.410Z",...}

curl -si localhost:8080/uXJ4lcc                                    # 302, Location: https://example.com/docs/getting-started
curl -s 'localhost:8080/api/v1/links/uXJ4lcc/analytics?days=7'    # {"totalClicks":1,"uniqueVisitors":1,"topReferrers":[...],...}

curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
# 422  {"title":"URL not allowed","detail":"Target host '169.254.169.254' is a private, loopback or otherwise non-public address.",...}

# after 20 creation requests within a minute from one client:
# 429  Retry-After: 2   {"title":"Too many requests","detail":"Too many link creation requests from this client; retry after 2 seconds.",...}
```

**PostgreSQL and containers.** The default is a file-based H2 database. For PostgreSQL 17 (the `postgres` profile
requires a real visitor-hash key):

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=postgres SHORTENER_DB_USER=shortener SHORTENER_DB_PASSWORD=shortener \
  SHORTENER_ANALYTICS_IP_SALT="<a long random secret>" \
  java -jar url-shortener/target/url-shortener-1.0.0-SNAPSHOT.jar

# both applications in containers, bound to 127.0.0.1; the control plane API then requires X-Api-Key
SHORTENER_IP_SALT="<secret>" SDLC_SECURITY_API_KEY="<secret>" docker compose --profile apps up --build
```

With an API key set, the console reads it from the browser's local storage
(`localStorage.setItem("sdlc.apiKey", "<key>")`, then reload).

---

## Repository map

```
agentic-sdlc-url-shortener/
├── url-shortener/           workload service: api, service, domain, repository, config; Flyway V1__init.sql
├── orchestrator-core/       engine library, no Spring
│   └── ...orchestrator/core graph (DAG), engine (scheduler, gates, rollback, re-plan), agent (deterministic, llm),
│                            artifact (typed payloads, provenance), policy (11 rules), model, audit, metrics,
│                            workspace (codebase index, impact analysis, sandbox), json, port, util
├── orchestrator-app/        control plane: api, config, llm (Anthropic, Ollama), persistence (JPA), scenario,
│                            security, startup, metrics; static console in src/main/resources/static
├── docs/                    architecture, orchestration, scenarios, testing, risks, summary, ADRs, OpenAPI, images
├── scripts/                 demo.sh, demo.ps1
├── .github/workflows/ci.yml build, test and coverage on Java 25; container image build
├── docker-compose.yml       PostgreSQL 17; optional "apps" profile with both services
└── pom.xml, mvnw, mvnw.cmd  parent POM (Spring Boot 4.1.2, Java 25) and Maven wrapper
```

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | components, control flow of a run, data model, threads, persistence, providers, deployment, key decisions |
| [ORCHESTRATION.md](docs/ORCHESTRATION.md) | DAG, state machines, gates, policy, failure handling, re-planning, lineage, budgets, metrics |
| [SCENARIOS.md](docs/SCENARIOS.md) | the seven scenarios with live artifact excerpts, audit sequences and a Claude-backed run |
| [TESTING.md](docs/TESTING.md) | test strategy per module, what each suite proves, how to run, CI and coverage |
| [RISKS_AND_TRADEOFFS.md](docs/RISKS_AND_TRADEOFFS.md) | risk register, failure scenarios, trade-offs, guardrails, known limitations |
| [ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) | plan and rationale, deliverables map, decisions, assumptions, limitations, next steps |
| [Engineering specification](docs/design/engineering-spec.md) | the pre-implementation contract (the code is the source of truth) |
| [ADRs 0001 to 0010](docs/adr/) | architecture decision records |
| [orchestrator.openapi.yaml](docs/api/orchestrator.openapi.yaml), [url-shortener.openapi.yaml](docs/api/url-shortener.openapi.yaml) | committed API contracts, checked against the controllers by tests |
| [AI_ASSISTANCE.md](docs/AI_ASSISTANCE.md) | how AI assistance was used and what the engineer owned |

## Testing

| Module | Tests | Highlights |
|---|---|---|
| `url-shortener` | 278 | parameterized SSRF validator cases, MockMvc and JPA slices, end-to-end integration (create, redirect, click, analytics, 410, idempotency, 429), rate-limit bypass attempts over a real servlet container, OpenAPI contract, ArchUnit layering |
| `orchestrator-core` | 336 | engine behaviour with fake agents: latch-proven parallel branches, retries, fallback, rollback with compensations, re-plan, budgets, restart recovery, store failures; every policy rule; risk floor; deterministic agents; snapshot round trip |
| `orchestrator-app` | 154 | REST slices, scenarios end to end through MockMvc, approval binding after a re-plan, API-key bypass attempts over a real servlet container, atomic commit rollback, JPA store round trip and corrupt-row tolerance, Anthropic and Ollama clients against stubs; 2 skipped by default: the live Anthropic test (needs `ANTHROPIC_API_KEY`) and the opt-in OpenAPI regeneration |

`./mvnw -B verify` runs everything and writes JaCoCo reports to `<module>/target/site/jacoco/index.html`. CI runs
the same build on Java 25 and builds both container images. Details: [docs/TESTING.md](docs/TESTING.md).

## Known limitations and production path

| Limitation (prototype) | Production path |
|---|---|
| Single-node engine: per-run locks and the retry scheduler are in memory | distributed lock or work queue; snapshot and audit in one PostgreSQL transaction |
| Deterministic agents produce skeleton code (`TODO` markers), so releases end `GO_WITH_CONDITIONS`; LLM output is not guaranteed to compile | enable the sandbox build (`sdlc.engine.sandbox-enabled`, `verify-build-enabled`, off by default) inside an isolated, credential-free container |
| Operator identity is a header (`X-Operator-Id`); the API key filter is optional; the shortener API has no authentication | OIDC identity, role-based approvals, four-eyes rule; gateway authentication for the shortener |
| Policy rules and ambiguity scoring are keyword heuristics | dedicated classifiers and scanners next to the deterministic rules |
| No DNS-time SSRF check; in-memory per-node rate limiter | egress policy or resolve-time check; shared limiter |
| A failed `RunStore.commit` leaves the in-memory run ahead of the store | transactional outbox or single unit of work |
| Console polls; Ollama on a CPU is slow and may hit stage timeouts (then falls back) | server-sent events; GPU-backed or hosted model |

The full list with residual risks is in [docs/RISKS_AND_TRADEOFFS.md](docs/RISKS_AND_TRADEOFFS.md#6-known-limitations).

---

<p align="center">Designed and built by <strong>Gayathri Addagada</strong>.</p>
