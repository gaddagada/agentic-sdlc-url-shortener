# ADR 0004: Saga-style compensations plus checkpoint restore for rollback; rollback always ends in a safe-stop

Status: Accepted (2026-10-29)

## Context

Stages may have side effects beyond their artifact (for example materializing a change set into a
sandbox workspace or running a build). "Restore an in-memory map" is not a rollback of those effects.
Equally, an engine that rolls back and then silently continues hides incidents from humans.

## Decision

- Before each stage activation (the first attempt of a stage within the current plan version) the
  engine records a `Checkpoint` (accepted artifact versions, plan version, compensation watermark).
  Retries of the same activation reuse that checkpoint.
- Agents may register `Compensation`s with their result (for example `DELETE_SANDBOX <path>`).
- On unrecoverable failure (`FAILED` after retries and fallback, or a policy DENY), `RollbackService`
  executes compensations registered after the checkpoint in reverse order, restores the checkpoint,
  records `ROLLBACK_COMPLETED` (or `COMPENSATION_FAILED` with `HaltReason.ROLLBACK_FAILED`), and puts
  the run into `SAFE_STOPPED`.
- A safe-stopped run only moves again on an explicit human command (`resume` or `replan`); a policy
  denial cannot be resumed, only re-planned with a changed requirement.

## Consequences

- Rollback is observable and testable; MTTR and rollback frequency are measurable.
- Humans own recovery decisions, which matches the "controlled autonomy" principle.
- Compensations are best-effort; a failed compensation is surfaced rather than retried forever.
