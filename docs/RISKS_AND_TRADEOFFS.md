# Risks and Trade-offs

This document records what can go wrong with the orchestrator and the URL shortener, what the code does about
it today, and what risk remains. It is written for engineers who need to judge whether the controls are real. Every
mitigation names the class that implements it; the mechanics are described in [ORCHESTRATION.md](ORCHESTRATION.md),
the design intent in the [engineering specification](design/engineering-spec.md) and the [ADRs](adr/).

## 1. How to read this document

- **Risk register (section 2).** One row per risk. *Likelihood* (L, M, H) is for the prototype as configured by
  default; *impact* (L, M, H) is for the consequence in a regulated delivery setting. *Mitigation* lists controls
  that exist in the code; the few that belong to the control plane are marked as specified. *Residual* states
  plainly what the mitigation does not cover and, where useful, the production control that would close the gap.
- **Failure scenarios (section 3)** describe the observable behaviour for concrete faults, with the audit events
  that prove it happened and the test that proves the behaviour.
- **Trade-offs (section 4)** explain decisions where a reasonable reviewer could have chosen differently.
- Statements marked "verified" were checked by running the named class against the example input.

## 2. Risk register

| ID | Risk | L | I | Mitigation in the code | Residual risk |
|---|---|---|---|---|---|
| R-01 | LLM output references files that do not exist, or code that does not compile | M | H | Prompt restricts the model to the indexed files ([PromptTemplates](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/llm/PromptTemplates.java)); `ChangeSet.validate` rejects absolute, backslash, `..` and drive-letter paths; [VerificationAgent](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/deterministic/VerificationAgent.java) checks allowed roots, tests per production file, migrations, endpoints, content and traceability; SEC-003 and CHG-001 force a human look at sensitive or large changes; implementation, verification and security review see the current source of the impacted files ([ImpactedSources](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/workspace/ImpactedSources.java)); with the sandbox enabled, DIFF changes are applied ([UnifiedDiff](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/workspace/UnifiedDiff.java)) and a hunk that does not match, or a DIFF of a missing file, rejects the change set as INVALID_OUTPUT before anything is written; optional sandbox build | Without the sandbox (the default) diffs are not applied, so a hunk that does not fit is caught only if the verifier notices it against the shown source; compilation is proven only when `sandboxEnabled` and `verifyBuildEnabled` are on (both off by default); in LLM mode verification is itself an LLM agent unless it falls back (R-12) |
| R-02 | Prompt injection through the requirement text or through LLM-written upstream artifacts | M | H | The requirement passes SEC-001 and CMP-002 at `RUN_START` and again, with clarification answers, at intake exit; agents have no tools: [LlmStageAgent](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/llm/LlmStageAgent.java) returns a typed record that must pass `validate()` and every exit-gate rule; approvals an LLM writes are discarded; sandbox writes are confined and builds are Maven-only | The requirement and upstream artifacts are embedded verbatim under Markdown headings in the user prompt, with no escaping and no instruction to treat them as data; injected text can still shape design or code content that passes structural checks. Since the source-aware prompts, repository files also enter the implementation, verification and security-review prompts verbatim (fenced, but not marked as untrusted), so text planted in a source file is another injection channel. The human design and release approvals are the last line |
| R-03 | Secrets leak into artifacts, prompts or logs | M | H | SEC-002 ([SecretLeakRule](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/policy/rules/SecretLeakRule.java)) scans every string value, key and `key: value` pair of every artifact at every exit gate, as raw text so JSON escaping cannot hide quoted literals, and denies (rollback, safe-stop); eight patterns ([SecretPatterns](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/util/SecretPatterns.java)): AWS access key ids, Anthropic API keys, GitHub tokens (`gh[pousr]_`), literal password assignments, PEM private key blocks, Slack tokens, JSON web tokens and generic credential assignments (`api_key`, `client_secret`, `secret`, `token`, `passwd`, `pwd`) ([SecretPatternsTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/util/SecretPatternsTest.java)); the denied artifact is removed from the history before the run is saved, so it is never persisted or shown, and the audit event keeps only reference, hash and rule ids (`artifactDeniedByTheExitGateIsRemovedFromHistoryAndTheRunSafeStops`); an agent rationale matching a pattern is redacted before it is audited; findings and halt detail name the kind, never the value; verification and security review repeat the check; requirement text is logged only at DEBUG; impacted sources never include secret-bearing files by name (`.env*`, `*.pem`, `*.key`, `*.p12`, `*.jks`) or anything under `.git`, `target` or `data` | Pattern matching, not entropy analysis: credential formats outside the eight patterns (for example Stripe or Azure storage keys, or opaque bearer tokens) are not detected. A secret in the requirement reaches the LLM provider before any exit gate runs (SEC-002 does not inspect the requirement at RUN_START), and so does a credential inside an ordinary source or configuration file shown as an impacted source: the sources are filtered by file name, not scanned |
| R-04 | Policy false positives (keyword heuristics) | M | M | Rules are deterministic and tested with allow and deny cases ([PolicyRulesTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/rules/PolicyRulesTest.java)); findings quote the matched phrase; privacy-improving removals ("Remove the logging of raw IP addresses from the redirect path.") are allowed by SEC-001 and CMP-002, and `SecretPatterns` ignores configuration placeholders such as `password: ${SHORTENER_DB_PASSWORD}` (both proven by tests); a denial is a visible safe-stop, and a re-plan with reworded text proceeds | Keyword windows still over-match: by inspection of the rule, "Remove the approval banner from the console UI." is denied by SEC-001 ("remove" followed by "approval"). DENY has no waiver path, and an artifact-level denial needs a full re-plan |
| R-05 | Policy false negatives | M | H | Defence in depth: design approval at MEDIUM risk or above in `GUIDED`, STRIDE security review, release approval over the complete change record, audit trail; the rules recognise "turn off" and "switch off" a control, "make ... optional", "drop", "no longer validate" and IP addresses stored "in plain form" or "as-is" (test cases in [PolicyRulesTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/policy/rules/PolicyRulesTest.java)), and also "weaken" and "circumvent" | Paraphrases outside the vocabulary still pass: by inspection of the rules, "Accept short links to any host, including intranet addresses" and "Keep the visitor's address with each click" match neither SEC-001 nor CMP-002. Most security-review checks read the design's own `securityConsiderations` text rather than the code |
| R-06 | Runaway agents: cost, time or endless loops | L | M | [RunBudget](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/model/RunBudget.java) (40 agent calls, 600 000 tokens, 2 h active time, 3 feedback loops, 3 re-plans) checked before every attempt; only blocking verification findings start a loop; per-stage attempt caps and timeouts; a single deterministic fallback once the primary agent is exhausted; the clarification gate fires once per plan version; more budget only through an explicit human resume, which extends every limit by one default allowance and is audited (`budgetExtended` in `RUN_RESUMED`); a per-provider circuit breaker ([ProviderCircuits](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/ProviderCircuits.java)) sends attempts straight to the deterministic agents for 10 minutes after 3 consecutive provider failures | Budgets are checked between attempts, so one attempt can overshoot (up to 64 000 output tokens by default plus input; a live Claude-backed run stopped at 220 615 of the then-default 200 000 tokens, which is why the default was raised and prompt context trimmed). The source-aware stages add up to 120 000 characters of source per attempt, roughly 30 000 input tokens at about four characters per token, to every implementation, verification and security-review attempt, and each feedback loop repeats them. The breaker counts across runs and stages, so one run's invalid outputs can switch other runs to deterministic agents; its state is in memory. The extension is a fixed allowance, not an amount chosen by the operator |
| R-07 | Work lost on a crash | M | M | After every mutation the aggregate and its audit events are committed under the run lock (`RunStore.commit`, [OrchestrationEngine](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngine.java)); a failed commit reaches the caller and the next successful commit persists the complete state; a start whose first commit fails leaves no run behind; a retry wake-up that hits a store failure is retried; artifacts are part of the snapshot; `recoverInterrupted` safe-stops interrupted runs; `nextAttemptAt` and fault counts are persisted | In-flight attempts are lost and re-executed (LLM cost paid twice). Engine downtime counts as active time. The default `commit` saves and then appends in two calls; atomicity depends on the adapter overriding it (ADR 0006 specifies one unit of work). Until the next successful commit the in-memory aggregate is ahead of the store, and the events of a failed commit are not re-sent |
| R-08 | Concurrent approvals or commands corrupt state | M | H | One `ReentrantLock` per run ([RunRegistry](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/RunRegistry.java)) serializes commands and completions; preconditions reject the second of two approvals (`InvalidRunStateException`, 409); an exit approval is bound to the version and hash of the artifact under review, and the engine refuses a decision that names an approval other than the pending one; tokens drop stale completions; reads return copies | Locks are in-process. Two engine instances over one store would each hold their own lock (single-node by design, ADR 0007) |
| R-09 | Stale artifacts or approvals after a re-plan | M | H | [ReplanService](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/ReplanService.java) invalidates every stage from the roots, revokes approvals and cancels in-flight attempts; stages consume only accepted versions of `SUCCEEDED` predecessors; hash changes invalidate successors; approvals are bound to a plan version | A re-plan always restarts from `intake` (no partial re-plan); superseded artifacts remain visible in history, so consumers must read accepted versions |
| R-10 | Rollback of side effects is incomplete | L | M | Compensations an agent reports are registered with its result before the exit gate runs (so a denial rolls them back) and run in reverse ([RollbackService](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/RollbackService.java)); failures are audited and turn the halt into `ROLLBACK_FAILED`; the sandbox handler refuses paths outside its root | Best effort: no retry of a failed compensation. A sandbox written by an attempt whose output then fails validation, or whose result arrives after it was cancelled, has no registered compensation (the next materialization recreates the same per-run directory). The engine has no other side effects (no git push, no deployment) |
| R-11 | Operator identity spoofing; no separation of duties | H | H | Every governance command requires a `HUMAN` actor whose id is recorded on the approval, the audit event and the change record; run creation is attributed to the requesting operator (`RUN_CREATED` actor `HUMAN`); NO_GO needs a waiver comment; the control plane requires `X-Operator-Id` on every POST ([OperatorIdentityResolver](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/api/OperatorIdentityResolver.java)) and supports an optional shared API key ([ApiKeyAuthFilter](../orchestrator-app/src/main/java/com/agenticsdlc/orchestrator/app/security/ApiKeyAuthFilter.java), ADR 0009) | Identity is asserted by a header, not authenticated; no role-based authorization; one operator may approve both design and release, and may approve a run they started. Production needs OIDC identity, roles and a four-eyes rule |
| R-12 | Self-verification in LLM mode | M | H | `VerificationReport.validate` rejects a `passed` flag inconsistent with its checks, traceability and build; `SecurityReview.validate` the same for HIGH and CRITICAL findings; the release approval is human | When a provider registers agents for every role (`LlmStageAgent.forAllRoles`, spec 6.5), the model chooses which checks to report and which of them are advisory; `validate()` only checks that `passed` agrees with the flags the model set, so a real defect marked advisory passes verification. The deterministic structural checks (all blocking) run only on fallback. Regulated use should pin `VERIFICATION` and `SECURITY_REVIEW` to the deterministic agents |
| R-13 | The sandbox build executes generated code | L | H | Sandbox and build are disabled by default ([EngineConfig](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/EngineConfig.java)); [ProcessBuildRunner](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/workspace/ProcessBuildRunner.java) accepts only `mvnw`/`mvn` without shell metacharacters, runs in the sandbox, strips credential-like environment variables (names containing key, token, secret, password, passwd, credential or auth) from the child process (`sanitizeEnvironment`), times out after 10 min and kills the process tree; `pom.xml` changes need approval (SEC-003) | The sandbox is a directory copy, not an isolation boundary: generated tests and build plugins run with the engine's operating-system privileges, file-system access and network, and inherit every environment variable whose name does not look like a credential. Production needs an ephemeral container or VM without credentials and with egress control |
| R-14 | Shortener used for SSRF or as an open redirect | M | M | [UrlValidator](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/UrlValidator.java): http/https only, host required, no userinfo, at most 2048 characters; blocks `localhost`, `.local`, `.internal`, `.localhost` and IP literals in loopback, private, CGNAT, link-local (incl. `169.254.169.254`), multicast, unspecified and reserved ranges, including octal and hex `inet_aton` forms and IPv4-compatible or NAT64 IPv6; the service never fetches targets; optional resolve-time check (`shortener.resolve-targets=true`): every resolved address must be public, unknown names and lookups slower than `resolve-timeout` (2 s) are rejected, lookups run on a bounded pool (8 threads, 64 queued, refused when full) and verdicts are cached for 60 s | The resolve-time check is off by default, so by default a public name that resolves to a private address passes (ADR 0008, ADR 0012). When on, it cannot prevent DNS rebinding (a name can resolve differently when the link is followed), it makes link creation depend on DNS (fail closed), and bulk items are validated one after the other, so a batch of slow names can take up to the timeout per distinct host. Any public URL can be shortened (no reputation or allow-list), so the service is an open redirector by design |
| R-15 | Link enumeration and metadata disclosure | M | M | [ShortCodeGenerator](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/ShortCodeGenerator.java): `SecureRandom` Base62, 7 characters (62^7, about 3.5 trillion); collision retry, then `503`; reserved aliases rejected | Custom aliases are guessable by design; link metadata and analytics endpoints are unauthenticated unless the optional API key (`shortener.security.api-key`, one shared secret, no roles) is set; only `POST` requests under `/api/v1/links` are rate limited, so probing with `GET` is not throttled; `404` versus `410` reveals that an expired or disabled code existed |
| R-16 | Analytics store personal data | M | H | [IpAnonymizer](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/IpAnonymizer.java) stores `HMAC-SHA256(key = shortener.analytics.ip-salt, message = UTC date + "|" + ip)`, never the raw IP; the placeholder key is refused at startup under the `postgres` and `prod` profiles and only warned about locally; [ClickRecorder](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/ClickRecorder.java) keeps only the referrer host and the user-agent family, asynchronously on a bounded executor and best effort; [ClickRetentionJob](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/ClickRetentionJob.java) purges click events older than `shortener.analytics.retention` (default 90 days) every hour, and idempotency records are purged after 24 h | Hashed IPs are still pseudonymous personal data: anyone holding the key can recompute a day's IPv4 hashes (2^32 inputs), so the key must be managed as a secret. Under overload clicks are dropped (counted in `shortener.clicks.dropped`), so analytics can undercount. Retention is enforced by a scheduled job per node, not by the database. Unique visitors are counted per day |
| R-17 | Rate-limit bypass behind proxies or across nodes | M | M | [RateLimitFilter](../url-shortener/src/main/java/com/agenticsdlc/shortener/config/RateLimitFilter.java) charges a token bucket (capacity 20, 20 per minute) per client IP for every `POST` under `/api/v1/links` except the exact bulk path, matched on the decoded, normalized servlet path so `;` path parameters, percent-encoding and duplicate slashes cannot bypass it (a spelling that does not normalize to the exact bulk path but still reaches its handler is charged by the filter and again per item: twice, never zero times); [BulkLinkService](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/BulkLinkService.java) charges one token per item, all or nothing (`RateLimitFilterTest`, `RateLimitServletContainerTest`, `BulkLinkIntegrationTest`); [ClientIpResolver](../url-shortener/src/main/java/com/agenticsdlc/shortener/service/ClientIpResolver.java) honours `X-Forwarded-For` only when trusted, then takes the right-most entry and validates it; at most 100 000 buckets in memory; with `shortener.rate-limit.backend=redis` all nodes share the buckets through an atomic Lua script ([RedisRateLimiter](../url-shortener/src/main/java/com/agenticsdlc/shortener/config/RedisRateLimiter.java)) with the same integer arithmetic, verified by one contract test against both | Behind a proxy with trust disabled, all clients share the proxy's bucket. With several trusted hops the right-most entry is the inner proxy. IPv6 clients get a bucket per address; many source addresses yield fresh buckets. The memory backend (the default) is per node. The Redis backend fails open: while Redis is unreachable every request is allowed, counted in `shortener.rate-limit.backend.errors` and logged at WARN at most once a minute, so an outage of Redis removes the limit |
| R-18 | Single node: H2 and in-process state | H | M | Documented (ADR 0007); run state is persisted, so a restart is safe; the shortener has a PostgreSQL profile | Run locks, the retry scheduler, the provider circuits, the link cache and (by default) the rate limiter live in memory; horizontal scaling needs a distributed lock or work queue, the Redis limiter backend and cache invalidation |
| R-19 | A misunderstood low-risk requirement passes with little oversight | M | M | The ambiguity gate asks before planning ([RequirementsAgent](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/agent/deterministic/RequirementsAgent.java)); the engine's deterministic risk floor ([RiskFloor](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/artifact/RiskFloor.java)) raises the risk for API, data model, personal-data and filter or configuration changes whatever an agent reports; the release approval always shows the full change record | Ambiguity and risk are still heuristics; a requirement scored LOW in `GUIDED` reaches a human only at release |
| R-20 | Audit trail is altered after the fact | L | H | Events are append-only by contract ([AuditSink](../orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/audit/AuditSink.java)), numbered per run without gaps, and written under the run lock | No tamper evidence (no hash chaining, no write-once storage); anyone with database access can change rows |

