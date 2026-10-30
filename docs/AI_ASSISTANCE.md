# How AI assistance was used

The platform was built with AI assistance, deliberately and under engineering control. This page records how
that was done, in the spirit of the system itself: agents execute under defined boundaries; humans own decisions,
approvals and final quality.

## Division of responsibility

| Activity | Human (engineer) | AI assistant (Claude) |
|---|---|---|
| Requirement interpretation | Set the project's goals, decided that the orchestrator is a control plane governing a real workload, chose which ambiguities to resolve and how | Drafted the requirement-to-design mapping and surfaced the questions that needed a decision |
| Key decisions | Chose the stack (Java 25, Spring Boot 4.1), the agent providers (deterministic default, Anthropic, Ollama), the inclusion of an operator console, repository and delivery approach | Presented options with trade-offs; recorded the outcomes as ADRs |
| Architecture and specification | Reviewed and approved `docs/design/engineering-spec.md` and the ADRs | Drafted the specification: module boundaries, state machines, policy rules, API contracts, test obligations |
| Implementation | Set the quality bar (compiles, tests pass, no skipped tests, production conventions) | Generated the module implementations against the specification, module by module |
| Verification | Ran the full build and the demo scenarios; accepted or rejected results | Ran `./mvnw verify`, smoke tests and end-to-end scenario runs; fixed failures |
| Review | Owned the final review and the decision to deliver | Performed a critical review pass (correctness, security, governance semantics) and listed weaknesses |
| Documentation | Reviewed every claim against the code | Drafted documentation from the implemented code, not from intentions |

## Guardrails applied to the AI-assisted process

- Specification first: no module was generated before its contract existed in writing.
- Compile-and-test gate: nothing was accepted that did not pass `./mvnw verify` locally.
- No secrets in prompts, code or history; API keys are read from the environment only.
- Deviations from the specification were reported explicitly and either accepted (and documented)
  or corrected.
- Provenance of decisions is kept in writing (`docs/adr`, `docs/design/engineering-spec.md`),
  mirroring the artifact provenance the orchestrator records for its own agents.

## The second pass

The changes recorded in [ADR 0011](adr/0011-source-aware-prompts-and-real-diff-application.md) and
[ADR 0012](adr/0012-shortener-bulk-api-and-rate-limiter-spi.md) followed the same model. A design authored with
Claude Fable fixed the contracts first (what the impacted-source context may contain, how diff hunks are placed
and when a change set is rejected, blocking versus advisory checks, the circuit breaker, per-item charging and the
rate limiter SPI); Claude Opus 5.5 agents then implemented it, module by module, together with the tests. The same
guardrails applied: nothing was accepted before `./mvnw verify` passed for all three modules (960 tests, with the
15 Redis integration tests skipped where Docker was not available), and the documentation was revised against the
code afterwards.

## What this means for a reader

Every design decision in this repository has a human owner and a written rationale (see `docs/adr`).
The code was generated quickly, but it was accepted slowly: through tests, scenario runs and review.
That is the same model of controlled autonomy the orchestrator implements for software delivery.
