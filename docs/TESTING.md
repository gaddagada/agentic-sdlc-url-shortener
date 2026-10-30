# Testing

How the three modules are tested, what each group of tests proves, how to run them, and what is deliberately not
covered. Every claim the documentation makes about engine behaviour names the test that proves it
([ORCHESTRATION.md](ORCHESTRATION.md), [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)); this page is the map.

## 1. Approach

The orchestration engine is the part where behaviour is subtle (concurrency, partial failure, human interleaving),
so it carries the deepest tests, written against fakes rather than mocks. The control plane is tested at the seams
(HTTP contract, persistence, provider adapters) and end to end through its real API. The shortener follows the
classic pyramid: many unit tests for the rules that matter (URL validation, codes, rate limiting, privacy), slices
for web and persistence, and integration tests over the running application.

| Module | Tests | Unit | Slice and component | End-to-end and contract | Architecture |
|---|---|---|---|---|---|
| `url-shortener` | 278 | validator, codes, user agents, IP resolution and anonymization, rate limiter, services | `@WebMvcTest` controllers, `@DataJpaTest` repositories with Flyway, filters, async executor | `@SpringBootTest` integration over MockMvc and over a real servlet container; OpenAPI contract | ArchUnit layering |
| `orchestrator-core` | 287 | policy rules, secret patterns, graph validation, JSON codec, metrics, deterministic agents, workspace | engine with fake agents and an in-memory store and audit sink | full pipeline with the real deterministic agents | ArchUnit boundaries |
| `orchestrator-app` | 112 | provider catalog, structured-output schema, metrics recorder, API key filter | `@WebMvcTest` controller, `@DataJpaTest` stores, `@RestClientTest` Ollama client, Anthropic client with a stubbed sender | scenarios through `@SpringBootTest` and MockMvc; OpenAPI contract; 1 live Anthropic test | ArchUnit layering |

Totals are from the latest Surefire reports of each module (677 tests, all passing; the live Anthropic test is
skipped unless `ANTHROPIC_API_KEY` is set).

**Principles**

- **No timing flakiness.** Parallelism is proven with latches, waiting uses Awaitility with explicit conditions,
  retry backoff is scaled to zero in engine and end-to-end tests (`backoffScale` 0, `sdlc.engine.backoff-scale: 0`
  in `application-test.yml`), and time-dependent shortener logic takes an injected `Clock` (`MutableClock`).
- **Fakes over mocks in the core.** [`EngineHarness`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/testsupport/EngineHarness.java)
  wires a real engine with `FakeAgent`s, an in-memory `RunStore` and `AuditSink`, so tests assert on the persisted
  state and the audit trail, not on interactions.
- **No network in CI.** LLM clients are tested against stubs (`MockRestServiceServer` for Ollama, a `MessageSender`
  stub for Anthropic); the one live test is opt-in.
- **Architecture as code.** Layering and dependency rules are tests, so a violation fails the build.

**Test support** (`orchestrator-core/src/test/.../testsupport`): `EngineHarness` (an engine with fake agents,
in-memory ports, immediate retries and Awaitility helpers), `FakeAgent` (a configurable agent that records its
calls and contexts; tests script its result per call, for example blocking on a latch to prove parallelism),
`Payloads` (minimal valid payloads), `Contexts`, `MutableClock`, and `SyntheticWorkspace` (a small source tree for
brownfield tests). A typical engine test reads like this (from `OrchestrationEngineHardeningTest`):

```java
harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
        Payloads.changeSet("url-shortener/src/main/java/com/agenticsdlc/shortener/Leak.java",
                "String awsKey = \"AKIAABCDEFGHIJKLMNOP\";"),
        "implementation with an embedded credential")));
OrchestrationEngine engine = harness.start();
String id = engine.start(REQUIREMENT, "leak", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();

Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
assertThat(stopped.artifacts()).as("the denied change set must not stay in the artifact history")
        .noneMatch(a -> a.kind() == ArtifactKind.CHANGE_SET);
assertThat(harness.store.load(id)).get().satisfies(persisted ->
        assertThat(persisted.artifacts()).noneMatch(a -> a.kind() == ArtifactKind.CHANGE_SET));
```

