# ADR 0002: Deterministic agents are the default and the fallback; LLM providers are optional adapters

Status: Accepted (2026-10-29)

## Context

Anyone must be able to run the platform end-to-end without API keys, and every governance path
(retries, fallback, rollback, re-planning) must be reproducible in automated tests. At the same time
a design goal is realistic outputs, which large language models produce far better than
templates.

## Decision

Every stage has a deterministic `StageAgent` implementation (pure Java, no randomness, no clock
inside payloads). LLM-backed agents (`LlmStageAgent` over an `LlmClient` SPI with Anthropic and
Ollama adapters) are optional and selected per run through `provider`. When an LLM agent exhausts its
retry budget, the engine switches the stage to the deterministic agent (`fallbackToDeterministic`).
All agents produce the same typed artifact records, validated identically before acceptance.

## Consequences

- Tests are deterministic and fast; CI needs no secrets.
- The same governance applies to both agent kinds; LLM output is a candidate that must pass artifact
  validation and policy before it enters cross-stage context.
- Deterministic implementation output is skeleton code, not a shippable feature; the LLM path and the
  optional sandbox build are the route to compilable change sets. This limitation is documented.
