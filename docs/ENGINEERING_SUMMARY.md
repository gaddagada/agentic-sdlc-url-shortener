# Engineering Summary

The project: *an agentic SDLC orchestration platform demonstrated on a production-grade URL shortener*. This
summary states the design goals, how the work was planned and decomposed, what was built and where, the decisions
that shape it, the risks and how they are controlled, the assumptions made, the limitations that remain, and what
we would do next.

## 1. Design goals

The project combines two things that are easy to conflate: an agentic system that performs software engineering,
and a URL shortener. It is built as **a control plane that governs the delivery of changes to a real service**,
with the URL shortener as that service. Three consequences follow.

1. **The orchestrator is the product; agents are workers inside it.** The hard problem in a regulated delivery
   setting is not generating text or code, it is deciding what may be accepted, by whom, with what evidence, and what
   happens when something fails. The engine therefore owns state, gates, approvals, budgets and recovery; agents only
   propose typed artifacts.
2. **The shortener must be real.** It is built to production conventions (validation, abuse controls, privacy,
   observability, contract tests) and doubles as the codebase that brownfield runs reason over, so "codebase
   reasoning" reads actual source rather than a fixture.
3. **Everything must run without secrets.** Anyone who clones the repository must be able to execute every
   scenario, and CI must prove every governance path, without an API key. LLMs are therefore optional providers
   behind the same contracts, with deterministic agents as default and fallback.

The eight platform capabilities were stated as testable design goals before design:

| # | Capability | Design goal |
|---|---|---|
| 1 | Requirement understanding | classify, extract prioritized and measurable requirements with acceptance criteria, score ambiguity, ask before planning when the score reaches a threshold |
| 2 | Task decomposition | a task DAG with dependencies, critical path and parallel groups, validated like any other artifact |
| 3 | Codebase reasoning | index the real source tree (types, endpoints, migrations); rank impacted components with stated reasons and regression risks |
| 4 | Workflow orchestration | an explicit stage DAG with parallel branches and joins, entry and exit gates, retries, fallback, rollback, safe-stop, re-planning, lineage and audit |
| 5 | Engineering output | typed artifacts for every stage (spec, plan, impact, design, change set, tests, verification, docs, security review, release decision) and a production-grade service |
| 6 | Validation and risk control | artifact invariants, verification and security review as stages, policy rules at every gate, a release checklist, a risk register |
| 7 | Controlled autonomy | autonomy levels, risk-based human approvals, a non-removable release gate, budgets, operator identity on every decision |
| 8 | Documented decisions | every decision recorded with its rationale, risks and limitations: this summary, the ADRs, the risk register and the engineering specification |

## 2. Plan and decomposition

**Modules.** Three Maven modules in one repository ([ADR 0001](adr/0001-separate-control-plane-from-workload.md)):
`url-shortener` (workload, port 8080), `orchestrator-core` (engine library without Spring, so its semantics are
testable in isolation and portable to another host), `orchestrator-app` (control plane, port 8090: REST API,
console, persistence, LLM adapters, scenario catalog).

**Phases.**

