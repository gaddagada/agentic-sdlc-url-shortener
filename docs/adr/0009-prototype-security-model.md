# ADR 0009: Prototype security model: API-key filter and mandatory operator identity; SSO is the production path

Status: Accepted (2026-10-29)

## Context

Approvals are high-impact actions and must be attributable to a person. A full identity provider
integration is out of scope for a prototype, but shipping anonymous approvals would contradict the
governance story.

## Decision

- Every governance endpoint (approve, reject, clarify, replan, stop, resume) requires an
  `X-Operator-Id` header; the identity is written into approvals, the audit log and the change record.
- An optional shared API key (`sdlc.security.api-key`) protects all `/api/**` routes when set; when
  unset the application logs a prominent warning that authentication is disabled (local development).
- The shortener's management API has no authentication in the prototype; the README states the
  production path (gateway authentication, per-client API keys for link creation).
- Production path for both: Spring Security with OIDC/SSO, operator identity from the token, and
  role-based authorization for approvals.

## Consequences

- Attribution exists end-to-end today; authentication strength is a deployment decision.
- Reviewers can run everything locally without configuring identity.
