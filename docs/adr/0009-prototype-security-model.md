# ADR 0009: Prototype security model: API-key filter and mandatory operator identity; SSO is the production path

Status: Superseded by [ADR 0015](0015-authentication-modes-roles-and-production-posture.md) (2026-10-30) for the
control plane: `X-Operator-Id` remains the identity in the `open` and `api-key` modes only. Originally accepted
2026-10-29. The shortener part is amended by [ADR 0012](0012-shortener-bulk-api-and-rate-limiter-spi.md), which
adds an optional shared `X-Api-Key`.

## Context

Approvals are high-impact actions and must be attributable to a person. A full identity provider
integration is out of scope for a prototype, but shipping anonymous approvals would contradict the
governance story.

## Decision

- Every mutating endpoint (run creation, approve, reject, clarify, replan, stop, resume, reindex)
  requires an `X-Operator-Id` header; the identity is written into approvals, the audit log and the
  change record. The identity is self-asserted in the prototype: it provides attribution, not
  authentication. Reserved system identities (`engine`, `system`, `deterministic`, `fallback:*`)
  are rejected.
- An optional shared API key (`sdlc.security.api-key`) protects all `/api/**` routes when set
  (matched on the decoded, normalized path, so encoded or `;`-suffixed paths cannot bypass it); when
  unset the application logs a prominent warning that authentication is disabled. The default is
  open for local evaluation; `docker-compose.yml` requires the key.
- An optional four-eyes rule (`sdlc.governance.four-eyes`) requires the release approver to differ
  from the run's requester and from the design approver.
- The shortener's management API has no authentication in the prototype; the README states the
  production path (gateway authentication, per-client API keys for link creation).
- Production path for both: Spring Security with OIDC/SSO, operator identity from the token, and
  role-based authorization for approvals.

## Consequences

- Attribution exists end-to-end today; authentication strength is a deployment decision.
- Anyone can run everything locally without configuring identity.
