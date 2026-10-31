# Testing

How the three modules are tested, what each group of tests proves, how to run them, and what is deliberately not
covered. Every claim the documentation makes about engine behaviour names the test that proves it
([ORCHESTRATION.md](ORCHESTRATION.md), [RISKS_AND_TRADEOFFS.md](RISKS_AND_TRADEOFFS.md)); this page is the map.

## 1. Approach

The orchestration engine is the part where behaviour is subtle (concurrency, partial failure, human interleaving),
so it carries the deepest tests, written against fakes rather than mocks. The control plane is tested at the seams
(HTTP contract, persistence, provider adapters) and end to end through its real API. The shortener follows the
classic pyramid: many unit tests for the rules that matter (URL validation, codes, rate limiting, privacy), slices
for web and persistence, integration tests over the running application, and one Docker-only integration test class
against a real Redis. The control plane has one Docker-only integration test class too, against a real PostgreSQL.

| Module | Tests | Unit | Slice and component | End-to-end and contract | Architecture |
|---|---|---|---|---|---|
| `url-shortener` | 493, plus 15 with Docker | validator (also the resolve-time check and embedded IPv4 forms), codes, user agents, IP resolution (ports, IPv6 prefixes) and anonymization, rate limiter contract on three backends, bounded bucket store, idempotency takeover, bulk service, host resolver, problem mapping, services | `@WebMvcTest` controllers, `@DataJpaTest` repositories with Flyway, filters (rate limit, API key, Redis auto-configuration veto), async executor | `@SpringBootTest` integration over MockMvc and over a real servlet container (rate limit, API key); OpenAPI contract; `RedisRateLimiterIT` against `redis:7-alpine` (Testcontainers, Failsafe) | ArchUnit layering |
| `orchestrator-core` | 574 | policy rules and packs, regex guard, secret patterns, graph validation, canonical and strict canonical JSON, audit chain and signers, lease provider, metrics and price book, evaluation scorers and suite, deterministic agents, workspace (index, impact, impacted sources, unified diffs, sandbox, dependency inventory), verification reports | engine with fake agents and an in-memory store and audit sink, including the provider circuit breaker, the hash chain across engines, leases between two engines and registry eviction | full pipeline with the real deterministic agents; the bundled evaluation suite | ArchUnit boundaries |
| `orchestrator-app` | 357, plus 2 with Docker | provider catalog, structured-output schema, metrics recorder, API key filter, OIDC token validation and role mapping, posture validator, four-eyes guard, dependency flags, pricing properties | `@WebMvcTest` controllers, `@DataJpaTest` stores, JDBC leases, listing queries, `@RestClientTest` Ollama client, Anthropic client with a stubbed sender, the event hub with a controllable write executor | scenarios through `@SpringBootTest` and MockMvc; the three security modes with real JWTs; the persistence contract on H2 and, with Docker, `PostgresPersistenceIT` on `postgres:17-alpine`; OpenAPI contract; 1 live Anthropic test | ArchUnit layering |

Totals at the `v1.0.0` tag, from the Surefire and Failsafe reports and the commit messages: `url-shortener` 493 tests
plus the 15 of `RedisRateLimiterIT`; `orchestrator-core` 574, of which `refusesSymbolicLinksThatEscapeTheWorkspaceRoot`
is skipped on Windows when the account may not create symbolic links (it runs on Linux); `orchestrator-app` 357, of
which 2 are skipped by default (the live Anthropic test without `ANTHROPIC_API_KEY`, the opt-in OpenAPI
regeneration), plus the 2 of `PostgresPersistenceIT`. A machine without Docker reports the 17 Docker-only tests as
skipped. CI runs on Linux with Docker, so it executes 1 404 tests: everything except the two skipped by default.

**Principles**

- **No timing flakiness.** Parallelism is proven with latches, waiting uses Awaitility with explicit conditions,
  retry backoff is scaled to zero in engine and end-to-end tests (`backoffScale` 0, `sdlc.engine.backoff-scale: 0`
  in `application-test.yml`), and time-dependent shortener logic takes an injected `Clock` (`MutableClock`).
- **Fakes over mocks in the core.** [`EngineHarness`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/testsupport/EngineHarness.java)
  wires a real engine with `FakeAgent`s, an in-memory `RunStore` and `AuditSink`, so tests assert on the persisted
  state and the audit trail, not on interactions.
- **No external services by default.** LLM clients are tested against stubs (`MockRestServiceServer` for Ollama,
  a `MessageSender` stub for Anthropic), the resolve-time SSRF check against a fake resolver and OIDC against a JWK set
  served by a JDK `HttpServer` on the loopback interface; the one live LLM test is opt-in, and the two test classes
  that need a container (`RedisRateLimiterIT`, `PostgresPersistenceIT`) are skipped without Docker.
