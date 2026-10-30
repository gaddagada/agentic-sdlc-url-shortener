# ADR 0003: Static lifecycle DAG with dynamic invalidation rather than a fully generated graph

Status: Accepted (2026-10-29)

## Context

"Dynamic re-planning" can mean two things: letting an agent invent the workflow graph per run, or
keeping a governed lifecycle graph and recomputing which parts must be redone when inputs change.
A per-run generated graph is flexible but hard to govern: gates, approvals and policies would have
to be re-derived for a graph nobody has reviewed.

## Decision

The SDLC lifecycle is an explicit, versioned `WorkflowDefinition` (ten stages, declared
dependencies, declared gate behaviour, retry policy and timeouts). Dynamism lives in the run state:

- `decompose` produces a task plan that structures implementation work without altering the graph.
- When an upstream artifact changes (human re-plan, rework after rejection, verification feedback),
  `ReplanService.invalidateDownstream` marks the transitive successors STALE, revokes their approvals
  and re-executes them; unaffected parallel branches keep their accepted artifacts.
- `planVersion` increments on every re-plan and is recorded on artifacts and audit events.

## Consequences

- Governance stays reviewable: the set of gates is known before any run starts.
- Non-linear execution is still real: verify -> implement feedback loops, rework after rejection, and
  re-plans all move the run backwards in the graph under budgets.
- A new workflow shape (for example a hotfix lane) is a new `WorkflowDefinition`, not a code change
  in the engine.
