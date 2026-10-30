# Final Engineering Summary

Assignment: *Build an Agentic Software Engineering System – URL Shortener*. This summary states how the
assignment was interpreted, how the work was planned and decomposed, what was delivered and where, the decisions
that shape it, the risks and how they are controlled, the assumptions made, the limitations that remain, and what
we would do next.

## 1. Interpretation of the requirement

The assignment asks for two things that are easy to conflate: an agentic system that performs software engineering,
and a URL shortener. We read it as **a control plane that governs the delivery of changes to a real service**, with
the URL shortener as that service. Three consequences follow.

1. **The orchestrator is the product; agents are workers inside it.** The hard problem in a regulated delivery
   setting is not generating text or code, it is deciding what may be accepted, by whom, with what evidence, and what
   happens when something fails. The engine therefore owns state, gates, approvals, budgets and recovery; agents only
   propose typed artifacts.
2. **The shortener must be real.** It is built to production conventions (validation, abuse controls, privacy,
   observability, contract tests) and doubles as the codebase that brownfield runs reason over, so "codebase
   reasoning" reads actual source rather than a fixture.
3. **Everything must run without secrets.** Reviewers must be able to execute every scenario, and CI must prove
   every governance path, without an API key. LLMs are therefore optional providers behind the same contracts, with
   deterministic agents as default and fallback.

The eight core requirements were normalized into testable statements before design:

| # | Requirement | Normalized as |
|---|---|---|
| 1 | Requirement understanding | classify, extract prioritized and measurable requirements with acceptance criteria, score ambiguity, ask before planning when the score reaches a threshold |
| 2 | Task decomposition | a task DAG with dependencies, critical path and parallel groups, validated like any other artifact |
| 3 | Codebase reasoning | index the real source tree (types, endpoints, migrations); rank impacted components with stated reasons and regression risks |
| 4 | Workflow orchestration | an explicit stage DAG with parallel branches and joins, entry and exit gates, retries, fallback, rollback, safe-stop, re-planning, lineage and audit |
| 5 | Engineering output | typed artifacts for every stage (spec, plan, impact, design, change set, tests, verification, docs, security review, release decision) and a production-grade service |
| 6 | Validation and risk control | artifact invariants, verification and security review as stages, policy rules at every gate, a release checklist, a risk register |
| 7 | Controlled autonomy | autonomy levels, risk-based human approvals, a non-removable release gate, budgets, operator identity on every decision |
| 8 | Final engineering summary | this document |

## 2. Plan and decomposition

**Modules.** Three Maven modules in one repository ([ADR 0001](adr/0001-separate-control-plane-from-workload.md)):
`url-shortener` (workload, port 8080), `orchestrator-core` (engine library without Spring, so its semantics are
testable in isolation and portable to another host), `orchestrator-app` (control plane, port 8090: REST API,
console, persistence, LLM adapters, scenario catalog).

**Phases.**

| Phase | Output | Exit criterion |
|---|---|---|
| 1. Interpretation and decisions | requirement-to-design mapping, ten ADRs | every open question decided and recorded |
| 2. Specification | [engineering specification](design/engineering-spec.md): packages, state machines, contracts, policy rules, test obligations | written and reviewed before the modules were built |
| 3. Workload | `url-shortener` with tests and OpenAPI contract | `./mvnw verify` green; contract matches controllers |
| 4. Engine | `orchestrator-core`: graph, scheduler, gates, failure handling, re-planning, metrics | behaviour proven by engine tests with fake agents (latches, fault injection), independent of any real agent |
| 5. Agents | ten deterministic agents, the LLM stage agent and its SPI | full pipeline test with real agents; identical hashes for identical inputs |
| 6. Control plane | API, JPA persistence, recovery, providers, console, scenario catalog | all seven scenarios pass end to end through the API |
| 7. Hardening | fixes from a critical review: denied artifacts discarded, operator-attributed run creation, budget extension on resume, sandbox environment scrubbing, broader secret and policy patterns, enum-typed artifact fields, retention job, engine-computed risk floor, rate-limit path normalization | regression tests for every fix |
| 8. Documentation and demonstration | README, these documents, demo scripts, live runs and screenshots | every statement checked against the code or a live run |