- **One contract, several implementations.** Behaviour that must not depend on the backend is written once as an
  abstract test and run against every implementation: the rate limiter (section 2.4) and the control plane's
  persistence (section 2.3).
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
| A diff that does not fit the code is never written; the agent gets the exact mismatch | `aDiffThatDoesNotApplyRejectsTheChangeSetWithHunkDiagnosticsAndWritesNothing`, `theMaterializerFeedsHunkDiagnosticsBackAsInvalidOutput`, `aHunkThatDoesNotMatchFailsWithThePreciseLineAndTheNearestMismatch` |
| Only blocking findings cost a feedback loop | `advisoryFindingsAreReportedWithoutStartingAFeedbackLoop`, `advisoryFailuresDoNotFailVerificationButBlockingFailuresAndUncoveredRequirementsDo`, `everyDeterministicCheckIsBlocking` |
| A failing provider stops costing every stage its retries | `consecutiveFailuresAcrossRunsOpenTheCircuitAndTheCooldownLetsOneTrialCloseOrReopenIt`, `injectedFaultsAndFatalErrorsDoNotCountAndASuccessResetsTheCount` |
| Agents see source only from inside the repository, never secrets | `refusesPathsOutsideTheRootSecretsAndExcludedDirectoriesAndSkipsBinaries`, `refusesSymbolicLinksThatEscapeTheWorkspaceRoot`, `credentialLikeTokensNeverLeaveTheWorkspaceInSourceContext`, `runsWithoutTheRepositoryWorkspaceOrWithoutAWorkspaceRootGetNoSources` |
| An edited, reordered or truncated-in-the-middle audit trail is detected | `modifiedContentIsDetected`, `aRemovedEventIsDetected`, `reorderedEventsAreDetected`, `aTamperedRowIsReportedAtItsSequenceNumber`, `aBatchThatDoesNotContinueTheStoredChainIsRejectedAndNothingIsStored` |
| A change record edited after signing no longer verifies; unsigned never passes as signed | `theCompletedReleaseIsSignedAndTamperedApprovalsNoLongerVerify`, `theUnsignedSignerNeverProducesOrAcceptsASignature` |
| Two nodes never execute one run; a crashed node's runs are recovered | `commandsOfAnotherNodeAreRefusedWhileTheRunExecutesAndAllowedOnceTheLeaseExpired`, `aRunOfACrashedNodeIsRecoveredOnlyOnceItsLeaseExpired`, `concurrentAcquisitionsGrantExactlyOneLease` |
| Only an approver approves, and not the requester or the design approver (four-eyes) | `theOperatorStartsTheRunAndOnlyAnApproverMayApproveIt`, `theReleaseNeedsAThirdOperator`, `aRevokedDesignApprovalStillCounts` |
| An unsafe production configuration does not start | `prodListsEveryViolationAtOnce`, `prodRequiresAUsableChangeRecordSigningKey` |
| A policy pack cannot remove the release gate or the secret guard, and a catastrophic regex fails closed | `rejectsDisablingTheReleaseGateAndTheSecretLeakGuard`, `protectedBuiltInsCannotBeDisabledEvenInCode`, `aCatastrophicRuleRegexFailsClosedInsteadOfStallingTheGate` |
| An event stream never shows uncommitted state and resumes without gaps | `aFailedCommitPublishesNothing`, `lastEventIdResumesAfterTheLastSeenSequence` |
| An unknown price never shows as a zero cost | `oneUnpricedCallMakesTheRunCostUnknownForGood`, `oneUnpricedCallMakesTheCostUnknownAndIsCounted` |

### 2.1 Engine (`orchestrator-core`, package `engine`)

| Test class | Proves (representative methods) |
|---|---|
| [`OrchestrationEngineTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineTest.java) | gate behaviour per autonomy level (`guidedHappyPathPausesAtDesignAndReleaseThenCompletes`, `fullAutoOnlyPausesAtTheReleaseGate`, `strictRequiresApprovalAtEveryStageEntryExceptIntake`); real parallelism (`parallelBranchesOverlapAndTheJoinWaitsForBoth` holds both branches on a latch and asserts the join waits); retries and MTTR (`transientFaultIsRetriedTwiceThenSucceedsAndRecoveryIsMeasured`); fallback (`exhaustedPrimaryAgentFallsBackToTheDeterministicAgent`); rollback with compensations across parallel branches (`fatalFailureWithoutFallbackRollsBackCompensatesRestoresCheckpointAndSafeStops`); policy denials (`policyDenialAtRunStartSafeStopsAndOnlyAReplanWithACleanRequirementProceeds`, `exitGateDenialForLeakedSecretRollsBackAndSafeStops`); feedback loops and their budget of three (`verificationThatKeepsFailingExhaustsTheFeedbackBudgetAndSafeStops`: four reports, four implementations); advisory findings without a loop (`advisoryFindingsAreReportedWithoutStartingAFeedbackLoop`); re-planning (`replanAfterDesignApprovalInvalidatesDownstreamRevokesApprovalAndRequiresReapproval`, `replanBudgetIsEnforced`); budgets and the resume extension (`agentCallBudgetExceededSafeStopsTheRun`); stop, resume and restart recovery (`stopCancelsInFlightWorkAndResumeContinues`, `recoverInterruptedSafeStopsRunsThatWereInProgressAndResumeContinues`); NO_GO waivers; command validation and isolated copies |
| [`OrchestrationEngineGovernanceTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineGovernanceTest.java) | failure classification (`exceptionsAreClassifiedIntoFailureKinds`); invalid output retried with the validation error as context; wrong payload kind exhausting attempts and fallback (six calls); a failing compensation turning the halt into `ROLLBACK_FAILED`; entry-gate denial; abort running compensations; rework at an entry gate; token budget and LLM usage in provenance; active-time budget; stop during a scheduled retry; lazy loading by a new engine; metrics recorder failures isolated |
| [`OrchestrationEngineHardeningTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineHardeningTest.java) | a denied artifact is removed from history and never persisted; run creation is attributed to the requesting operator |
| [`OrchestrationEngineReviewFixesTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineReviewFixesTest.java) | acceptance only after the gate and approval, rework never accepts the rejected version; version numbers of denied artifacts never reused; quoted password literals denied, plain assignments allowed; rationale redaction; a failed verification report that leaks a secret is denied; approvals bound to the approval reviewed; commit failures (a failed commit is a conflict that discards the mutation and the next command reloads the stored run; no ghost run, also when an attempt finishes while the first commit fails: `anAttemptThatFinishesWhileTheFirstCommitFailsNeverPersistsTheDiscardedRun`); retry wake-ups that hit store failures; `InvalidRunStateException` for wrong-state commands; the engine risk floor |
| [`ProviderCircuitBreakerTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/ProviderCircuitBreakerTest.java) | three consecutive provider failures across runs open the circuit; while open, attempts run the deterministic agent with `FALLBACK_ENGAGED` (reason `circuit open`) without calling the provider; after the cooldown one half-open trial closes or re-opens it; injected faults and FATAL errors do not count, a success resets the count; a threshold of 0 never opens |
| [`ImpactedSourcesContextTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/ImpactedSourcesContextTest.java), `EngineConfigTest` | implementation, verification and security review of `REPO` runs get bounded impacted sources, other roles and runs without a workspace or workspace root get none; default source limits and circuit settings, and their validation |
| `RollbackServiceTest`, `RiskFloorTest` | rollback ignores checkpoints of a superseded plan; risk floor levels and the merge with agent-reported risk |
| [`DeterministicPipelineTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/DeterministicPipelineTest.java) | the real deterministic agents end to end: greenfield in `GUIDED` and `FULL_AUTO`, brownfield over a synthetic workspace, the ambiguous clarification path, the policy-denial scenario, and `identicalRunsProduceIdenticalArtifactHashes` |
| [`OrchestrationEngineAuditChainTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineAuditChainTest.java) | a complete run produces a valid chain; a run reloaded by another engine continues it; a failed commit leaves no gap or broken link (also atomically with the in-memory store); a snapshot ahead of the audit log is resynchronized on load; the change record is signed with the configured key, left unsigned by default, and a failing signer does not block completion |
| [`OrchestrationEngineLeaseTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineLeaseTest.java) | two engines on one store: commands of the other node refused until the lease expires; a waiting run continued by any node; a long attempt keeps its lease through heartbeats; closing a node releases its leases; recovery counts; a failed release kept pending and retried; a restarted node reclaims its own lease; a failed start releases its lease |
| [`RunRegistryEvictionTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/RunRegistryEvictionTest.java) | idle runs evicted after the retention and continued correctly; terminal runs beyond the maximum evicted least recently ended first; executing runs never evicted; `listCached` versus `list` |
| `PolicyPackEngineTest`, `LlmCostEngineTest`, `LlmTimeoutScaleTest` | a declarative DENY at run start safe-stops and the audit records the pack; exit denials fail the stage and warnings never block; protected built-ins cannot be disabled even in code; every LLM call priced into counters, provenance, audit and metrics, unpriced models leave the cost unknown; LLM attempts get scaled timeouts |

