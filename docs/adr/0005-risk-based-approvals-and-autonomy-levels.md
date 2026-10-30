# ADR 0005: Risk-based human approvals driven by autonomy level and policy, with a non-removable release gate

Status: Accepted (2026-10-29)

## Context

Hard-coding "approve after design, approve before release" treats a one-line documentation fix and a
schema migration identically. Real change-control processes scale oversight with risk, and operators
need a knob to tighten or relax autonomy per run.

## Decision

- Each run has an `AutonomyLevel`: `FULL_AUTO` (only the release gate), `GUIDED` (default: design
  approval when the assessed risk is MEDIUM or higher, release approval always), `STRICT` (approval
  before every stage after `intake`, whose own human gate is the clarification check).
- In `GUIDED`, a risk level that a human has already accepted within the current plan version is not
  re-approved at a later risk-based gate; a re-plan starts a new plan version and revokes that
  acceptance.
- Risk is assessed by the design stage (`RiskAssessment`) from concrete factors: schema changes,
  breaking API changes, security-relevant changes, PII handling.
- Policy rules can raise additional approvals (protected paths, large change sets, PII without a
  protection statement, NO_GO release readiness).
- The release gate is `ALWAYS` and cannot be configured away.
- Every approval carries a human operator identity, a timestamp and a comment, and is written into the
  change record.

## Consequences

- Oversight effort is proportional to risk; low-risk runs flow with one approval.
- "Agents execute, humans approve" is enforced structurally, not by convention.
- Operators must supply an identity on governance actions; in production this is backed by SSO.