| Phase | Output | Exit criterion |
|---|---|---|
| 1. Goals and decisions | goal-to-design mapping, ten ADRs | every open question decided and recorded |
| 2. Specification | [engineering specification](design/engineering-spec.md): packages, state machines, contracts, policy rules, test obligations | written and reviewed before the modules were built |
| 3. Workload | `url-shortener` with tests and OpenAPI contract | `./mvnw verify` green; contract matches controllers |
| 4. Engine | `orchestrator-core`: graph, scheduler, gates, failure handling, re-planning, metrics | behaviour proven by engine tests with fake agents (latches, fault injection), independent of any real agent |
| 5. Agents | ten deterministic agents, the LLM stage agent and its SPI | full pipeline test with real agents; identical hashes for identical inputs |
| 6. Control plane | API, JPA persistence, recovery, providers, console, scenario catalog | all seven scenarios pass end to end through the API |
| 7. Hardening | fixes from a critical review: denied artifacts discarded, operator-attributed run creation, budget extension on resume, sandbox environment scrubbing, broader secret and policy patterns, enum-typed artifact fields, retention job, engine-computed risk floor, rate-limit path normalization | regression tests for every fix |
| 8. Documentation and demonstration | README, these documents, demo scripts, live runs and screenshots | every statement checked against the code or a live run |
| 9. Second pass from the live LLM runs | source-aware LLM stages, real diff application, blocking and advisory checks, a per-provider circuit breaker ([ADR 0011](adr/0011-source-aware-prompts-and-real-diff-application.md)); the bulk API for real, a rate limiter SPI with a Redis backend, an optional API key and an opt-in resolve-time SSRF check in the shortener ([ADR 0012](adr/0012-shortener-bulk-api-and-rate-limiter-spi.md)); a console walkthrough recording | 960 tests green (shortener 441, core 365, app 154), plus 15 Redis tests where Docker is available |
| 10. Third pass: production posture | a hash-chained audit trail and signed change records ([ADR 0013](adr/0013-hash-chained-audit-and-signed-change-records.md)); run leases and a bounded registry for several nodes ([ADR 0014](adr/0014-run-leases-and-bounded-registry-for-multi-node.md)); `open`, `api-key` and `oidc` modes with roles, four-eyes and a `prod` profile that refuses unsafe settings ([ADR 0015](adr/0015-authentication-modes-roles-and-production-posture.md)); declarative policy packs ([ADR 0016](adr/0016-declarative-policy-packs.md)); server-sent events and a paged run listing ([ADR 0017](adr/0017-server-sent-events-and-paged-listing.md)); a deterministic evaluation harness and an operator-priced LLM cost model ([ADR 0018](adr/0018-evaluation-harness-and-llm-cost-model.md)); a dependency inventory in the security review; prompt caching; OpenTelemetry observations; a PostgreSQL integration test; GitHub Releases on version tags; two rounds of adversarial review fixes in the core and the shortener and one in the control plane; a brownfield scenario that asks for a change the shortener does not have yet | every module green; the Redis and PostgreSQL integration tests in CI (counts in [TESTING.md](TESTING.md)) |

The stage graph mirrors this decomposition: understanding, planning and impact analysis feed a design that a human
approves at sufficient risk; implementation and test design run in parallel; verification, documentation and
security review gate a release decision that always needs a human.

## 3. Capabilities and where they are

