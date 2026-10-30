# ADR 0012: Shortener bulk API, rate limiter SPI with a fail-open Redis backend, optional API key and DNS check

Status: Accepted (2026-10-30). Amends the DNS decision of
[ADR 0008](0008-shortener-redirect-code-and-privacy-choices.md).

## Context

The brownfield scenario asks for bulk link creation that respects the existing per-client rate limit, and the
Claude-backed design runs left the open question of whether a bulk request costs one unit or one per item. The
shortener's own limitations were a per-node rate limiter (each node counts separately), an unauthenticated
management API, and no check of what a host name resolves to (ADR 0008).

## Decision

- **Bulk creation.** `POST /api/v1/links/bulk` accepts 1 to 100 items. Each item goes through `LinkService.create`
  exactly like a single request, in its own transaction; a failing item never rolls back the others. The response
  is `200` with one `CREATED` or `FAILED` result per item, carrying the RFC 9457 problem type and status the single
  endpoint would have answered. A later item repeating an earlier item's alias fails with `alias-conflict` without
  being attempted. With an `Idempotency-Key` the whole response is stored and replayed for 24 h
  (`Idempotent-Replayed: true`).
- **One token per created link.** `RateLimitFilter` charges one token for every `POST` under `/api/v1/links`
  except the exact (normalized) bulk path; `BulkLinkService` charges N tokens for N items, all or nothing, after the
  body is validated. A batch larger than the bucket capacity is `422 batch-too-large`; a batch the bucket cannot
  cover now is `429` with `Retry-After`, and nothing is created. Invalid requests, idempotency conflicts and replays
  are not charged.
- **`RateLimiter` SPI.** `TokenBucketRateLimiter` (memory, default) or `RedisRateLimiter`
  (`shortener.rate-limit.backend=redis`, keys prefixed by `shortener.rate-limit.redis-key-prefix`), which runs
  `ratelimit/token-bucket.lua` atomically. Both use the same integer arithmetic (1 token = 60 000 units, so a
  refill of N tokens per minute is N units per millisecond); time comes from the application clock; an idle key
  expires once its bucket would be full. `capacity` and `refill-per-minute` are capped at 1 000 000. Redis errors
  fail open: the request is allowed, `shortener.rate-limit.backend.errors` is incremented and a WARN is logged at
  most once a minute. Spring Boot's Redis auto-configuration is vetoed unless the backend is `redis`.
- **Optional API key.** With `shortener.security.api-key` (`SHORTENER_SECURITY_APIKEY`) set, every request whose
  decoded, normalized path starts with `/api/v1/links` needs `X-Api-Key`; SHA-256 digests are compared in constant
  time, before the rate limiter, and a failure is `401` with `WWW-Authenticate`. Redirects, Actuator and the API
  docs stay public. A blank key leaves the API open with one WARN; a key under 32 characters is accepted with a WARN.
- **Optional resolve-time SSRF check.** With `shortener.resolve-targets=true` (default `false`) host names are
  resolved on a bounded pool (8 threads, 64 queued lookups, refused when full) within `shortener.resolve-timeout`
  (2 s); a URL is rejected with `422` when any address is non-public, the name is unknown or the lookup is too slow.
  Verdicts are cached for 60 s; failures are not cached.

## Consequences

- The brownfield feature exists for real, so the brownfield scenario now meets an implementation instead of a gap
  (docs/SCENARIOS.md section 2).
- With the default bucket of 20 tokens a bulk request can carry at most 20 items; the 100-item limit needs a larger
  `capacity`. The requirement's two clauses conflict under default settings, and the API says so with `422`.
- Several nodes can share one limit, at the price of a Redis dependency on the request path; while Redis is down
  there is no limit at all (alert on the counter). Clock skew between nodes can delay a refill but never mint
  tokens.
- One contract test (`RateLimiterContract`) pins the behaviour of the in-memory limiter, a Java simulator of the Lua
  script and a real Redis (`RedisRateLimiterIT`, Testcontainers, skipped without Docker).
- The API key is one shared secret without roles or rotation support; it closes anonymous access, not
  authorization.
- The resolve-time check makes creation depend on DNS (fail closed) and cannot stop DNS rebinding; anything that
  fetches targets still needs to resolve once and connect to the checked address.

## Alternatives considered

- **One token per bulk request.** Simpler, but a client could create 100 links for the price of one, a hundredfold
  increase of the creation rate the limit is meant to bound.
- **Per-item charging in the filter.** The filter would have to buffer and parse the JSON body a second time to
  count the items; charging in the service after validation also leaves invalid requests uncharged.
- **Fail closed on Redis errors.** Protects the database during an outage of Redis but turns every Redis incident
  into an outage of link creation; availability was preferred for an abuse control.
- **Resolve-time check on by default.** Adds DNS latency and a failure mode to every new host for a check that
  rebinding defeats; kept opt-in, with the caveat documented in `UrlValidator`.
