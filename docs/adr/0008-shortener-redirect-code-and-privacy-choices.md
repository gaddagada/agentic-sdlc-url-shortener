# ADR 0008: Shortener choices: 302 redirects, random Base62 codes, salted IP hashing, no DNS-time SSRF check

Status: Accepted (2026-10-29). The DNS decision is amended by
[ADR 0012](0012-shortener-bulk-api-and-rate-limiter-spi.md), which adds an opt-in resolve-time check.

## Context

A URL shortener in an enterprise setting must balance performance, analytics, abuse resistance and
privacy.

## Decision

- Redirects use `302 Found` with `Cache-Control: no-store`. A `301` would let browsers cache the hop
  permanently, defeating expiry, deactivation and click analytics.
- Short codes are random Base62 strings of configurable length (default 7, 3.5 trillion values)
  generated with `SecureRandom`; collisions are handled by retrying on the unique constraint.
  Sequential or hash-derived codes would make links enumerable.
- Click analytics never store raw client IP addresses; an HMAC-SHA256 of the IP, keyed with a secret
  salt and scoped to the UTC day, supports approximate unique-visitor counts while limiting
  re-identification. The salt must be a secret in production (the placeholder is rejected under the
  `postgres`/`prod` profiles); with a known salt the IPv4 space could be brute-forced per day.
- URL validation blocks non-HTTP schemes, userinfo, local hostnames and private, loopback, link-local
  and cloud-metadata IP literals. DNS resolution is deliberately not performed at creation time: it is
  slow, racy (DNS rebinding) and would make the API depend on network conditions. This gap is documented
  as a known limitation with a production mitigation (egress policy or resolve-time check at redirect).

## Consequences

- Slightly higher redirect traffic than with `301`; correct analytics and expiry semantics.
- Privacy-preserving analytics by default; "unique visitors" is an approximation.