| Capability | Location |
|---|---|
| Runnable prototype | `./mvnw -B verify`, then two `java -jar` commands ([README](../README.md#quick-start)); Dockerfiles and `docker-compose.yml` |
| Three scenarios (greenfield, brownfield, ambiguous) | [`ScenarioCatalog`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/scenario/ScenarioCatalog.java), runnable from the console, `scripts/demo.sh`, `scripts/demo.ps1` or the API; walkthroughs in [SCENARIOS.md](SCENARIOS.md) |
| Additional failure-path scenarios | `policy-denial`, `fault-retry`, `fault-fallback`, `replan` in the same catalog |
| 1. Requirement understanding | `intake` stage: [`RequirementsAgent`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/deterministic/RequirementsAgent.java), [`ClarificationGate`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/ClarificationGate.java), `RequirementSpec` |
| 2. Task decomposition | `decompose` stage: [`PlanningAgent`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/deterministic/PlanningAgent.java), `TaskPlan` (validated DAG, critical path, parallel groups) |
| 3. Codebase reasoning | `impact` stage: [`CodebaseIndexer`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/workspace/CodebaseIndexer.java), [`ImpactAnalyzer`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/workspace/ImpactAnalyzer.java), `GET /api/v1/workspace/index` |
| 4. Workflow orchestration | [`OrchestrationEngine`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngine.java) and the `engine` package; specified in [ORCHESTRATION.md](ORCHESTRATION.md) |
| 5. Engineering output | the ten artifact types in `orchestrator-core/.../artifact`; the [`url-shortener`](../url-shortener) service |
| 6. Validation and risk control | `verify`, `security-scan` and `release-readiness` stages; [`PolicyEngine`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/policy/PolicyEngine.java) and eleven rules; [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md) |
| 7. Controlled autonomy | [`GateEvaluator`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/GateEvaluator.java), [`AutonomyLevel`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/model/AutonomyLevel.java), [`RunBudget`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/model/RunBudget.java), the operator identity on every governance command (a verified token in `oidc` mode), roles and the four-eyes rule ([`ApiAuthorizationRules`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/security/ApiAuthorizationRules.java), [`FourEyesGuard`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/api/FourEyesGuard.java)) |
| 8. Documented decisions | this summary, [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md), the [engineering specification](design/engineering-spec.md) |
| Evidence and integrity | [`AuditChain`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/audit/AuditChain.java), [`ChangeRecordSigner`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/audit/ChangeRecordSigner.java), `GET /api/v1/runs/{id}/audit/integrity` |
| Policy as code | [`policy.pack`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/policy/pack/PolicyPack.java), the [example pack](../orchestrator-app/src/main/resources/policy/example-policy-pack.yaml), `GET /api/v1/policy` |
| Production posture and operation | [`SecurityPostureValidator`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/security/SecurityPostureValidator.java), the `prod` profile, [`JdbcRunLeaseProvider`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/persistence/JdbcRunLeaseProvider.java), [`EngineMaintenance`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/startup/EngineMaintenance.java), [`RunStreamHub`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/api/RunStreamHub.java), [`RunObservations`](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/metrics/RunObservations.java), [`release.yml`](../.github/workflows/release.yml) |
| Quality and cost measurement | [`RunEvaluator`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/evaluation/RunEvaluator.java), [`EvaluationSuiteRunner`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/evaluation/EvaluationSuiteRunner.java) with the ten-case suite, [`LlmPriceBook`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/metrics/LlmPriceBook.java) |
| Architecture and decisions | [ARCHITECTURE.md](ARCHITECTURE.md), [ADRs](adr/) |
| API contracts | [orchestrator.openapi.yaml](api/orchestrator.openapi.yaml), [url-shortener.openapi.yaml](api/url-shortener.openapi.yaml), each checked against its controllers by a test |
| Tests and CI | [TESTING.md](TESTING.md), [`ci.yml`](../.github/workflows/ci.yml) |

## 4. Key decisions

| Decision | Why | Record |
|---|---|---|
| Separate control plane and workload in one repository | clear blast radius; real source for brownfield analysis | [ADR 0001](adr/0001-separate-control-plane-from-workload.md) |
| Deterministic agents by default and as fallback; Claude and Ollama optional | reproducible governance in CI without keys; a working pipeline in a provider outage | [ADR 0002](adr/0002-deterministic-agents-default-llm-optional.md) |
| Static, reviewed lifecycle DAG; dynamism in run state | every gate is known before a run starts, which change control needs | [ADR 0003](adr/0003-static-lifecycle-dag-with-dynamic-invalidation.md) |
| Compensations plus checkpoint restore; rollback ends in a safe-stop | side effects are undone; humans own recovery | [ADR 0004](adr/0004-saga-rollback-and-safe-stop.md) |
| Risk-based approvals, three autonomy levels, release gate that cannot be removed | oversight proportional to risk without a bypass for releases | [ADR 0005](adr/0005-risk-based-approvals-and-autonomy-levels.md) |
| JSON snapshot plus append-only audit and artifact tables | simple restore and crash recovery; full audit trail | [ADR 0006](adr/0006-aggregate-snapshot-persistence-with-audit-and-artifact-tables.md) |
| Virtual threads per attempt, a lock per run | real parallel branches, serializable state transitions, no extra infrastructure | [ADR 0007](adr/0007-virtual-threads-and-per-run-locking.md) |
| 302 redirects, random codes, pseudonymous analytics, no DNS-time SSRF check by default | correct expiry and analytics, no enumeration, privacy by default | [ADR 0008](adr/0008-shortener-redirect-code-and-privacy-choices.md) |
| Mandatory operator identity, optional API key, SSO as the production path (superseded by ADR 0015 for the control plane) | attributable approvals now; authentication as a deployment decision | [ADR 0009](adr/0009-prototype-security-model.md) |
| Java 25 and Spring Boot 4.1.2 | current supported baseline with mature virtual threads | [ADR 0010](adr/0010-java-25-and-spring-boot-4.1-baseline.md) |
| Bounded source of the impacted files in LLM prompts, diffs applied for real, blocking and advisory checks, an engine-wide circuit per provider | live Claude runs failed at verification because diffs were written without the source; advisory findings should not cost a loop; an outage should not cost every stage its retries | [ADR 0011](adr/0011-source-aware-prompts-and-real-diff-application.md) |
| Bulk creation charged one token per item, a rate limiter SPI whose Redis backend fails open, an optional API key, an opt-in resolve-time SSRF check | the brownfield feature exists for real; horizontal scaling and abuse controls become configuration, with the costs stated | [ADR 0012](adr/0012-shortener-bulk-api-and-rate-limiter-spi.md) |
| A per-run hash chain over the audit events, HMAC-signed change records behind a signer SPI | a reviewer can check mechanically that the trail and the change record are what the engine wrote | [ADR 0013](adr/0013-hash-chained-audit-and-signed-change-records.md) |
| Run leases on the shared database and a bounded registry, no work queue | several nodes without new infrastructure; distribution and failover stay an explicit gap | [ADR 0014](adr/0014-run-leases-and-bounded-registry-for-multi-node.md) |
| Three authentication modes, three roles, four-eyes, a `prod` profile that refuses to start unsafe | approvals authenticated and separated in production, no configuration needed locally | [ADR 0015](adr/0015-authentication-modes-roles-and-production-posture.md) |
| Declarative policy packs with a closed condition set; CHG-002, GOV-003 and SEC-002 cannot be disabled | organization rules by configuration without weakening the guarantees | [ADR 0016](adr/0016-declarative-policy-packs.md) |
| Server-sent events fed after commit, polling as the fallback; a paged run listing from table columns | live supervision without polling load; a listing that does not read every snapshot | [ADR 0017](adr/0017-server-sent-events-and-paged-listing.md) |
| Deterministic artifact scorers and a ten-case suite; an LLM cost model with operator-configured prices only | quality and cost become measurable without a model grading a model or a price list going stale | [ADR 0018](adr/0018-evaluation-harness-and-llm-cost-model.md) |

Smaller decisions that matter in a review are recorded where the behaviour is specified: an artifact denied by a
policy gate is discarded rather than kept; more budget is granted only by an explicit human resume; the engine
computes a deterministic risk floor so an agent cannot under-report risk to skip an approval; and the requirement
text is re-checked by policy at the intake exit, because clarification answers can change it
([ORCHESTRATION.md](ORCHESTRATION.md)).

## 5. Risks, trade-offs and validation

The risk register ([RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)) lists twenty-eight risks with likelihood,
impact, the control in the code and the residual risk. The five that matter most for a production decision:

1. **Identity and keys outside the `prod` profile.** The `prod` profile authenticates (OIDC), checks roles, enforces
   four-eyes and requires a signing key, and refuses to start otherwise; the local default (`open`) and `api-key`
   mode still assert identity by header, and the signing key is a shared secret.
2. **Model influence.** In LLM mode a model shapes design and code and can report on its own verification; human
   design and release approvals are the control on content. The Claude-backed run in
   [SCENARIOS.md](SCENARIOS.md#the-same-scenario-with-an-llm-provider) shows a design that passed every structural
   check and still contained claims the codebase contradicts.
3. **Generated code on the host.** The optional sandbox build strips credential-like environment variables but is
   not an isolation boundary.
4. **Secret handling.** Detection and redaction are pattern-based; denied artifacts are discarded; the requirement
   reaches a provider before any secret check.
5. **Evidence and coordination.** The audit chain is tamper-evident only against a head hash kept elsewhere, which
   nothing exports yet; several nodes coordinate through time-based leases without a work queue.

Validation is layered so that no single check carries the weight: typed invariants on every artifact, policy at run
start and at every gate, structural verification and a STRIDE review (with a dependency inventory) as stages, a
release checklist, risk-based and mandatory human approvals, budgets, and a hash-chained audit log; a deterministic
evaluation harness scores the artifacts. The automated tests prove the behaviour, including every failure path the
documentation describes; their counts per module are in [TESTING.md](TESTING.md).

**Observed in live runs** ([SCENARIOS.md](SCENARIOS.md)): the deterministic greenfield run completes with
10 agent calls, 2 approvals and 89 audit events in about 0.1 s of active time; the ambiguous requirement is held
at intake with four targeted questions; the unsafe requirement is stopped before any agent runs and recovers only
through a compliant re-plan; injected faults recover through retries (MTTR about 6 s with the default backoff) or the
fallback; a re-plan revokes the earlier design approval and the change record lists only current approvals; a
Claude-backed run produced substantially richer artifacts and was safe-stopped by the token budget, and two
follow-up runs stopped at the verification gate because their diffs were written without the source, which the
second pass addresses (all three on the earlier bulk-API requirement); the deterministic campaign-label brownfield run over the committed tree ranks the link, bulk and analytics code
first, completes with two approvals and leaves an audit chain that verifies, while its design shows the kind of
schema over-reach the design gate exists to catch; of the two Claude-backed runs on that requirement, the fourth
stopped at verification because the LLM impact and design stages left the implementer without any source (fixed by
feeding the static impact analysis into those stages and into source selection), and the fifth wrote real diffs
against the entity, DTOs, service and repositories, passed verification on its second change set, was safe-stopped
by the 600 000-token budget two stages before the end and completed with a signed change record after an operator
resume (15 LLM calls, $6.08 at list prices).

## 6. Where to look for each quality attribute

| Quality attribute | Evidence in the repository |
|---|---|
| Effectiveness of agentic orchestration | a ten-stage DAG with real parallel branches and joins, gates, retries, fallback, rollback, re-planning and lineage ([ORCHESTRATION.md](ORCHESTRATION.md)); seven runnable scenarios, including failure paths ([SCENARIOS.md](SCENARIOS.md)) |
| Architecture quality | three modules with enforced boundaries (ArchUnit in every module), a framework-free engine behind ports, adapters for persistence and providers ([ARCHITECTURE.md](ARCHITECTURE.md)) |
| Depth of decomposition and execution | typed artifacts for ten stages, each with invariants; a task DAG with critical path; impact analysis over the real source; live excerpts of every stage in [SCENARIOS.md](SCENARIOS.md) |
| Realism of outputs | a working URL shortener with production controls; deterministic artifacts that are honest about being skeletons; a Claude-backed run quoted with its tokens, latencies, errors and the mistakes a reviewer had to catch |
| Validation and risk rigor | layered checks (section 5), a twenty-eight-entry risk register with residual risk stated per entry, failure scenarios each proven by a named test ([RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)), a ten-case evaluation suite |
| Clarity and defensibility of decisions | eighteen ADRs with context and consequences; trade-offs with the alternative considered and when to revisit; a table of where the code refines the specification |
| Engineering principles | tests before trust (no external services needed by default; the Docker-only integration tests run in CI), immutable artifacts with canonical hashing, durability before visibility, least privilege for agents and operators, explicit failure semantics, a production posture checked at startup |
| Engineering judgment | deterministic-first agents so governance is reproducible; a release gate that cannot be configured away; limitations stated next to the features they limit |

## 7. Assumptions

- The organization delivers through change control: a release means a recorded, approved release decision with a
  change record; deployment and git operations are outside the orchestrator (it has no push or deploy side effects).
- Operators are authenticated by the organization's OIDC identity provider in production (the `prod` profile); in
  the local default their identity is asserted by a header.
- Requirements arrive as English free text of up to 4 000 characters; the keyword heuristics are tuned for English.
- The URL shortener runs single-region; targets are public web URLs; analytics need approximate unique visitors per
  day, not identities.
- Runs are long-lived and dominated by human wait, so a few control-plane nodes on one database are enough; a run
  does not need to move between nodes without an operator.
- LLM providers are optional and may be unavailable; the deterministic path must always complete.
- Users have JDK 25; Docker, Ollama and an Anthropic key are optional.

## 8. Limitations

Stated plainly, with the production path in [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md#6-known-limitations):

- Several nodes share one database through run leases, but there is no work queue: a dead node's runs are
  safe-stopped once their lease expires and wait for an operator; retry timers, provider circuits and event streams
  are per node.
- Deterministic agents produce skeleton code, so every deterministic release is `GO_WITH_CONDITIONS` with "Code
  complete" waived; LLM output is not guaranteed to compile; the sandbox build that would prove it is off by default.
- Policy rules and ambiguity scoring are keyword heuristics, with documented false positives and negatives; the
  deterministic architecture agent can misread a requirement (the re-planning walkthrough shows one case).
- Shortener: the resolve-time SSRF check is opt-in and cannot stop DNS rebinding; the rate limiter is per node
  unless the Redis backend is chosen, which fails open; the management API key is optional and one shared secret;
  with the default bucket a bulk request carries at most 20 items.
- Control plane identity is authenticated only in `oidc` mode (the `prod` profile); the local default asserts it by
  header. OIDC accepts RS256 tokens only and the console has no login flow. Change records are signed with one shared
  HMAC key, and the audit chain is not anchored outside the database.
- LLM costs are estimates from operator-configured prices (none ship); the evaluation suite scores structure and
  consistency, not working code, and has been run with the deterministic agents only.
- Event streams carry only the serving node's commits (the console polls the rest); Ollama on a CPU is slow and can
  hit stage timeouts, after which the fallback takes over.
- LLM-backed brownfield runs did not converge to a verified change set within the then-default two feedback
  loops: the Claude verifier rejected diffs that the implementation agent wrote without the modified files' source
  in view, and the engine safe-stopped as designed. The second pass gives those stages the source, applies diffs
  for real in the sandbox, stops advisory findings from costing a loop and allows three loops; whether Claude now
  converges is recorded in [SCENARIOS.md](SCENARIOS.md#the-same-scenario-with-an-llm-provider). The deterministic
  path is the reproducible baseline; the defaults are 1 500 000 tokens and 64 000 streamed output tokens, and prompts
  summarize non-adjacent artifacts.
- Source-aware prompts send up to 120 000 characters of repository source per implementation, verification and
  security-review attempt; secret files are refused by name and credential-like tokens redacted by pattern, other
  secrets are not detected. The provider circuit breaker is per node and in memory.

## 9. With more time

In order of value for a regulated delivery organization:

1. **Real verification:** run the sandbox build in an ephemeral container without credentials or egress, add static
   analysis as a stage and a CVE scanner through the dependency scan hook, and make "Code complete" a checked item
   instead of a waiver.
2. **LLM operations:** (impacted sources, the circuit breaker, prompt caching, cost estimates and the evaluation
   suite are in place) re-run the Claude-backed brownfield scenario and hold providers to the suite offline; a dollar
   budget next to the token budget; split implementation into one change set per task; prompt-injection hardening by
   delimiting untrusted text, including repository sources; pin verification and security review to deterministic
   agents in regulated runs.
3. **Evidence:** export or anchor audit head hashes in write-once storage; an asymmetric KMS signer for change
   records with verification of older key ids; show the reviewed artifact hash in the console approval card.
4. **Scale and operation:** a work queue or workflow engine for automatic failover between nodes; a shared broker
   behind the event streams; spans for provider calls; dashboards and alerts on the reliability metrics and the
   event bus drop counter; a console login flow for OIDC.
5. **Shortener:** cache invalidation across nodes, OIDC or gateway authentication instead of one shared key,
   alerting on the Redis limiter failing open, URL reputation checks, and a load test of the redirect path in a
   staging environment.

---

Gayathri Addagada
