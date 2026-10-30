# ADR 0007: Virtual threads for stage execution and a per-run lock for state transitions

Status: Accepted (2026-10-29)

## Context

Stages are I/O-bound (LLM calls, builds) and must run in parallel where the graph allows. State
transitions, on the other hand, must be serialized per run to keep the aggregate consistent and the
audit sequence monotonic.

## Decision

- Stage attempts run on virtual threads (`Thread.ofVirtual()`), one per attempt, with a timeout.
- All mutations of a run happen inside `RunRegistry.withLock(runId, ...)` (a `ReentrantLock` per run).
  Persistence and audit flushing happen under the lock, before the mutation becomes visible.
- Agent execution happens outside the lock; completion re-acquires it to apply the result.
- Read models are snapshots built under the lock.

## Consequences

- Parallel branches genuinely overlap; a slow LLM call does not block other runs or other branches.
- The design is single-node. Multi-node deployment requires a distributed lock and a work queue; this
  is a documented limitation, not an accident.