### 2.2 Policy, artifacts, agents and supporting code (`orchestrator-core`)

| Area | Test classes | Proves |
|---|---|---|
| Policy rules | [`PolicyRulesTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/rules/PolicyRulesTest.java), `PolicyEngineTest` | table-driven allow and deny cases for every rule (prohibited intents and their paraphrases, privacy-improving removals allowed, personal data, secrets, protected paths, change size, release, risk and autonomy rules); no short-circuit, maximum verdict, fail-closed on a throwing rule |
| Secret detection | [`SecretPatternsTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/util/SecretPatternsTest.java) | each credential format is detected; placeholders (`${SHORTENER_DB_PASSWORD}`, `{{ vault... }}`, `<redacted>`, `***`), bare identifiers and prose are not |
| Deterministic agents | `RequirementsAgentTest`, `PlanningAgentTest`, `VerificationAgentTest`, `SecurityReviewAgentTest`, `ReleaseAgentTest`, `DeterministicAgentsTest` | ambiguity scoring and questions, plan DAG and critical path, the verification checks (all blocking: `everyDeterministicCheckIsBlocking`) and the optional sandbox build, STRIDE checks, release decisions; `identicalInputsProduceByteIdenticalCanonicalJson` |
| LLM agent | [`LlmStageAgentTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/agent/llm/LlmStageAgentTest.java), `JsonSchemasTest` | validated payloads with usage and prompt hash; invalid output is a retryable failure carrying usage; provider errors classified by retryability; the clarification gate for the requirements role; lower-case enum values accepted and unknown ones rejected; approvals written by a model discarded; previous error and feedback in the prompt; the split into system prompt, run-stable context and stage part (`thePromptIsSplitIntoSystemRunStableContextAndStageSpecificUserContent`); impacted sources in the implementation prompt but not in intake's; a verifier prompt that compares diffs with the source and separates blocking from advisory findings |
| Verification verdict | [`VerificationReportTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/artifact/VerificationReportTest.java) | failed advisory checks keep `passed` true, a failed blocking check or an uncovered requirement makes it false; JSON without the `blocking` flag reads as blocking |
| Model and persistence format | `RunSnapshotTest`, `JsonCodecTest`, `ArtifactPayloadsTest` | a completed run with approvals, compensations and a re-plan, and an in-flight run, round-trip through the snapshot; snapshots of unknown workflows are rejected; canonical JSON and payload invariants |
| Graph and metrics | `WorkflowGraphTest`, `MetricsCalculatorTest` | cycles, unknown and self dependencies rejected with the offending stages named; layers; metric definitions |
| Workspace | `CodebaseIndexerTest`, `ImpactAnalyzerTest`, `WorkspaceSandboxTest`, `ProcessBuildRunnerTest`, `ArchitectureAgentTest` | indexing of types, endpoints and migrations; impact scoring, migrations scoped to the module of the impacted entities; sandbox confinement, two changes of one file, directory-shaped paths and segments ending in a dot or a space rejected before anything is written; diffs applied, FULL overwrites, SKELETON sidecars, creates and deletes (`appliesDiffsOverwritesFullContentKeepsSkeletonSidecarsCreatesAndDeletes`); a non-matching hunk rejects the change set and writes nothing; `sandboxEnvironmentDropsCredentialLikeVariablesAndKeepsTheToolchain`; build output redacted; explicitly named `METHOD /path` endpoints become ADDED API changes |
| Unified diffs | [`UnifiedDiffTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/workspace/UnifiedDiffTest.java) | parsing and round trip; exact application; whitespace-insensitive matching that keeps the file's indentation; hunks whose line numbers are off; a hunk without line numbers that matches twice is rejected; new files, the no-newline-at-end marker, CRLF line ends and files with mixed line endings; tolerance of preambles, missing context spaces, code fences and blank separators; the precise failure message (hunk, line, nearest mismatch); rejection of text without hunks, empty hunks and diffs of several files |
| Impacted sources | [`ImpactedSourcesTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/workspace/ImpactedSourcesTest.java) | per-file limit and the `[truncated]` marker; `MODIFY` targets first, regression risks only when they fit; refusal of paths outside the root (relative, `..` and absolute), secret files and excluded directories; binary and non-UTF-8 files skipped; credential-like tokens redacted with a note; a symbolic link escaping the root refused (`refusesSymbolicLinksThatEscapeTheWorkspaceRoot`, skipped where the operating system refuses to create symbolic links) |
| Audit chain and signers | [`AuditChainTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/audit/AuditChainTest.java), `InMemoryAuditSinkChainTest`, [`ChangeRecordSignerTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/audit/ChangeRecordSignerTest.java), `StrictCanonicalJsonTest` | genesis, determinism independent of key order and number types, every field covered; modified content or data, removed, reordered and renumbered events, a replaced first event, legacy unsigned events and foreign runs detected; batches that do not continue the stored chain rejected atomically; HMAC verifies with the same key only, short keys refused, the key never shown, the unsigned signer never accepts; canonical JSON sorted, normalized, null handling and no Unicode normalization |
| Leases | `InMemoryRunLeaseProviderTest` | exclusive until released or expired, takeover exactly at expiry, renewal by token only, reclaim by the same owner, exactly one grant under concurrency |
| Policy packs | [`PolicyPackLoaderTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/pack/PolicyPackLoaderTest.java), `DeclarativePolicyRuleTest`, [`RegexGuardTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/pack/RegexGuardTest.java) | every condition type loads, canonical hashing, defaults; malformed JSON, duplicate keys, unknown fields, enum values and condition types, invalid regexes and globs, bad or built-in ids, protected rules, rules that cannot apply, nesting depth, all problems reported at once; each condition's semantics and the sample pack's behaviour; nested quantifiers and overlong regexes refused, a search over budget throws and fails closed |
| Evaluation | [`ArtifactScorersTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/evaluation/ArtifactScorersTest.java), `RunEvaluatorTest`, [`EvaluationSuiteRunnerTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/evaluation/EvaluationSuiteRunnerTest.java) | one scorer per kind with weighted criteria (for example skeleton change sets penalised, impact paths checked against the index, release decisions consistent with their inputs); stages scored in workflow order; the deterministic agents pass the bundled suite; failed expectations reported with reasons; NO_GO never waived; timeouts and broken factories fail the case |
| Cost | [`LlmPriceBookTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/metrics/LlmPriceBookTest.java), `RunCountersTest` | exact model, longest prefix at a name boundary, provider default; micro-dollar arithmetic with cache tokens, half-up rounding; one unpriced call makes the run cost unknown for good; snapshots written before costs existed keep the invariant |
| Dependency inventory | [`DependencyInventoryTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/workspace/DependencyInventoryTest.java), `DependencyReviewTest` | build output, IDE metadata and runtime data never searched; properties resolved through the parent chain, unresolvable versions `unknown`; hygiene flags on a multi-module tree; DOCTYPEs rejected; the review's check, grouped findings and the external scan through the allow-listed runner |
| Architecture | [`CoreArchitectureTest`](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/CoreArchitectureTest.java) | no Spring in the core; engine independent of the deterministic agents; agents, policy and workspace independent of the engine; policy packs inside the policy layer; only the evaluation harness depends on `evaluation`, and it uses agents only through the engine; policy rules never mutate the run; `util` is a leaf; no standard streams |

