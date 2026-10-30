# ADR 0011: Source-aware LLM stages, real diff application, advisory checks and a per-provider circuit breaker

Status: Accepted (2026-10-30)

## Context

Two of the three Claude-backed runs of the brownfield scenario (docs/SCENARIOS.md) produced complete change sets
that the Claude verifier rejected: the `DIFF` hunks for existing classes had wrong headers and assumed field and
constructor names, because the implementation agent saw only the codebase index, never the source it was modifying.
In the sandbox, partial `MODIFY` content was written next to the file as a `.diff` sidecar, so a wrong hunk could
not be detected mechanically either. The final report of the third run mixed defects (wrong hunks, missing tests)
with a finding about test placement (a performance test in the default build), and every finding cost the same
feedback loop. Every stage of a run also paid its own retries and timeouts when a provider misbehaved. The prompt
also mixed run-stable and per-attempt content, so nothing could be cached.

## Decision

- **Impacted sources in the context.** For the implementation, verification and security-review roles of `REPO`
  runs, `StageExecutor` adds the current source of the impacted files to the immutable `StageContext`:
  `MODIFY` targets (including files named by feedback or the previous error) before `EXTEND` components before
  regression risks, read by `ImpactedSources` with at most 20 000 characters per file and 120 000 in total
  (`EngineConfig.sourceContextPerFileChars`, `sourceContextTotalChars`). Paths outside the workspace root (also
  through symbolic links), secret-bearing files, skipped directories and binaries are refused; every file not shown
  in full gets a note. The prompt shows them under "Source of impacted files (authoritative; write diffs against
  exactly this text)".
- **Real diff application.** When the sandbox is enabled (`sdlc.engine.sandbox-enabled`, off by default),
  `WorkspaceSandbox.apply` parses `DIFF` content with `UnifiedDiff` and applies it:
  exact match at the declared position, then whitespace-insensitive, then the nearest match within 50 lines, then
  a unique match anywhere. All diffs are applied in memory first; one hunk that cannot be placed rejects the whole
  change set as INVALID_OUTPUT with the hunk, the path and the nearest mismatch, and nothing is written. `MODIFY`
  with FULL content overwrites, SKELETON content still goes to a `.skeleton` sidecar, and `.diff` sidecars are gone.
- **Blocking and advisory checks.** `Check` carries `blocking`; `VerificationReport.passed` means no blocking
  failure, no uncovered requirement and no failed build. Advisory failures are reported (Severity column, "Advisory findings"
  section) and never start a feedback loop. Every deterministic check is blocking. The default feedback-loop
  budget rises from 2 to 3.
- **Per-provider circuit breaker.** `ProviderCircuits` counts consecutive TRANSIENT, TIMEOUT and INVALID_OUTPUT
  failures of real provider calls across stages and runs. After 3 the circuit opens for 10 minutes: attempts for
  that provider run the deterministic agent, audited as `FALLBACK_ENGAGED` with reason `circuit open`; then one
  half-open trial closes or re-opens it. State is in memory (`OrchestrationEngine.providerCircuits()`).
- **Cache-ready prompt.** `LlmRequest(system, stableContext, user, ...)`: requirement, clarification answers,
  accepted specification and codebase index form the stable context; the prompt hash covers all three parts.

## Consequences

- An LLM implementation agent can write diffs against the text it modifies, and with the sandbox enabled a wrong
  diff fails fast with a message precise enough for the retry, instead of reaching verification.
- Implementation, verification and security-review prompts grow by up to 120 000 characters (roughly 30 000
  tokens) per attempt, which the token budget has to absorb; large files are cut and can still be diffed wrongly
  in their unseen part.
- Repository source now leaves the engine for the provider. Files are filtered by name, not scanned for
  credentials, and the source is a new prompt-injection channel (RISKS_AND_TRADEOFFS.md R-02, R-03).
- The verifier decides which of its findings are advisory; `validate()` only checks consistency. Pinning
  verification to the deterministic agent remains the control for regulated runs (R-12).
- The breaker is shared by all runs: three invalid outputs from one run degrade others on the same provider to
  deterministic agents for 10 minutes, and a restart closes every circuit.
- Without the sandbox (the default) diffs are still not applied; only the verifier's reading checks them.
- The clients still send the stable context and the stage part as one user message; caching needs a client change.

## Alternatives considered

- **Whole files instead of diffs for every `MODIFY`.** Simple to apply, but output size (the runs already needed
  51 000 streamed output tokens) and the risk of silently dropping unrelated code grow with file size.
- **A tool-using agent that reads files on demand.** More precise context, but it gives the agent a capability the
  governance model deliberately withholds (agents have no tools) and makes prompts non-reproducible.
- **`git apply` or a diff library.** Needs a git binary or a new dependency in a core that is limited to Jackson,
  SLF4J and JSpecify, and standard tools apply hunks strictly, while model-written hunks often carry wrong line
  counts or context lines without their leading space, which `UnifiedDiff` tolerates. The cost is one class of
  about 650 lines, covered by `UnifiedDiffTest`.
- **A per-run circuit breaker.** Isolates runs from each other, but in an outage every run pays its own retries.