## 2. What the tests prove

### 2.0 Guarantees and the tests that hold them

| Guarantee claimed in the documentation | Proven by |
|---|---|
| Agents cannot decide: governance commands need a human actor; model-written approvals are discarded | `commandsValidateActorsInputAndState`, `releaseApprovalsFromTheModelAreDiscarded` |
| No release without a human, at every autonomy level | `fullAutoOnlyPausesAtTheReleaseGate`, `noGoReleaseRequiresAWaiverCommentToApprove` |
| Only validated, gated output crosses a stage boundary | `wrongPayloadKindIsInvalidOutputAndExhaustsAttemptsAndFallback`, `exitApprovalAcceptsTheArtifactOnlyWhenGrantedAndReworkNeverAcceptsTheRejectedVersion` |
| Parallel branches really overlap and joins wait | `parallelBranchesOverlapAndTheJoinWaitsForBoth` |
| Every failure ends in a retry, a fallback, or a rollback with a safe-stop | `transientFaultIsRetriedTwiceThenSucceedsAndRecoveryIsMeasured`, `exhaustedPrimaryAgentFallsBackToTheDeterministicAgent`, `fatalFailureWithoutFallbackRollsBackCompensatesRestoresCheckpointAndSafeStops` |
| Decisions are attributed and never stale | `runCreationIsAttributedToTheRequestingOperator`, `anApprovalIsBoundToTheApprovalTheOperatorReviewed`, `replanAfterDesignApprovalInvalidatesDownstreamRevokesApprovalAndRequiresReapproval` |
| Credentials are neither persisted nor audited | `artifactDeniedByTheExitGateIsRemovedFromHistoryAndTheRunSafeStops`, `aRationaleContainingACredentialIsRedactedBeforeItIsAudited` |
| Agents cannot lower risk to skip an approval | `aDesignThatUnderReportsItsRiskIsStillGatedByTheEngineRiskFloor` |
| A crash or restart loses no decision | `recoverInterruptedSafeStopsRunsThatWereInProgressAndResumeContinues`, `aRestartedEngineSeesPersistedRunsAndSafeStopsInterruptedOnes` |
| Same input, same artifacts | `identicalRunsProduceIdenticalArtifactHashes`, `identicalInputsProduceByteIdenticalCanonicalJson` |

### 2.1 Engine (`orchestrator-core`, package `engine`)