The stage graph mirrors this decomposition: understanding, planning and impact analysis feed a design that a human
approves at sufficient risk; implementation and test design run in parallel; verification, documentation and
security review gate a release decision that always needs a human.

## 3. Deliverables and where they are

| Assignment item | Location |
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
| 7. Controlled autonomy | [`GateEvaluator`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/GateEvaluator.java), [`AutonomyLevel`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/model/AutonomyLevel.java), [`RunBudget`](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/model/RunBudget.java), `X-Operator-Id` on every governance command |
| 8. Final engineering summary | this document |
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
| 302 redirects, random codes, pseudonymous analytics, no DNS-time SSRF check | correct expiry and analytics, no enumeration, privacy by default | [ADR 0008](adr/0008-shortener-redirect-code-and-privacy-choices.md) |
| Mandatory operator identity, optional API key, SSO as the production path | attributable approvals now; authentication as a deployment decision | [ADR 0009](adr/0009-prototype-security-model.md) |
| Java 25 and Spring Boot 4.1.2 | current supported baseline with mature virtual threads | [ADR 0010](adr/0010-java-25-and-spring-boot-4.1-baseline.md) |

Smaller decisions that matter to reviewers are recorded where the behaviour is specified: an artifact denied by a
policy gate is discarded rather than kept; more budget is granted only by an explicit human resume; the engine
computes a deterministic risk floor so an agent cannot under-report risk to skip an approval; and the requirement
text is re-checked by policy at the intake exit, because clarification answers can change it
([ORCHESTRATION.md](ORCHESTRATION.md)).

## 5. Risks, trade-offs and validation

The risk register ([RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)) lists twenty risks with likelihood, impact,
the control in the code and the residual risk. The five that matter most for a production decision:

1. **Identity and segregation of duties.** Approvals are attributed but identity is a header; production needs
   OIDC, roles and a four-eyes rule.
