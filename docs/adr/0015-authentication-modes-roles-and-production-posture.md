# ADR 0015: Authentication modes, control-plane roles and a production posture that refuses unsafe settings

Status: Accepted (2026-10-30). Supersedes [ADR 0009](0009-prototype-security-model.md) for the control plane; the
shortener's optional API key stays as decided in [ADR 0012](0012-shortener-bulk-api-and-rate-limiter-spi.md).

## Context

ADR 0009 made operator identity mandatory but self-asserted (`X-Operator-Id`), with an optional shared API key and
an optional four-eyes rule. That gave attribution, not authentication: anyone who could reach the API could approve
a release as anyone, and nothing stopped a deployment from running that way in production. The highest residual risk
of the register (R-11) was identity, and a governance platform that cannot say who approved a release has no
governance story.

## Decision

- **Three modes, `sdlc.security.mode`.** `open` (local default; every caller holds every role; a WARN at startup),
  `api-key` (the existing constant-time `ApiKeyAuthFilter` on the normalized `/api/**` paths; a caller with the key
  holds every role; inferred when only `sdlc.security.api-key` is set) and `oidc` (a Spring Security OAuth 2.0
  resource server). In `open` and `api-key` mode the operator id is still the required `X-Operator-Id` header.
- **OIDC.** Bearer JWTs verified with RS256 against keys from `jwk-set-uri`, or discovered from `issuer-uri` on the
  first request (startup does not need the identity provider); `exp`/`nbf`, `iss` when an issuer is set, an `aud`
  that must contain `sdlc.security.oidc.audience`, and a non-blank identity claim (`identity-claim`, default `sub`).
  The operator id comes from the token; an `X-Operator-Id` that names someone else is a 400
  (`operator-identity-mismatch`). Roles come from `roles-claim` (a top-level claim or a dotted path such as
  `realm_access.roles`; a JSON array or a space or comma separated string) through `role-mapping`; scopes are not
  mapped.
- **Roles.** OPERATOR (start runs and scenarios, clarify, re-plan, stop, resume), APPROVER (approve, reject), ADMIN
  (re-index, run the evaluation suite, and every other non-GET `/api/**` request, so an endpoint added without a
  rule fails closed). Any role may read (`GET /api/**`, including the event streams). Roles are independent: ADMIN
  does not imply APPROVER. The console, `/actuator/health` and, unless `sdlc.security.public-docs=false`, the
  OpenAPI document stay public. 401 and 403 are Problem Details; Spring Security's firewall rejects non-normalized
  paths before any rule.
- **Four-eyes rule.** `sdlc.governance.four-eyes=true`: the release-readiness gate cannot be approved by the
  operator who started the run or by anyone who approved its design (a revoked design approval still counts),
  compared case-insensitively; a violation is `409 four-eyes-violation`. Enforced in the API layer
  (`FourEyesGuard`), which binds the approval to the pending approval it checked.
- **Profiles and posture.** `local` (default: open unless a key is set, four-eyes off, evaluation endpoint on) and
  `prod` (`oidc`, four-eyes on, private API docs, aggregate health only, evaluation endpoint off).
  `SecurityPostureValidator` refuses a `prod` start with open mode, an API key under 32 characters, four-eyes off,
  the console on without authentication, or no change record signing key of at least 32 bytes, listing every
  violation at once; in every profile it refuses a mode that cannot work (`api-key` without a key, `oidc` without
  issuer or JWK set, audience or a value for every role).

## Consequences

- Approvals in production are authenticated and authorized by role, and the requester cannot approve their own
  release (`OidcSecurityTest`, `FourEyesEndToEndTest`, `SecurityPostureValidatorTest`, `ApiAuthorizationRulesTest`).
- Local evaluation still needs no configuration; `docker-compose.yml` runs the control plane in `api-key` mode.
- Only RS256 tokens are accepted. With `issuer-uri` only, an unreachable identity provider is noticed on the first
  request, which then fails with a server error rather than 401 (not covered by a test). There is no token
  revocation check beyond expiry, and the console has no login flow: an operator stores a token
  (`sessionStorage` key `sdlc.accessToken`) or an API key (`localStorage` key `sdlc.apiKey`).
- The four-eyes rule lives in the control plane, not the engine; another host of `orchestrator-core` must enforce
  it itself. In `api-key` mode identity remains asserted, so four-eyes there separates names, not people.

## Alternatives considered

- **OIDC only.** Simplest to reason about, but every local run and every test would need an identity provider.
- **Rewrite the API key filter as a Spring Security filter.** Kept as a servlet filter in front of the chain: its
  normalization, constant-time comparison and tests were proven, and rewriting them added risk without benefit.
- **Four-eyes inside the engine.** Would protect every host of the core. It was kept in the control plane, which
  records the requester as a run tag (`RunLauncher`), resolves identities and binds the decision to the checked
  approval; moving it into the engine remains an option.
