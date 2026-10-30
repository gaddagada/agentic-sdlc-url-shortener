# ADR 0017: Server-sent events for the console and a paged run listing

Status: Accepted (2026-10-30)

## Context

The console polled the run list every 3 s and the selected run every 2 s. That costs a few seconds of latency and a
steady request load per open console. `GET /api/v1/runs` returned a bare array built by loading and deserializing
every run aggregate of the store, which grows without bound and cannot be filtered or sorted by the server. With
several control-plane nodes (ADR 0014) a node's in-memory view is not even complete.

## Decision

- **Event source after commit.** `JpaRunStore` hands every committed change (the run's list row, its snapshot and
  the audit events of the commit) to an in-process `RunEventBus` in a transaction synchronization, so a listener
  never sees state that could still roll back. The bus delivers on its own thread in commit order through a bounded
  queue (1 000; a change that does not fit is dropped and counted, and the streams recover by reading the store).
- **Two streams.** `GET /api/v1/runs/{id}/events` sends the audit events after `Last-Event-ID` (all without one) as
  `audit` events whose SSE id is the audit `seq`, then the current `run` view, then for every commit the new `audit`
  events followed by the new `run`; it ends after the final `run` event of a COMPLETED or ABORTED run.
  `GET /api/v1/runs/events` sends a `run-summary` (the list item shape) for every commit, without replay. Both send
  a `heartbeat` every 15 s (`sdlc.console.sse-heartbeat-interval`) and end after 30 min (`sse-timeout`); the client
  reconnects with `Last-Event-ID`. At most 200 streams per node (`max-sse-connections`), beyond that
  `503 stream-limit-reached` with `Retry-After`; a stream that falls more than 1 000 live events behind is closed.
- **Console.** The console reads the streams with `fetch` and a `ReadableStream` (not `EventSource`, which cannot
  send `X-Api-Key` or `Authorization`), shows Live or Polling, polls while a stream is down and keeps a 30 s safety
  poll while it is up.
- **Single-node semantics, stated.** A stream carries only the commits of the node that serves it; every stream
  response names that node in `X-Sdlc-Node-Id`, and the console polls a run whose `nodeId` names another node.
- **Paged listing.** `GET /api/v1/runs` returns `{items, page, size, total, totalPages, sort}`. Items are read from
  listing columns of the `runs` table (Flyway V2 and V3), never from snapshots. Filters: `status` (repeatable or
  comma separated), `scenario`, `startedBy`, combined with AND; `sort` is `<field>[,asc|desc]` over a whitelist
  (`createdAt`, `updatedAt`, `endedAt`, `status`, `scenario`, `startedBy`; ties by id; rows without a value last in
  either direction on H2 and PostgreSQL; anything else is 400); `size` 1 to 200, default 50, default sort
  `updatedAt,desc`. This is a breaking change of the response shape.

## Consequences

- Operators see changes as they are committed; a console with a stream open polls every 30 s instead of every 2 s
  (`RunStreamControllerTest`, `RunStreamHubTest`, `JpaRunStoreCommitEventsTest`, `RunListingQueriesTest`).
- Clients that parsed the old array must read `items` (the bundled demo scripts never listed runs).
- Rows written before V2 and V3 have NULL listing columns until the run is saved again, so `pendingActions` and
  `progress` can be null for old runs.
- In a multi-node deployment the streams are not a complete feed; a shared broker (for example PostgreSQL
  `LISTEN/NOTIFY` or Redis pub/sub) would make them one, and is not implemented.
- `GET /api/v1/metrics/reliability` still reads every run (`engine.list()`); its result is cached for
  `sdlc.metrics.cache-ttl` (5 s) instead.

## Alternatives considered

- **WebSockets.** Bidirectional, which the console does not need, and harder through proxies than a plain HTTP
  response; SSE carries `Last-Event-ID` resumption for free.
- **Engine `EngineListener` as the source.** Fires before the store commit has been made durable for every
  adapter's transaction semantics and on the engine's thread; the after-commit hook of the store is the point
  where an event is true.
- **Keyset (cursor) pagination.** Stable under concurrent inserts, but offset pages with a total count are what
  the console's pager shows and the run volume of a control plane keeps offsets cheap.