2. **Model influence.** In LLM mode a model shapes design and code and can report on its own verification; human
   design and release approvals are the control on content. The Claude-backed run in
   [SCENARIOS.md](SCENARIOS.md#the-same-scenario-with-an-llm-provider) shows a design that passed every structural
   check and still contained claims the codebase contradicts.
3. **Generated code on the host.** The optional sandbox build strips credential-like environment variables but is
   not an isolation boundary.
4. **Secret handling.** Detection is pattern-based; denied artifacts are discarded; the requirement reaches a
   provider before any secret check.
5. **Persistence and scale.** Single node; the audit log has no tamper evidence.

Validation is layered so that no single check carries the weight: typed invariants on every artifact, policy at run
start and at every gate, structural verification and a STRIDE review as stages, a release checklist, risk-based and
mandatory human approvals, budgets, and an append-only audit log. 677 automated tests prove the behaviour, including
every failure path the documentation describes ([TESTING.md](TESTING.md)).

**Observed in live runs** ([SCENARIOS.md](SCENARIOS.md)): the deterministic greenfield run completes with
10 agent calls, 2 approvals and 89 audit events in about 0.1 s of active time; the ambiguous requirement is held
at intake with four targeted questions; the unsafe requirement is stopped before any agent runs and recovers only
through a compliant re-plan; injected faults recover through retries (MTTR about 6 s with the default backoff) or the
fallback; a re-plan revokes the earlier design approval and the change record lists only current approvals; a
Claude-backed run produced substantially richer artifacts and was safe-stopped by the token budget.

## 6. Where to look for each review criterion

| Criterion | Evidence in the repository |
|---|---|
| Effectiveness of agentic orchestration | a ten-stage DAG with real parallel branches and joins, gates, retries, fallback, rollback, re-planning and lineage ([ORCHESTRATION.md](ORCHESTRATION.md)); seven runnable scenarios, including failure paths ([SCENARIOS.md](SCENARIOS.md)) |
| Architecture quality | three modules with enforced boundaries (ArchUnit in every module), a framework-free engine behind ports, adapters for persistence and providers ([ARCHITECTURE.md](ARCHITECTURE.md)) |
| Depth of decomposition and execution | typed artifacts for ten stages, each with invariants; a task DAG with critical path; impact analysis over the real source; live excerpts of every stage in [SCENARIOS.md](SCENARIOS.md) |
| Realism of outputs | a working URL shortener with production controls; deterministic artifacts that are honest about being skeletons; a Claude-backed run quoted with its tokens, latencies, errors and the mistakes a reviewer had to catch |
| Validation and risk rigor | layered checks (section 5), a twenty-entry risk register with residual risk stated per entry, failure scenarios each proven by a named test ([RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)) |
| Clarity and defensibility of decisions | ten ADRs with context and consequences; trade-offs with the alternative considered and when to revisit; a table of where the code refines the specification |
| Engineering principles | tests before trust (677 tests, no network in CI), immutable artifacts with canonical hashing, durability before visibility, least privilege for agents, explicit failure semantics |
| Engineering judgment | deterministic-first agents so governance is reproducible; a release gate that cannot be configured away; limitations stated next to the features they limit |

## 7. Assumptions

- The organization delivers through change control: a release means a recorded, approved release decision with a
  change record; deployment and git operations are outside the orchestrator (it has no push or deploy side effects).
- Operators are authenticated upstream in production (SSO); in the prototype their identity is asserted.
- Requirements arrive as English free text of up to 4 000 characters; the keyword heuristics are tuned for English.
- The URL shortener runs single-region; targets are public web URLs; analytics need approximate unique visitors per
  day, not identities.
- One engine node is enough for the prototype's load; runs are long-lived and dominated by human wait.
- LLM providers are optional and may be unavailable; the deterministic path must always complete.
- Reviewers have JDK 25; Docker, Ollama and an Anthropic key are optional.

## 8. Limitations

Stated plainly, with the production path in [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md#6-known-limitations):

- Single-node engine: per-run locks and the retry scheduler are in memory.
- Deterministic agents produce skeleton code, so every deterministic release is `GO_WITH_CONDITIONS` with "Code
  complete" waived; LLM output is not guaranteed to compile; the sandbox build that would prove it is off by default.
- Policy rules and ambiguity scoring are keyword heuristics, with documented false positives and negatives; the
  deterministic architecture agent can misread a requirement (the re-planning walkthrough shows one case).
- No DNS-time SSRF check; per-node rate limiter; no authentication on the shortener API; header-asserted operator
  identity on the control plane (API key optional).
- A failed store commit leaves the in-memory run ahead of the store.
- The console polls; Ollama on a CPU is slow and can hit stage timeouts, after which the fallback takes over.
- A full Claude-backed run does not fit the default 200 000-token budget, and a complete change set does not fit
  in 16 000 output tokens.

## 9. With more time

In order of value for a bank's delivery organization:

1. **Identity and authorization:** Spring Security with OIDC, roles for approvers, and a four-eyes rule (requester is
   not approver, design approver is not release approver). The engine already binds an exit approval to the hash of
   the artifact under review; the console should show that hash and send the id of the approval the operator read.
2. **Real verification:** run the sandbox build in an ephemeral container without credentials or egress, add static
   analysis and dependency scanning as stages, and make "Code complete" a checked item instead of a waiver.
3. **LLM operations:** per-provider budgets and a cross-stage circuit breaker; split implementation into one change
   set per task to fit output limits; prompt-injection hardening by delimiting untrusted text; pin verification and
   security review to deterministic agents in regulated runs; an evaluation set that scores artifacts per role.
4. **Persistence and scale:** PostgreSQL for the control plane with snapshot and audit in one transaction, a
   hash-chained audit log, and a work queue or workflow engine for multi-node execution.
5. **Operations:** OpenTelemetry traces across API, engine and provider calls; dashboards and alerts on the
   reliability metrics; server-sent events for the console.
6. **Shortener:** shared rate limiter and cache, gateway authentication, URL reputation checks, and a load test of the
   redirect path in a staging environment.

---

Gayathri Addagada