### Highest residual risks

For a decision to take this design towards production, these residual risks matter most, in order:

1. **R-11, identity and segregation of duties.** An approval is only as strong as the identity behind it, and
   today that identity is a request header.
2. **R-02 and R-12, model influence.** In LLM mode a model shapes the design and the code and can report on its
   own verification; human review is the only control on content.
3. **R-13, generated code on the host.** Enabling the sandbox build runs model-written tests with the engine's
   privileges.
4. **R-03, secret handling.** Detection is pattern-based (eight formats), and the requirement text reaches a
   provider before any secret check; denied artifacts are discarded, but nothing scans prompts, including the
   repository sources that source-aware stages now send.
5. **R-07, R-18 and R-20, persistence.** Snapshot and audit atomicity depends on the adapter, the engine runs on
   one node, and the audit log has no tamper evidence.

## 3. Failure scenarios and expected system behaviour

| Scenario | Expected behaviour | Audit evidence | Human action | Proven by |
|---|---|---|---|---|
| Agent timeout | The nested task is interrupted; TIMEOUT is retryable: retries with backoff (2 s, then 4 s for a three-attempt stage), then the deterministic fallback; a late result is dropped by the token guard | `RETRY_SCHEDULED` (kind `TIMEOUT`), `FALLBACK_ENGAGED` if exhausted | none unless the fallback also fails | `attemptTimeoutIsClassifiedAndRetried` |
| Agent returns invalid JSON | Parse or `validate()` failure is INVALID_OUTPUT; the next attempt's prompt contains the error; after the attempts the fallback runs; no invalid artifact is ever accepted | `RETRY_SCHEDULED` (kind `INVALID_OUTPUT`), `FALLBACK_ENGAGED` | none | `invalidOutputIsRetriedWithTheValidationErrorAsContext`, `invalidOutputIsARetryableFailureCarryingUsage` |
| LLM provider outage | Unavailable or non-retryable: FATAL, immediate fallback; 5xx, 429 and timeouts are retried (TRANSIENT or TIMEOUT), then fallback; provenance shows `fallback:deterministic`. After 3 consecutive TRANSIENT, TIMEOUT or INVALID_OUTPUT provider failures (any stages, any runs) the provider's circuit opens: for 10 minutes every new attempt for that provider runs the deterministic agent without calling it, then one half-open trial closes or re-opens the circuit | `FALLBACK_ENGAGED` with source agent and error; with an open circuit `reason: circuit open`, `consecutiveFailures`, `openedAt`, `retryAt` | none; the run completes on deterministic agents | `providerErrorsAreClassifiedByRetryability`, `unexpectedExceptionsAreFatalAndEngageTheFallback`, `consecutiveFailuresAcrossRunsOpenTheCircuitAndTheCooldownLetsOneTrialCloseOrReopenIt` |
| A DIFF hunk does not match the file (sandbox enabled) | The whole change set is rejected before anything is written and the partial sandbox deleted; INVALID_OUTPUT is retried with the hunk, path and nearest mismatch as the previous error and that file's source in the prompt; after the attempts the deterministic fallback writes a skeleton plan | `RETRY_SCHEDULED` (kind `INVALID_OUTPUT`, message naming the hunk), `FALLBACK_ENGAGED` if exhausted | none | `aDiffThatDoesNotApplyRejectsTheChangeSetWithHunkDiagnosticsAndWritesNothing`, `theMaterializerFeedsHunkDiagnosticsBackAsInvalidOutput` |
| Policy denial mid-run | An exit DENY (for example SEC-002 on `implement`) removes the denied artifact from the history (it is never persisted), fails the stage, rolls back to its checkpoint and safe-stops with `POLICY_DENIED`; a denied re-plan safe-stops and keeps the old requirement | `POLICY_FINDING`, `POLICY_DENIED` (reference, hash, rule ids, `removed: true`), `STAGE_FAILED`, `ROLLBACK_COMPLETED`, `SAFE_STOP` | re-plan with a changed requirement (resume is refused) | `exitGateDenialForLeakedSecretRollsBackAndSafeStops`, `artifactDeniedByTheExitGateIsRemovedFromHistoryAndTheRunSafeStops`, `replanWithProhibitedRequirementSafeStopsARunningRun` |
| Human rejects the design | REWORK: design re-runs with `[review by <operator>] <reason>` as feedback and asks again; counts against the feedback budget. ABORT: run `ABORTED`, open stages cancelled, compensations run | `APPROVAL_REJECTED`, `FEEDBACK_LOOP`, or `RUN_ABORTED` | review the reworked design | `rejectWithReworkReRunsTheStageAndAbortEndsTheRun` |
| Verification keeps failing | Each report with a failed blocking check or an uncovered requirement routes its feedback to `implement`, which re-runs; three failures use the default budget of three loops; a fourth failed report fails `verify`, rolls back and safe-stops with `AGENT_FAILURE_EXHAUSTED`. A report whose only failures are advisory passes and starts no loop | three `FEEDBACK_LOOP`, then `STAGE_FAILED`, `ROLLBACK_COMPLETED`, `SAFE_STOP` | resume or re-plan | `failedVerificationFeedsBackToImplementationAndTheSecondVerificationPasses`, `verificationThatKeepsFailingExhaustsTheFeedbackBudgetAndSafeStops`, `advisoryFindingsAreReportedWithoutStartingAFeedbackLoop` |
| Requirement changes after design approval | Re-plan: plan version 2, every stage invalidated, the design approval revoked, the in-flight `implement` cancelled; design must be approved again; the change record lists only plan-2 approvals | `REPLAN`, `APPROVAL_REVOKED`, `STAGE_INVALIDATED` | approve the new design | `replanAfterDesignApprovalInvalidatesDownstreamRevokesApprovalAndRequiresReapproval` |
| Engine restart mid-run | `RUNNING` runs become `SAFE_STOPPED` (`INTERRUPTED_BY_RESTART`) with their running stages reset; runs waiting for a human are untouched | `RUN_INTERRUPTED`, `SAFE_STOP` | resume; interrupted stages re-run, retries are re-armed | `recoverInterruptedSafeStopsRunsThatWereInProgressAndResumeContinues` |
| Compensation fails | The compensation is marked `FAILED` with its error, rollback completes, the halt becomes `ROLLBACK_FAILED`; nothing is retried automatically | `COMPENSATION_FAILED`, `ROLLBACK_COMPLETED` (`compensationsSucceeded=false`), `SAFE_STOP` | clean up manually, then resume | `failingCompensationTurnsTheHaltIntoRollbackFailed` |
| Shortener: Redis down with `shortener.rate-limit.backend=redis` | Link creation stays available: every limiter call fails open (allowed, reported with 0 remaining tokens); Redis calls time out after 500 ms (`spring.data.redis.timeout`) | counter `shortener.rate-limit.backend.errors`, a WARN at most once a minute | restore Redis; alert on the counter | `failsOpenAndCountsWhenRedisIsUnavailable` |
| Budget exhausted | Safe-stop with `BUDGET_EXCEEDED` naming the budget; no rollback; in-flight attempts are cancelled, scheduled retries are kept; a human resume extends every limit by one default allowance and continues; a re-plan does not extend the budget | `BUDGET_EXCEEDED`, `SAFE_STOP`, then `RUN_RESUMED` with `budgetExtended: true` | resume (the authorization to spend more), or abandon the run | `agentCallBudgetExceededSafeStopsTheRun`, `llmUsageIsRecordedInProvenanceAndCountersAndBoundedByTheTokenBudget` |

