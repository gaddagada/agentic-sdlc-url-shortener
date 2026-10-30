# ADR 0006: Persist the run aggregate as a JSON snapshot plus append-only audit and artifact tables

Status: Accepted (2026-10-29)

## Context

A run is a rich aggregate (stages, approvals, checkpoints, counters, artifact versions). Mapping every
part to normalized tables would multiply entities and mapping code without improving the questions the
system must answer (show a run, show its audit trail, show artifact lineage, compute metrics).
Full event sourcing would be elegant but is more than a prototype needs.

## Decision

- `runs` stores one JSON snapshot per run (optimistic `version` column) plus a few indexed columns for
  listing and filtering.
- `audit_events` is append-only (unique `(run_id, seq)`), written in the same unit of work as the
  snapshot after every state transition.
- `artifacts` stores every artifact version with its hash and provenance for lineage queries.
- The core module defines `RunStore` and `AuditSink` ports; the app provides JPA adapters; tests use
  in-memory implementations.

## Consequences

- Simple, robust persistence that survives restarts; interrupted runs are detected at startup and
  safe-stopped pending human resume.
- Snapshot schema evolution needs care (tolerant deserialization); acceptable for the prototype.
- Swapping H2 for PostgreSQL is a configuration change.