| Test class | Proves (representative methods) |
|---|---|
| [`OrchestrationEngineTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineTest.java) | gate behaviour per autonomy level (`guidedHappyPathPausesAtDesignAndReleaseThenCompletes`, `fullAutoOnlyPausesAtTheReleaseGate`, `strictRequiresApprovalAtEveryStageEntryExceptIntake`); real parallelism (`parallelBranchesOverlapAndTheJoinWaitsForBoth` holds both branches on a latch and asserts the join waits); retries and MTTR (`transientFaultIsRetriedTwiceThenSucceedsAndRecoveryIsMeasured`); fallback (`exhaustedPrimaryAgentFallsBackToTheDeterministicAgent`); rollback with compensations across parallel branches (`fatalFailureWithoutFallbackRollsBackCompensatesRestoresCheckpointAndSafeStops`); policy denials (`policyDenialAtRunStartSafeStopsAndOnlyAReplanWithACleanRequirementProceeds`, `exitGateDenialForLeakedSecretRollsBackAndSafeStops`); feedback loops and their budget; re-planning (`replanAfterDesignApprovalInvalidatesDownstreamRevokesApprovalAndRequiresReapproval`, `replanBudgetIsEnforced`); budgets and the resume extension (`agentCallBudgetExceededSafeStopsTheRun`); stop, resume and restart recovery (`stopCancelsInFlightWorkAndResumeContinues`, `recoverInterruptedSafeStopsRunsThatWereInProgressAndResumeContinues`); NO_GO waivers; command validation and isolated copies |
| [`OrchestrationEngineGovernanceTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineGovernanceTest.java) | failure classification (`exceptionsAreClassifiedIntoFailureKinds`); invalid output retried with the validation error as context; wrong payload kind exhausting attempts and fallback (six calls); a failing compensation turning the halt into `ROLLBACK_FAILED`; entry-gate denial; abort running compensations; rework at an entry gate; token budget and LLM usage in provenance; active-time budget; stop during a scheduled retry; lazy loading by a new engine; metrics recorder failures isolated |
| [`OrchestrationEngineHardeningTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineHardeningTest.java) | a denied artifact is removed from history and never persisted; run creation is attributed to the requesting operator |
| [`OrchestrationEngineReviewFixesTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineReviewFixesTest.java) | acceptance only after the gate and approval, rework never accepts the rejected version; version numbers of denied artifacts never reused; quoted password literals denied, plain assignments allowed; rationale redaction; a failed verification report that leaks a secret is denied; approvals bound to the approval reviewed; commit failures (no ghost run, store catches up later); retry wake-ups that hit store failures; `InvalidRunStateException` for wrong-state commands; the engine risk floor |
| `RollbackServiceTest`, `RiskFloorTest` | rollback ignores checkpoints of a superseded plan; risk floor levels and the merge with agent-reported risk |
| [`DeterministicPipelineTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/DeterministicPipelineTest.java) | the real deterministic agents end to end: greenfield in `GUIDED` and `FULL_AUTO`, brownfield over a synthetic workspace, the ambiguous clarification path, the policy-denial scenario, and `identicalRunsProduceIdenticalArtifactHashes` |

### 2.2 Policy, artifacts, agents and supporting code (`orchestrator-core`)

| Area | Test classes | Proves |
|---|---|---|
| Policy rules | [`PolicyRulesTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/rules/PolicyRulesTest.java), `PolicyEngineTest` | table-driven allow and deny cases for every rule (prohibited intents and their paraphrases, privacy-improving removals allowed, personal data, secrets, protected paths, change size, release, risk and autonomy rules); no short-circuit, maximum verdict, fail-closed on a throwing rule |
| Secret detection | [`SecretPatternsTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/util/SecretPatternsTest.java) | each credential format is detected; placeholders (`${SHORTENER_DB_PASSWORD}`, `{{ vault... }}`, `<redacted>`, `***`), bare identifiers and prose are not |
| Deterministic agents | `RequirementsAgentTest`, `PlanningAgentTest`, `VerificationAgentTest`, `SecurityReviewAgentTest`, `ReleaseAgentTest`, `DeterministicAgentsTest` | ambiguity scoring and questions, plan DAG and critical path, the seven verification checks and the optional sandbox build, STRIDE checks, release decisions; `identicalInputsProduceByteIdenticalCanonicalJson` |
| LLM agent | [`LlmStageAgentTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/agent/llm/LlmStageAgentTest.java), `JsonSchemasTest` | validated payloads with usage and prompt hash; invalid output is a retryable failure carrying usage; provider errors classified by retryability; the clarification gate for the requirements role; lower-case enum values accepted and unknown ones rejected; approvals written by a model discarded; previous error and feedback in the prompt |
| Model and persistence format | `RunSnapshotTest`, `JsonCodecTest`, `ArtifactPayloadsTest` | a completed run with approvals, compensations and a re-plan, and an in-flight run, round-trip through the snapshot; snapshots of unknown workflows are rejected; canonical JSON and payload invariants |
| Graph and metrics | `WorkflowGraphTest`, `MetricsCalculatorTest` | cycles, unknown and self dependencies rejected with the offending stages named; layers; metric definitions |
| Workspace | `CodebaseIndexerTest`, `ImpactAnalyzerTest`, `WorkspaceSandboxTest`, `ProcessBuildRunnerTest` | indexing of types, endpoints and migrations; impact scoring; sandbox confinement; `sandboxEnvironmentDropsCredentialLikeVariablesAndKeepsTheToolchain` |
| Architecture | [`CoreArchitectureTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/CoreArchitectureTest.java) | no Spring in the core; engine independent of the deterministic agents; agents, policy and workspace independent of the engine; `util` is a leaf; no standard streams |

### 2.3 Control plane (`orchestrator-app`)

| Test class | Proves |
|---|---|
| [`OrchestratorEndToEndTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/OrchestratorEndToEndTest.java) | through the real API on H2: greenfield completes through the design and release approvals; ambiguous pauses for clarification and continues after the answers; policy denial is safe-stopped and `resume` is 409; fault-retry recovers with two retries; unknown scenario 404 and unavailable provider 409; catalog endpoints |
| [`RunControllerTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/api/RunControllerTest.java) | `@WebMvcTest`: request validation and contract defaults, unknown enum values and malformed JSON, missing or malformed `X-Operator-Id` (400 Problem Detail), 404, 409 with the engine message, 500 without internals |
| [`JpaRunStoreTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/persistence/JpaRunStoreTest.java), `JpaAuditSinkTest` | `@DataJpaTest` with Flyway: round trip of a completed run with stages, artifacts and approvals; artifacts inserted once; a restarted engine sees persisted runs and safe-stops interrupted ones; corrupt snapshots are skipped; audit ordering, isolation and unique sequence |
| `AnthropicLlmClientTest`, `StructuredOutputSchemaTest` | the structured-output request shape and typed parsing; lower-case enum values parsed while the schema constrains them to upper-case constants; maps sent as entry arrays and restored; refusal not retryable, truncation retryable; SDK errors mapped by status |
| `OllamaLlmClientTest` | `@RestClientTest` with `MockRestServiceServer`: typed parsing, 5xx and connection errors retryable, 4xx not, truncation retryable, availability probe cached for 30 s |
| `ProviderCatalogTest`, `ScenarioCatalogTest`, `ApiKeyAuthFilterTest`, `WorkspaceIndexHealthIndicatorTest`, `WorkspaceRootDetectorTest`, `MicrometerMetricsRecorderTest` | provider resolution and refusal; the seven scenarios verbatim from the specification; 401 Problem Detail without a valid key while the console and Actuator stay open; health states; repository-root detection; every engine signal recorded |
| `OpenApiContractTest`, `AppArchitectureTest` | the committed `orchestrator.openapi.yaml` lists every operation the controllers expose and documents the operator header; layering rules |
| `AnthropicLlmClientLiveTest` | opt-in (`@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY")`): a real Claude call returns a valid `RequirementSpec` (about 22 s when run) |