All engine tests live in [OrchestrationEngineTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineTest.java)
and [OrchestrationEngineGovernanceTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngineGovernanceTest.java);
the LLM agent tests in [LlmStageAgentTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/agent/llm/LlmStageAgentTest.java).

## 4. Trade-offs and alternatives considered

### 4.1 Deterministic-first agents rather than LLM-first ([ADR 0002](adr/0002-deterministic-agents-default-llm-optional.md))

Every role has a deterministic agent; LLM agents are optional per run and fall back to it. This makes every
governance path reproducible in CI without keys, gives identical artifact hashes for identical inputs, and keeps a
working pipeline during provider outages. The cost is output quality: deterministic implementation produces
skeletons with `TODO` markers, which the release checklist marks as waived. An LLM-first design would produce
richer artifacts but make the governance claims depend on model behaviour that tests cannot pin down.
*Revisit when* a model is measurably better for a role under the same validation: make it the primary agent for
that role only and keep the deterministic fallback.

### 4.2 Static DAG with invalidation rather than generated graphs ([ADR 0003](adr/0003-static-lifecycle-dag-with-dynamic-invalidation.md))

The lifecycle is a reviewed, versioned definition; dynamism lives in run state (feedback loops, rework, re-plans,
hash-based invalidation). Every gate is known before a run starts, which is what change control requires. An
agent-generated graph per run would adapt better to unusual work but would need its gates and approvals derived
and reviewed per run. The cost here: a re-plan restarts from `intake`, and a new lane (for example a hotfix) is a
new definition. *Revisit when* several delivery lanes are needed; add reviewed definitions before considering
generated graphs.

