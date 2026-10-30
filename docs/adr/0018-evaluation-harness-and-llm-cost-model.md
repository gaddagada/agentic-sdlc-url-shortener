# ADR 0018: A deterministic evaluation harness and an operator-priced LLM cost model

Status: Accepted (2026-10-30)

## Context

Two questions had no mechanical answer. "Is this agent's output any good?" was judged by reading artifacts: the
validation invariants say an artifact is well formed, not that it is useful, and the live Claude-backed runs could
only be compared with the deterministic ones by hand. "What did this run cost?" had tokens but no money, and once
Anthropic prompt caching was on, input tokens were no longer one price: cache reads and cache writes are billed
differently. Any answer to either question must not depend on a model judging a model, and must not ship numbers
that silently go out of date.

## Decision

- **Deterministic scorers per artifact kind.** `ArtifactScorers` holds one scorer for each of the ten artifact
  kinds with explicit criteria (weight 2 for what matters most, 1 for regular criteria, 0.5 for hygiene; criteria
  that do not apply get weight 0), for example traceable acceptance criteria and measurable NFRs, a task DAG that
  starts with analysis in brownfield runs, impact paths that exist in the codebase index, skeleton content
  penalised in a change set, a release decision consistent with its inputs. `RunEvaluator` scores the accepted (or
  latest, noted as under review) artifact of every stage; the overall score is the mean of the stage scores. Pure
  and repeatable: the same run always gives the same report.
- **A bundled suite.** `evaluation/default-suite.json` has ten cases (greenfield in GUIDED and FULL_AUTO, the
  original bulk-API brownfield change, two ambiguous requirements, two policy denials, STRICT autonomy, a
  security-sensitive change and a compliance case that must end NO_GO) with expectations: clarification, policy
  denial, completion, minimum risk, release decision, minimum overall score. `EvaluationSuiteRunner` drives each
  case on a fresh engine, answers clarifications with the first suggested answer, approves gates as the operator
  identity `evaluation-runner` with the comment "auto-approved by the evaluation runner", never waives a NO_GO, and
  bounds every case (30 s by default).
- **Endpoints.** `GET /api/v1/runs/{id}/evaluation` (JSON, or Markdown with `format=markdown`, any role) and
  `POST /api/v1/evaluation/suite/run` (ADMIN; only when `sdlc.evaluation.enabled`, which the `local` profile sets
  and the `prod` profile clears; deterministic provider only; isolated in-memory engines, stores, audit sinks and
  leases; one suite run at a time).
- **Cost model.** Token usage carries cache read and cache creation tokens from the Anthropic client through
  `LlmUsage`, provenance, `AGENT_DECISION` audit data and `RunCounters`. An `LlmPriceBook` (exact model, else the
  longest model-name prefix that ends at a name boundary, else the provider default) prices each call in
  micro-dollars; a call without a price makes the run's cost unknown for good and is counted as unpriced. **No
  prices ship**: they come from `sdlc.llm.pricing.*`. Run views, provenance, reliability metrics and the meters
  `sdlc.llm.cost.microusd`, `sdlc.llm.calls.unpriced` and `sdlc.llm.cache.*` carry the result.

## Consequences

- The deterministic agents pass the bundled suite in every build (`EvaluationSuiteRunnerTest`), so a regression in
  an agent or in a gate shows up as a failed expectation with a reason; an LLM provider can be held to the same
  suite offline.
- The scores measure structure and consistency, not correctness of code: a skeleton change set scores low on
  content but the suite cannot tell whether a full change set compiles (that remains the sandbox build).
- The suite's automatic approvals defeat human gates by design, which is why it never runs on the production engine
  and is off in `prod`; LLM-backed suite runs are an offline job, not an endpoint.
- Cost is an estimate from the operator's price list: wrong or stale prices give wrong costs, and an unpriced model
  turns the run cost into "unknown" rather than a misleading zero (`LlmPriceBookTest`, `RunCountersTest`,
  `CostAndCacheViewsTest`).

## Alternatives considered

- **LLM-as-judge scoring.** Richer judgments, but non-deterministic, costly and self-referential when the same
  provider wrote the artifact; possible later as an additional, clearly labelled score.
- **Shipping a default price list.** Convenient, but prices change and differ by contract; a stale shipped price
  would look authoritative. Placeholders are commented out in `application.yml` instead.
- **Cost as a budget limit.** A dollar budget next to the token budget is a natural next step; it was left out
  until prices are configured by the operator, because an unknown price cannot enforce a limit.
