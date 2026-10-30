# ADR 0006: Persist the run aggregate as a JSON snapshot plus append-only audit and artifact tables

Status: Accepted (2026-10-29). Amended by [ADR 0013](0013-hash-chained-audit-and-signed-change-records.md) (audit
events are hash-chained per run) and [ADR 0017](0017-server-sent-events-and-paged-listing.md) (listing columns on
`runs`).

## Context

A run is a rich aggregate (stages, approvals, checkpoints, counters, artifact versions). Mapping every
part to normalized tables would multiply entities and mapping code without improving the questions the
system must answer (show a run, show its audit trail, show artifact lineage, compute metrics).
Full event sourcing would be elegant but is more than a prototype needs.

## Decision

- `runs` stores one JSON snapshot per run (optimistic `version` column) plus a few indexed columns for
  listing and filtering.
- `audit_events` is append-only (unique `(run_id, seq)`), written in the same database transaction as
  the snapshot after every state transition (`RunStore.commit`).
- `artifacts` stores every artifact version that was retained with its hash and provenance for
  lineage queries. An artifact denied by an exit gate (for example one containing a credential) is
  removed before the commit; only its reference and hash remain in the audit trail.
- The core module defines `RunStore` and `AuditSink` ports; the app provides JPA adapters; tests use
  in-memory implementations.

## Consequences

- Simple, robust persistence that survives restarts; interrupted runs are detected at startup and
  safe-stopped pending human resume.
- Snapshot schema evolution needs care (tolerant deserialization); acceptable for the prototype.
- If the commit fails, the caller receives an error while the in-memory aggregate is already ahead
  of the store; this is logged and documented as a known limitation of the single-node design.
- Swapping H2 for PostgreSQL is a configuration change: the PostgreSQL driver and Flyway module are
  on the classpath of both applications.