### 2.4 Workload (`url-shortener`)

| Test class | Proves |
|---|---|
| [`UrlValidatorTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/service/UrlValidatorTest.java) | parameterized accepted and rejected URLs: schemes, userinfo, length, `localhost`, `.local`, `.internal`, loopback, private, link-local and metadata addresses, CGNAT, multicast, broadcast, IPv6 loopback, unique-local, link-local, IPv4-mapped and NAT64 forms, the octal spelling `0177.0.0.1` |
| `LinkServiceTest`, `ShortCodeGeneratorTest`, `IdempotencyServiceTest`, `AnalyticsServiceTest` | alias rules and conflicts, expiry computation, collision retry and 503, idempotent replay and fingerprint conflicts, analytics windows |
| `TokenBucketRateLimiterTest`, `RateLimitFilterTest`, `RateLimitServletContainerTest`, `ClientIpResolverTest` | token refill with an injected clock; every POST under `/api/v1/links` limited whatever its spelling (path parameters, encoded characters, duplicate slashes), proven also over a real servlet container; forwarded headers honoured only when trusted |
| `IpAnonymizerTest`, `ClickRecorderTest`, `ClickRetentionJobTest`, `ClickEventRetentionRepositoryTest`, `AsyncConfigTest` | keyed visitor hashing without raw IPs and the placeholder key refused under production profiles; asynchronous, best-effort recording; a saturated recorder drops and counts instead of blocking a redirect; retention purge in batches and only before the cutoff |
| `LinkControllerTest`, `RedirectControllerTest`, `SecurityHeadersFilterTest` | `@WebMvcTest`: status codes and Problem Details for every error, `302` with `no-store`, HEAD requests not counted as clicks, security headers on redirects, errors and unmapped paths |
| `LinkRepositoryTest`, `ClickEventRepositoryTest` | `@DataJpaTest` with Flyway: case-sensitive codes, UTC timestamps, atomic click increments, aggregation queries |
| [`UrlShortenerIntegrationTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/UrlShortenerIntegrationTest.java) | create, redirect, recorded click, analytics, deactivate and `410`; expired link; idempotent retry; `429` after the test capacity; spoofed `X-Forwarded-For` cannot bypass the limit; aliases; private targets rejected; health and metrics exposed |
| `OpenApiContractTest`, `ArchitectureTest` | the committed `url-shortener.openapi.yaml` declares the generated operations and DTO properties; `api` depends only on `service` and `domain`, `service` is transport-agnostic, `domain` depends on nothing |

