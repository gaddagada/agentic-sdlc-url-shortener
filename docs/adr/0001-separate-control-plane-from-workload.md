# ADR 0001: Separate the orchestration control plane from the URL shortener workload

Status: Accepted (2026-10-29)

## Context

The assignment asks for two things at once: a production-grade URL shortener and an agentic
orchestration layer that drives the SDLC of such a service. Putting both into one Spring Boot
application (the approach of the earlier prototype) blurs responsibilities: the orchestrator ends up
deployed inside the system it is supposed to govern, and the shortener's dependency footprint grows
with LLM SDKs and workflow persistence it does not need.

The brownfield scenario, however, requires the orchestrator to reason over a real codebase.

## Decision

- `url-shortener` is an independently deployable service with no dependency on the orchestrator.
- `orchestrator-core` is a framework-independent library containing the engine.
- `orchestrator-app` is the control plane (REST API, console, persistence, LLM adapters).
- All three live in one repository so the control plane can index the shortener's source tree from
  disk (`sdlc.workspace.root`) for codebase reasoning, and so one `./mvnw verify` validates everything.

## Consequences

- Clear deployment boundaries and smaller blast radius; each app has its own port, database and
  Dockerfile.
- The brownfield analysis is real: impacted controllers, services, entities and migrations are read
  from the actual `url-shortener` sources, not hard-coded.
- Slightly more build complexity (three modules) and two processes to start for a full demo.