### 4.3 Snapshot persistence rather than event sourcing ([ADR 0006](adr/0006-aggregate-snapshot-persistence-with-audit-and-artifact-tables.md))

The run is saved as one JSON snapshot after each mutation, next to an append-only audit log. Restore is a single
read and recovery is simple. Event sourcing would make the audit log the source of truth and allow exact replay,
at the cost of event versioning and projection code. The price of the choice: snapshot and audit can diverge if
the adapter does not write them atomically (R-07), and snapshot schema changes need tolerant reading (the codec
ignores unknown properties). *Revisit when* auditors require exact replay of every state transition, or several
writers must share a run.

### 4.4 Per-run lock and virtual threads rather than a workflow engine ([ADR 0007](adr/0007-virtual-threads-and-per-run-locking.md))

A `ReentrantLock` per run and virtual threads per attempt give serializable state transitions, real parallel
branches and no infrastructure. Temporal or Camunda would add durable timers, distributed workers, history replay
and operational tooling, but also a server cluster, a programming model to learn, and a second source of truth
next to the run aggregate. The cost of the choice is single-node operation (R-08, R-18); the engine's narrow
command API keeps a later migration contained. *Revisit when* runs must survive node loss without an operator's
resume, or throughput needs more than one engine node.

### 4.5 302 rather than 301 redirects ([ADR 0008](adr/0008-shortener-redirect-code-and-privacy-choices.md))

