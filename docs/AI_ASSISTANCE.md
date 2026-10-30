# How AI assistance was used

The assignment explicitly asks for the prototype to be built with AI assistance while demonstrating
engineering judgment. This page records how that was done, in the spirit of the system itself:
agents execute under defined boundaries; humans own decisions, approvals and final quality.

## Division of responsibility

| Activity | Human (engineer) | AI assistant (Claude) |
|---|---|---|
| Requirement interpretation | Read the assignment, decided the interpretation that the orchestrator is a control plane governing a real workload, chose which ambiguities to resolve and how | Drafted the requirement-to-design mapping and surfaced the questions that needed a decision |
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

## What this means for a reviewer

Every design decision in this repository has a human owner and a written rationale (see `docs/adr`).
The code was generated quickly, but it was accepted slowly: through tests, scenario runs and review.
That is the same model of controlled autonomy the orchestrator implements for software delivery.