## 3. How to run

All commands run from the repository root (`mvnw.cmd` on Windows).

| Goal | Command |
|---|---|
| Everything, with coverage | `./mvnw -B verify` |
| One module | `./mvnw -B -pl orchestrator-core verify` (for `orchestrator-app` add `-am` so the core is built in the same reactor) |
| One test class | `./mvnw -B -pl orchestrator-core test -Dtest=OrchestrationEngineTest` |
| One test method | `./mvnw -B -pl orchestrator-core test -Dtest='OrchestrationEngineTest#parallelBranchesOverlapAndTheJoinWaitsForBoth'` |
| A class in the app module | `./mvnw -B -pl orchestrator-app -am test -Dtest=OrchestratorEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false` |
| The live Anthropic test | `ANTHROPIC_API_KEY=... ./mvnw -B -pl orchestrator-app -am test -Dtest=AnthropicLlmClientLiveTest -Dsurefire.failIfNoSpecifiedTests=false` |

Without `ANTHROPIC_API_KEY` the live test is reported as skipped, never as passed. Reports: Surefire under
`<module>/target/surefire-reports`, JaCoCo under `<module>/target/site/jacoco/index.html`.

## 4. CI and coverage

[`ci.yml`](../.github/workflows/ci.yml) runs on every push to `main` and on pull requests: Temurin 25,
`./mvnw -B -ntp verify`, then uploads the Surefire and JaCoCo reports as a build artifact (also when the build
fails), and in a second job builds both container images. Actions are pinned to commit SHAs. CI has no
`ANTHROPIC_API_KEY`, so the live test is skipped there.

JaCoCo runs on every `verify` and only reports; no coverage threshold fails the build. Line coverage measured by the
last local builds: `url-shortener` 95.6 %, `orchestrator-core` 92.8 %, `orchestrator-app` 88.9 % (branch coverage
87.3 %, 78.4 % and 70.6 %).

## 5. What is deliberately not tested, and why

| Not covered | Why, and what covers the risk instead |
|---|---|
| Load and latency (for example the "p95 under 50 ms at 500 rps" target of the greenfield scenario) | a laptop benchmark proves nothing about production capacity; the redirect path is measured by the `shortener.redirect.latency` timer, and a load test belongs in a staging pipeline |
| PostgreSQL at test time | the migrations are written as portable SQL and the same schema runs on H2 in every test; a Testcontainers suite would need Docker in the test job. The PostgreSQL profile is not exercised by the automated build, which is a gap to close before production |
| Quality of LLM output | not assertable deterministically; tests pin what the system controls instead: request shape, schema, parsing, error classification, validation of what comes back, budgets and fallback. Live behaviour is shown in [SCENARIOS.md](SCENARIOS.md) |
| A live Ollama model | slow on a CPU (about 100 s per intake call) and machine-dependent; the HTTP contract is tested with `MockRestServiceServer` |
| The operator console in a browser | static JavaScript without a build step; exercised manually (the screenshots in `docs/images/screenshots`) and through the same API the end-to-end tests drive |
| Multi-node operation | the engine is single-node by design ([ADR 0007](adr/0007-virtual-threads-and-per-run-locking.md)); per-run locking is proven within one JVM |
| Mutation testing | not set up; the table-driven policy and validator tests and the scenario tests are the main guard against weak assertions |