`302 Found` with `Cache-Control: no-store` keeps expiry, deactivation and click analytics effective on every
request. `301` would let browsers and proxies cache the hop permanently and cut redirect traffic, but a disabled
or expired link would keep working for those clients and their clicks would never be counted. *Revisit when*
origin load from redirects becomes the bottleneck for links that never expire and need no analytics.

### 4.6 Random codes rather than sequential codes

Random Base62 codes from `SecureRandom` cannot be enumerated by counting, at the price of a unique-constraint
collision retry. Sequential or hash-derived codes would be shorter and collision-free but would expose every link
to walking the keyspace. *Revisit when* codes must be read aloud or typed: use a longer random code over a
smaller alphabet, not a sequence.

### 4.7 Resolve-time SSRF check as an opt-in rather than the default

By default the validator rejects literal private targets without resolving host names. Since commit `cef62d5` a
resolve-time check can be switched on (`shortener.resolve-targets=true`): every address a name resolves to must be
public, and a name that does not resolve within `resolve-timeout` (2 s) is rejected. It stays off by default
because it makes link creation depend on DNS (it fails closed), adds a lookup per new host (verdicts are cached for
60 s), and still cannot stop DNS rebinding: a name can answer with a public address at creation and a private one
when the link is followed. Because the shortener never fetches targets, the exposure is to clients following a
link, not to the service, so the check filters obvious internal targets rather than defending anyone. The
production control remains an egress or browser-side policy. *Revisit when* the service starts fetching targets
(previews, unfurling): then resolve once, validate and connect to that same address, as the `UrlValidator` Javadoc
states.