### 2.3 Control plane (`orchestrator-app`)

| Test class | Proves |
|---|---|
| [`OrchestratorEndToEndTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/OrchestratorEndToEndTest.java) | through the real API on H2: greenfield completes through the design and release approvals; ambiguous pauses for clarification and continues after the answers; policy denial is safe-stopped and `resume` is 409; fault-retry recovers with two retries; unknown scenario 404 and unavailable provider 409; catalog endpoints |
| [`RunControllerTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/api/RunControllerTest.java) | `@WebMvcTest`: request validation and contract defaults, unknown enum values and malformed JSON, missing or malformed `X-Operator-Id` (400 Problem Detail), 404, 409 with the engine message, 500 without internals |
| [`JpaRunStoreTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/persistence/JpaRunStoreTest.java), `JpaAuditSinkTest` | `@DataJpaTest` with Flyway: round trip of a completed run with stages, artifacts and approvals; artifacts inserted once; listing columns from the run and its committed events; snapshot and events committed together, a failing audit insert rolls back the snapshot; a restarted engine sees persisted runs and safe-stops interrupted ones; corrupt snapshots are skipped; audit ordering, isolation and unique sequence; a committed chain verifies after the round trip, a tampered row is reported at its sequence number, a batch that does not continue the stored chain is rejected and nothing is stored |
| [`PersistenceScenarioContract`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/PersistenceScenarioContract.java) via `H2PersistenceScenarioTest` and [`PostgresPersistenceIT`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/PostgresPersistenceIT.java) | one contract, two databases: the deterministic greenfield run completes through the whole application context; Flyway V1 to V3 applied; the stored audit trail verifies through the integrity endpoint; the run row carries the listing and progress columns and the paged list query returns it; a lease row existed while the run executed and none is left; the change record signature verifies. H2 in every build, `postgres:17-alpine` (Testcontainers, Failsafe) where Docker is available |
| [`JdbcRunLeaseProviderTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/persistence/JdbcRunLeaseProviderTest.java), [`JdbcLeaseMultiNodeTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/persistence/JdbcLeaseMultiNodeTest.java) | the SQL lease contract (exclusive, not re-entrant, takeover after expiry with a new token, renewal and release by token, reclaim by the owner only, exactly one grant under concurrency, UTC wall-clock expiry whatever the JVM time zone); two engines on one database: a waiting run continued by either node, commands of the other node refused while a run executes, a closed node's runs recovered at once, a crashed node's runs only after expiry, a restart with a fixed node id taking its run back |
| [`RunListingQueriesTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/persistence/RunListingQueriesTest.java), `PostgresNullOrderRunListingQueriesTest`, `JpaRunStoreCommitEventsTest` | listing items are the table columns with the requirement cut by the database; totals over all matching rows; filters combined with AND; every whitelisted sort field with the id as tie-breaker; rows without a value last in either direction, also under PostgreSQL's null ordering simulated on H2; commits published only after the transaction, never after a failure, in commit order |
| [`RunStreamControllerTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/api/RunStreamControllerTest.java), `RunStreamHubTest` | a run stream starts with the audit backlog (SSE id = seq) then the run view; `Last-Event-ID` resumes, a malformed one replays the trail; committed changes stream their events and the run view, a terminal run ends the stream; the list stream starts with a heartbeat and carries run summaries; heartbeats at the configured interval; a long backlog delivered completely; a stream whose live events pile up is closed and frees its slot; a blocked write never blocks the publisher |
| Security: `OidcSecurityTest`, `OidcTokensTest`, `OidcRoleMapperTest`, `ApiAuthorizationRulesTest`, `OpenModeSecurityTest`, `ApiKeyModeTest`, `ApiKeyModePrivateDocsTest`, `SecurityPostureValidatorTest`, `SecurityHeadersFilterTest` | `oidc` mode under the `prod` profile with RS256 tokens from a local JWK set: 401 without a token, for another audience, expired, foreign or wrong-issuer tokens; reads for any role, 403 without a mapped role; only an approver approves; re-index needs ADMIN; the suite needs ADMIN and is disabled in `prod`; tokens without `exp`, with a non-string identity claim or with another algorithm rejected; issuer-only decoders discover keys lazily; roles from a top-level claim, a dotted path or a separated string; every rule of `ApiAuthorizationRules`, also on encoded paths; `open` mode grants every role but still needs `X-Operator-Id`; `api-key` mode grants every role with the key and keeps the documents private when asked; every production violation listed at once |
| `FourEyesGuardTest`, [`FourEyesEndToEndTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/FourEyesEndToEndTest.java) | the requester and every design approver (also a revoked one) cannot approve the release; a third operator can, bound to the checked approval; other stages, rejections and a disabled rule are not restricted |
| [`SignedChangeRecordEndToEndTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/SignedChangeRecordEndToEndTest.java), `RunArtifactControllerTest`, `EngineConfigurationTest` | the completed release is signed and tampered approvals no longer verify; integrity endpoint results for intact and modified trails; signature views; without a key records are unsigned, a short or malformed key fails startup without revealing it; lease, registry, price, scan and policy pack settings reach the engine; a lease TTL shorter than the longest retry backoff fails startup |
| `PolicyControllerTest`, [`PolicyPackFilesTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/config/PolicyPackFilesTest.java), `EvaluationControllerTest`, [`EvaluationEndpointsTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/EvaluationEndpointsTest.java) | the policy in force with active and disableable built-ins; the YAML example pack is the core's sample pack, JSON and YAML files load, every problem of an invalid pack reported, YAML aliases rejected; a run evaluated as JSON and Markdown; the suite on isolated engines leaves the production store untouched, only the deterministic provider accepted, 404 when disabled |
| `MetricsControllerTest`, `CostAndCacheViewsTest`, `LlmPricingPropertiesTest`, `RunObservationsTest`, `TracingExportTest`, `EngineMaintenanceTest`, `ProviderControllerTest`, `DependencyFlagsTest` | metrics reused for the cache TTL; cache tokens and dollar cost in run, provenance and metrics views, one unpriced call making the cost unknown; no prices ship by default; one run observation and a stage observation per attempt, a denied start observed with its halt reason, an operator stop not an error; spans traced, OTLP exporter configured only with an endpoint, no long task timers; a maintenance pass recovers then evicts, survives a failing recovery and runs at the configured delay; the circuit of every provider; dependency flags per declaration |
| [`BrownfieldScenarioTest`](../orchestrator-app/src/test/java/com/agenticsdlc/orchestrator/app/scenario/BrownfieldScenarioTest.java) | the brownfield requirement asks for a change the indexed tree does not implement (no campaign endpoint or migration, the bulk endpoint exists); over the real repository the deterministic run ranks the link, bulk and analytics controllers and services first, treats `Link` as a schema change, plans a new migration in the shortener's folder, completes with exactly two approvals and leaves a valid hash chain (section 2 of [SCENARIOS.md](SCENARIOS.md#2-brownfield-campaign-labels)) |
| `AnthropicLlmClientTest`, `StructuredOutputSchemaTest` | the structured-output request shape and typed parsing; lower-case enum values parsed while the schema constrains them to upper-case constants; maps sent as entry arrays and restored; refusal not retryable, truncation retryable; SDK errors mapped by status |
| `OllamaLlmClientTest` | `@RestClientTest` with `MockRestServiceServer`: typed parsing, 5xx and connection errors retryable, 4xx not, truncation retryable, availability probe cached for 30 s |
| `ProviderCatalogTest`, `ScenarioCatalogTest`, `ApiKeyAuthFilterTest`, `WorkspaceIndexHealthIndicatorTest`, `WorkspaceRootDetectorTest`, `MicrometerMetricsRecorderTest` | provider resolution and refusal; the seven scenarios verbatim from the specification; 401 Problem Detail without a valid key while the console and Actuator stay open; health states; repository-root detection; every engine signal recorded, including cache tokens and costs |
| `OpenApiContractTest`, `AppArchitectureTest` | the committed `orchestrator.openapi.yaml` lists every operation the controllers expose, documents the operator header, the bearer scheme, 401 and 403 and the roles of every operation; layering rules (Spring Security only in `security`, `events` and `llm` free of web and persistence code) |
| `AnthropicLlmClientLiveTest` | opt-in (`@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY")`): a real Claude call returns a valid `RequirementSpec` (about 22 s when run) |

### 2.4 Workload (`url-shortener`)

| Test class | Proves |
|---|---|
| [`UrlValidatorTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/service/UrlValidatorTest.java) | parameterized accepted and rejected URLs: schemes, userinfo, length, `localhost`, `.local`, `.internal`, loopback, private, link-local and metadata addresses, CGNAT, multicast, broadcast, IPv6 loopback, unique-local, link-local, IPv4-mapped and NAT64 forms, IPv6 literals with a zone id or an embedded non-public IPv4 address (6to4, IPv4-translated, local-use NAT64, Teredo), the octal spelling `0177.0.0.1`; with `resolve-targets` (against `FakeHostResolver`): names resolving only to public addresses accepted, any non-public address, an unknown name or a lookup timeout rejected, verdicts cached for 60 s, failures not cached, IP literals and blocked names never resolved, nothing resolved when the check or private-target blocking is off |
| `InetAddressHostResolverTest` | every address of a lookup returned; unknown hosts and empty answers rejected; the caller gives up after the timeout while the lookup still runs; with all threads stuck and the queue full, lookups are refused at once |
| `LinkServiceTest`, `ShortCodeGeneratorTest`, [`IdempotencyServiceTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/service/IdempotencyServiceTest.java), `AnalyticsServiceTest`, `ShortenerPropertiesTest` | alias rules and conflicts, expiry computation, collision retry and 503, idempotent replay and fingerprint conflicts; a pending reservation protected for its window (longer for bulk requests, with the seconds left for `Retry-After`) and taken over only afterwards; a request that was taken over can neither complete nor release the newer reservation; configurable windows, invalid ones refused; secrets never in `toString`; analytics windows |
| [`RateLimiterContract`](../url-shortener/src/test/java/com/agenticsdlc/shortener/config/RateLimiterContract.java) via `TokenBucketRateLimiterTest`, `RedisRateLimiterTest`, [`RedisRateLimiterIT`](../url-shortener/src/test/java/com/agenticsdlc/shortener/config/RedisRateLimiterIT.java) | the same twelve contract tests against the in-memory limiter, the Redis limiter on `TokenBucketScriptSimulator` (a Java port of `token-bucket.lua`) and the Redis limiter on a real `redis:7-alpine`: capacity, continuous refill, per-key buckets, `Retry-After` rounding, all-or-nothing multi-token takes, exact arithmetic for rates that do not divide a minute, an earlier clock reading never refilling or rewinding, invalid requests and configuration, exactly `capacity` grants under concurrency. Plus, in memory: full buckets dropped, the bucket closest to full evicted first beyond the limit, a drained client never reset by a burst of new keys, a new client limited even when the store is full; argument order, unit storage and key expiry, fail-open with the error counter, an unexpected reply, and the script's declared arguments (simulator); the Redis wiring with its health indicator, link creation limited through Redis and bulk requests charged per item (real Redis) |
| `RateLimitFilterTest`, `RateLimitServletContainerTest`, [`ClientIpResolverTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/service/ClientIpResolverTest.java), `RedisBackendAutoConfigurationFilterTest` | every `POST` under `/api/v1/links` except the exact bulk path charged one token whatever its spelling (path parameters, encoded characters, duplicate slashes), proven also over a real servlet container, and answered `429` (never `500`) for special characters; the default backend is in memory without any Redis bean, and Redis auto-configuration is vetoed unless the backend is `redis`; forwarded headers honoured only when trusted, ports stripped from `X-Forwarded-For` entries; IPv6 clients keyed by their /64 (configurable), IPv4 by address |
| `BulkLinkServiceTest`, `BulkLinkControllerTest`, `ProblemMapperTest`, [`BulkLinkIntegrationTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/BulkLinkIntegrationTest.java) | items created independently with failures reported in place; later items repeating an alias fail up front without being attempted; one token per item, all or nothing, with `429` when the bucket cannot cover the batch and `422 batch-too-large` above its capacity, neither charged; idempotent replay without creating or charging again; exactly 100 items accepted and 101 rejected; every item error carries exactly the problem type and status of the single endpoint |
| `ApiKeyFilterTest`, `ApiKeyConfigTest`, `RequestPathsTest`, [`ApiKeyServletContainerTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/ApiKeyServletContainerTest.java) | without a key nothing is protected and one warning is logged, a short key is accepted with a warning, the key is never printed; a missing and a wrong key get the same `401` Problem Details with `WWW-Authenticate`; whitespace around the configured key ignored; over a real Tomcat, thirteen method and path spellings (`/%61pi/v1/links`, `/api/v1/links;x`, `/api;v=1/v1/links`, ...) all need the key and are rejected before the rate limiter, while redirects, Actuator and the API docs stay public |
| `IpAnonymizerTest`, `ClickRecorderTest`, `ClickRetentionJobTest`, `ClickEventRetentionRepositoryTest`, `AsyncConfigTest` | keyed visitor hashing without raw IPs and the placeholder key refused under production profiles; asynchronous, best-effort recording; a saturated recorder drops and counts instead of blocking a redirect; retention purge in batches and only before the cutoff |
| `LinkControllerTest`, `RedirectControllerTest`, `SecurityHeadersFilterTest` | `@WebMvcTest`: status codes and Problem Details for every error, `302` with `no-store`, HEAD requests not counted as clicks, security headers on redirects, errors and unmapped paths |
| `LinkRepositoryTest`, `ClickEventRepositoryTest` | `@DataJpaTest` with Flyway: case-sensitive codes, UTC timestamps, atomic click increments, aggregation queries |
| [`UrlShortenerIntegrationTest`](../url-shortener/src/test/java/com/agenticsdlc/shortener/UrlShortenerIntegrationTest.java) | create, redirect, recorded click, analytics, deactivate and `410`; expired link; idempotent retry; `429` after the test capacity; spoofed `X-Forwarded-For` cannot bypass the limit; aliases; private targets rejected; health and metrics exposed |
| `OpenApiContractTest`, `ArchitectureTest` | the committed `url-shortener.openapi.yaml` declares the generated operations and DTO properties; `api` depends only on `service` and `domain`, `service` is transport-agnostic, `domain` depends on nothing |

**Why contract tests.** The same pattern guards two backends that must behave identically. For the control plane,
`PersistenceScenarioContract` drives a complete run through the real application context and asserts what the
database must deliver (migrations, hash chain, listing columns and query, lease rows, signature);
`H2PersistenceScenarioTest` runs it in every build and `PostgresPersistenceIT` on PostgreSQL where Docker is available,
so the PostgreSQL profile, the listing query and the JDBC lease SQL are exercised on the real database in CI.

**Why a contract test for the rate limiter.** The in-memory and the Redis limiter must take identical decisions, or
switching `shortener.rate-limit.backend` would change behaviour. Both keep bucket amounts in integer units
(1 token = 60 000 units, so a refill of N tokens per minute is exactly N units per millisecond), and the shared
abstract `RateLimiterContract` pins the decisions. It runs three times: against `TokenBucketRateLimiter`, against
`RedisRateLimiter` driving `TokenBucketScriptSimulator` (a line-by-line Java port of the Lua script, so the unit
build needs no Redis), and against `RedisRateLimiter` on a real Redis in `RedisRateLimiterIT`, which also proves
that the script itself is atomic under concurrency. The IT class is named `*IT`, so Surefire leaves it to the
Failsafe plugin in the `integration-test` phase of `verify`, and it is annotated
`@Testcontainers(disabledWithoutDocker = true)`, so a machine without Docker reports its 15 tests as skipped instead
of failing.

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
| The Redis integration tests (Docker running) | `./mvnw -B -pl url-shortener verify -Dit.test=RedisRateLimiterIT` (runs the module's unit tests first; `verify`, not `test`, because Failsafe runs `*IT` classes in the `integration-test` phase) |
| The PostgreSQL integration test (Docker running) | `./mvnw -B -pl orchestrator-app -am verify -Dit.test=PostgresPersistenceIT` |
| The brownfield scenario over the real tree | `./mvnw -B -pl orchestrator-app -am test -Dtest=BrownfieldScenarioTest -Dsurefire.failIfNoSpecifiedTests=false` |
| The evaluation suite (deterministic agents) | `./mvnw -B -pl orchestrator-core test -Dtest=EvaluationSuiteRunnerTest`, or `POST /api/v1/evaluation/suite/run` on a running control plane in the `local` profile |

Without `ANTHROPIC_API_KEY` the live test is reported as skipped, never as passed; without Docker the same holds
for `RedisRateLimiterIT` and `PostgresPersistenceIT`. Reports: Surefire under `<module>/target/surefire-reports`,
Failsafe under `<module>/target/failsafe-reports`, JaCoCo under `<module>/target/site/jacoco/index.html` (with
`jacoco.csv` next to it).

## 4. CI and coverage

[`ci.yml`](../.github/workflows/ci.yml) runs on every push to `main` and on pull requests: Temurin 25 on
`ubuntu-latest`, `./mvnw -B -ntp verify`, then uploads the Surefire and JaCoCo reports as a build artifact (also when
the build fails) and an aggregate CycloneDX SBOM, and in a second job builds both container images (retried on
transient Maven Central errors). Actions are pinned to commit SHAs; CodeQL and dependency review run as separate
workflows, and [`release.yml`](../.github/workflows/release.yml) repeats `verify` for every `v*` tag before it
publishes anything. CI has no `ANTHROPIC_API_KEY`, so the live test is skipped there.

What CI runs that a local build may skip:

| Tests | Locally | In CI |
|---|---|---|
| `RedisRateLimiterIT` (15) and `PostgresPersistenceIT` (2) | skipped without Docker (`@Testcontainers(disabledWithoutDocker = true)`) | run: GitHub-hosted Ubuntu runners provide Docker; the results appear in the build log, because the uploaded artifact contains only the Surefire reports |
| `refusesSymbolicLinksThatEscapeTheWorkspaceRoot` | skipped on Windows when the account may not create symbolic links | runs on Linux |
| `AnthropicLlmClientLiveTest`, the OpenAPI regeneration | skipped unless `ANTHROPIC_API_KEY` or `-Dopenapi.regenerate=true` is set | skipped as well |

The PostgreSQL profile of the control plane (Flyway V1 to V3 on PostgreSQL, the listing query, the JDBC lease SQL,
the audit chain round trip) is therefore exercised on a real PostgreSQL only by CI or by a local build with Docker.

JaCoCo runs on every `verify` and only reports; no coverage threshold fails the build. Line coverage of the last
local builds, computed as covered / (covered + missed) over the `LINE_COVERED` and `LINE_MISSED` columns of each
module's `jacoco.csv`. The modules were built separately, at or near the commit named in section 1, on a machine
without Docker (so the 17 Docker-only tests did not contribute); a full `./mvnw -B verify` gives the authoritative
numbers:

| Module | Lines covered | Lines missed | Line coverage | Branch coverage |
|---|---|---|---|---|
| `url-shortener` | 1 229 | 66 | 94.9 % | 87.5 % |
| `orchestrator-core` | 9 921 | 625 | 94.1 % | 81.8 % |
| `orchestrator-app` | 2 713 | 186 | 93.6 % | 78.9 % |
| all three | 13 863 | 877 | 94.1 % | 81.7 % |

The README badges are static shields.io badges with these numbers. To regenerate them after a full build, run from
the repository root:

```bash
./mvnw -B verify
for f in */target/site/jacoco/jacoco.csv; do
  awk -F, -v m="${f%%/*}" 'NR > 1 { missed += $8; covered += $9 }
    END { printf "%-18s %5d covered %4d missed %5.1f %%\n", m, covered, missed, 100 * covered / (covered + missed) }' "$f"
done
awk -F, 'FNR > 1 { missed += $8; covered += $9 }
  END { printf "overall %d covered %d missed %.1f %%\n", covered, missed, 100 * covered / (covered + missed) }' \
  */target/site/jacoco/jacoco.csv
```

Columns 8 and 9 of `jacoco.csv` are `LINE_MISSED` and `LINE_COVERED`. Put the overall value into the
`line%20coverage-<value>%25` badge URL and the number of tests CI executes (the Surefire and Failsafe totals of a
build with Docker, minus the tests skipped by default) into the `tests-<count>` badge in `README.md`.

## 5. What is deliberately not tested, and why

| Not covered | Why, and what covers the risk instead |
|---|---|
| Load and latency (for example the "p95 under 50 ms at 500 rps" target of the greenfield scenario) | a laptop benchmark proves nothing about production capacity; the redirect path is measured by the `shortener.redirect.latency` timer, and a load test belongs in a staging pipeline |
| PostgreSQL without Docker | `PostgresPersistenceIT` runs the persistence contract (migrations, hash chain, listing columns and query, lease rows, signature) on `postgres:17-alpine`, but only where Docker is available, which in practice means CI. A local build without Docker exercises the PostgreSQL-specific behaviour only through `PostgresNullOrderRunListingQueriesTest`, which simulates PostgreSQL's null ordering on H2. The shortener's `postgres` profile has no PostgreSQL test of its own; its migration is the same portable SQL the H2 tests run |
| Several processes, real clocks | multi-node behaviour is tested with two engines in one JVM on one store or database (`OrchestrationEngineLeaseTest`, `JdbcLeaseMultiNodeTest`) under a controllable clock; there is no test with separate processes, network partitions or clock skew between nodes, and no test of the event streams across nodes |
| A real identity provider | OIDC is tested with RS256 tokens from a JWK set served on the loopback interface; issuer discovery against a real provider, and the behaviour when discovery fails on the first request, are not covered |
| The evaluation suite with an LLM provider | the suite runs with the deterministic agents only; holding Claude or Ollama to it is an offline exercise that has not been recorded |
| Real DNS in the resolve-time SSRF check, and DNS rebinding | the validator is tested against `FakeHostResolver` and the resolver against a stubbed lookup, so no test depends on the network; rebinding cannot be prevented by a check at creation time (documented in `UrlValidator`), so there is nothing to assert |
| Quality of LLM output | not assertable deterministically; tests pin what the system controls instead: request shape, schema, parsing, error classification, validation of what comes back, budgets, fallback, the impacted sources in the prompt and the application of the diffs that come back. Whether a model writes diffs that apply is shown by live runs in [SCENARIOS.md](SCENARIOS.md) |
| A live Ollama model | slow on a CPU (about 100 s per intake call) and machine-dependent; the HTTP contract is tested with `MockRestServiceServer` |
| The operator console in a browser | static JavaScript without a build step, including its event-stream client and polling fallback; exercised manually (the screenshots in `docs/images/screenshots`) and through the same API and streams the tests drive |
| The shared rate limiter across nodes | the Redis limiter is tested with concurrent callers against one Redis, not with several application nodes |
| Mutation testing | not set up; the table-driven policy and validator tests and the scenario tests are the main guard against weak assertions |