### 4.8 Polling console rather than server push

The console polls the run list every 3 s and the selected run every 2 s (10 s once terminal), slowed down while the
browser tab is hidden ([`app.js`](../orchestrator-app/src/main/resources/static/app.js)). Polling is
stateless, cache-friendly and works through any proxy; the cost is up to a few seconds of latency and steady
request load. The engine already publishes a copy of the run after every mutation through `EngineListener`, so
server-sent events could be added without engine changes. *Revisit when* operators supervise many concurrent
runs and need sub-second updates.

### 4.9 Spring Boot 4.1 on Java 25 rather than 3.5 on Java 21 ([ADR 0010](adr/0010-java-25-and-spring-boot-4.1-baseline.md))

The current generation (Spring Framework 7, Jakarta EE 11, Jackson 3) has mature virtual threads and a supported
life ahead; the 3.x line's open-source support window has ended. The cost: some libraries lag the new baseline,
and an enterprise platform team may not yet certify Java 25 runtimes. *Revisit when* the deployment platform
cannot run Java 25.

### 4.10 A shared rate limiter that fails open ([ADR 0012](adr/0012-shortener-bulk-api-and-rate-limiter-spi.md))

The limiter is an SPI with an in-memory token bucket (default) and a Redis backend that runs the same integer
arithmetic in an atomic Lua script, so several nodes share one bucket per client. When Redis is unreachable the
Redis limiter allows the request, counts `shortener.rate-limit.backend.errors` and warns at most once a minute.
Failing closed would protect the database from a flood during a Redis outage but would turn every Redis incident
into an outage of link creation; for a limiter whose purpose is abuse control rather than billing, availability
was preferred. The cost: while Redis is down there is no limit at all. *Revisit when* the limit protects a scarce
resource or a contract (then fail closed or fall back to a per-node bucket).

### 4.11 One circuit per provider, shared by all runs ([ADR 0011](adr/0011-source-aware-prompts-and-real-diff-application.md))

The breaker counts consecutive provider-attributable failures (TRANSIENT, TIMEOUT, INVALID_OUTPUT) across stages
and runs and, after three, routes every run's attempts for that provider to the deterministic agents for
10 minutes. Per-run breakers would isolate runs from each other, but during a real outage each run would pay its
own retries and timeouts first. The cost of the engine-wide choice: three invalid outputs in a row from one run
(for example change sets whose diffs do not apply) also degrade other runs on the same provider to deterministic
output, and the state is lost on restart. *Revisit when* several teams share one engine: count per run or per
role, or exclude INVALID_OUTPUT from the count.

### 4.12 Source in the prompt, bounded rather than complete ([ADR 0011](adr/0011-source-aware-prompts-and-real-diff-application.md))

Implementation, verification and security review see the source of the impacted files, at most 20 000 characters
per file and 120 000 in total (about 30 000 tokens at roughly four characters per token). Unbounded context would
let a model see every dependency, but cost and latency would grow with the size of the change and the prompt could
exceed the model's window; giving no source made correct diffs impossible (SCENARIOS.md, Claude-backed runs). The
cost of the bound: large files are cut (marked `[truncated]`), regression risks are dropped first, and a diff
against the unseen part of a cut file can still be wrong. *Revisit when* models with cheap long context or
retrieval over the index make a selection per hunk practical.

## 5. Validation and safety guardrails summary

| Layer | Where | What it validates | On failure |
|---|---|---|---|
| Command validation | `OrchestrationEngine` | human actor, non-blank input, known stage, provider and fault targets, state preconditions, budgets for re-plan and rework | exception; no state change |
| Requirement policy | `PolicyEngine` at `RUN_START` | SEC-001, CMP-002 on the requirement or re-plan text | safe-stop `POLICY_DENIED` |
| Clarification gate | `ClarificationGate` | ambiguity score of at least 0.5 without answers | stage waits for answers |
| Artifact validation | engine kind check and payload `validate()` | ids, references, DAG shape, internal consistency of `passed` flags | INVALID_OUTPUT: retry, then fallback |
| Exit-gate policy | all rules at `EXIT` | secrets, protected paths, change size, personal data, risk, release decision | DENY: denied artifact discarded, rollback and safe-stop; or approval |
| Structural verification | `VerificationAgent` | eight checks (nine with a codebase index), all blocking, and an optional sandbox build | feedback loop on a blocking failure or an uncovered requirement, bounded (3); advisory findings are reported only |
| Diff application | `WorkspaceSandbox`, `UnifiedDiff` (sandbox enabled) | every DIFF hunk matches the current file before anything is written | INVALID_OUTPUT: retry with the hunk diagnostics, then fallback |
| Provider circuit | `ProviderCircuits` | consecutive provider failures across runs | open circuit: deterministic agents for 10 min, then a half-open trial |
| Security review | `SecurityReviewAgent` | STRIDE threats and up to six checks | NO_GO at release, waiver required |
| Release checklist | `ReleaseAgent` | verification, security, docs, traceability, rollback plan, design approval, completeness | NO_GO or conditions |
| Human approvals | `GateEvaluator` and engine | risk-based design and implementation, release always | run waits |
| Budgets | engine | agent calls, tokens, active time, loops, re-plans | safe-stop or refusal |
| Architecture rules | [CoreArchitectureTest](../orchestrator-core/src/test/java/com/agenticsdlc/orchestrator/core/CoreArchitectureTest.java), shortener `ArchitectureTest` | no Spring in core; engine independent of deterministic agents; agents, policy and workspace independent of the engine; leaf utilities; no standard streams | build fails |
| Tests | both modules | engine behaviour with fake agents and fault injection, real deterministic pipeline, every policy rule, shortener unit, slice and integration tests | build fails |

What "controlled autonomy" guarantees structurally with the default workflow and rule set, at every autonomy level:

- **No agent approves anything.** Governance commands require a `HUMAN` actor; agent packages cannot depend on the
  engine; LLM-written approvals are discarded; the change record is written by the engine.
- **No release without a human.** The `ALWAYS` gate, CHG-002 and, in `FULL_AUTO`, GOV-003 each require it.
- **No unvalidated output crosses stages.** Successors receive only accepted versions of `SUCCEEDED` stages, and
  acceptance follows kind checks, `validate()` and the exit gate.
- **No unbounded work and no silent failure.** Every failure ends in a retry, a fallback, or a rollback with a
  safe-stop; a halted run moves only on a human command; budgets cap calls, tokens, time, loops and re-plans.
- **No unattributed or stale decision.** Every decision carries an operator id in a gap-free audit log;
  approvals are bound to a plan version and revoked when their inputs change.

## 6. Known limitations

1. Deterministic implementation output is skeleton code, so even a clean deterministic run ends
   `GO_WITH_CONDITIONS` with the checklist item "Code complete (skeleton plan)" marked not applicable and an
   explicit condition to complete and build before merge. It demonstrates the pipeline, not a shippable feature.
   LLM-backed brownfield runs produced complete change sets but did not pass the Claude verifier within the then
   two default feedback loops (diffs were written without the modified files' source in view) and safe-stopped for
   a human decision. The source-aware prompts, real diff application and advisory checks address that cause; the
   status of a re-run is in [SCENARIOS.md](SCENARIOS.md#the-same-scenario-with-an-llm-provider).
2. In LLM mode compilation is not proven unless the sandbox and its build are enabled; both are off by default,
   and the sandbox is not an isolation boundary (R-13).
3. Policy rules and ambiguity scoring are keyword heuristics with known false positives and negatives (R-04,
   R-05); secret detection covers eight patterns and does not scan the requirement or the prompts (R-03).
4. Most security-review checks read the design's statements rather than the generated code; only the secrets
   check reads the change set.
5. A denied artifact is discarded from the history, so the evidence of what was denied is the audit event
   (reference, hash, rule ids), not the content; a reviewer cannot inspect the denied output afterwards.
6. An artifact-level `POLICY_DENIED` cannot be resumed or waived; it needs a re-plan, which restarts from `intake`.
7. More budget is granted only by a human resume after `BUDGET_EXCEEDED`, in fixed default allowances; checks run
   between attempts; engine downtime counts as active time.
8. The provider circuit breaker is engine-wide and in memory: three consecutive provider failures from any runs
   switch every run on that provider to deterministic agents for 10 minutes, and a restart closes all circuits.
9. Identity is asserted by a header; there is no authentication, role model or separation of duties in the
   prototype (R-11).
10. Single-node engine and shortener state; H2 by default (R-18). Persistence failures leave the in-memory run
    ahead of the store (R-07). The audit log has no tamper evidence (R-20).
11. Success rate counts only completed and aborted runs, so a run left `SAFE_STOPPED` never counts as a failure;
    MTTR counts only failed agent attempts, not policy denials.
12. Shortener: the resolve-time SSRF check is opt-in and cannot stop DNS rebinding; only link creation is rate
    limited; the limiter is per node unless the Redis backend is chosen, and the Redis backend fails open; the
    management API key is optional and a single shared secret; with the default bucket of 20 tokens a bulk request
    can carry at most 20 items, not the 100 the endpoint accepts; click retention is a per-node scheduled job
    (R-14 to R-17).
13. Source-aware prompts send up to 120 000 characters of repository source per implementation, verification and
    security-review attempt to the provider, filtered by file name but not scanned for credentials (R-03, R-06).

The production path for the largest gaps, in the order of section 2's highest residual risks:

| Gap | Production control |
|---|---|
| Identity (R-11) | OIDC identity from the token, role-based authorization for approvals, a four-eyes rule (requester is not approver; design approver is not release approver) |
| Model influence (R-02, R-12) | delimit untrusted text in prompts and instruct the model to treat it as data; pin `VERIFICATION` and `SECURITY_REVIEW` to deterministic agents; add static analysis and dependency scanning as stages |
| Generated code execution (R-13) | build in an ephemeral container or VM without credentials, with egress denied and resource limits |
| Secrets (R-03) | a dedicated scanner (provider patterns and entropy) on the requirement before any prompt and at every exit; quarantine denied artifacts in access-controlled storage if reviewers need to inspect them |
| Persistence (R-07, R-18, R-20) | snapshot and audit in one transaction on PostgreSQL; a distributed lock or work queue; hash-chained or write-once audit storage |
| Shortener (R-14 to R-17) | OIDC or gateway authentication instead of one shared key, rate limits on every endpoint, alerting on the Redis limiter failing open, an egress policy for anything that fetches targets, database-level or partition-based click retention, URL reputation checks |
