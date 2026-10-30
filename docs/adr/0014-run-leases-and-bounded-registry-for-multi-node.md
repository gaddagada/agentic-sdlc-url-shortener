# ADR 0014: Run leases and a bounded run registry for multi-node operation

Status: Accepted (2026-10-30). Amends the single-node scope of
[ADR 0007](0007-virtual-threads-and-per-run-locking.md); the per-run lock stays the mechanism within one node.

## Context

The engine serialized every change of a run with an in-process `ReentrantLock` (ADR 0007). Two control-plane nodes
over one database would each hold their own lock, both could execute the same run, and `recoverInterrupted` at the
start of one node would safe-stop runs that another node was executing. The run registry also kept every run it had
ever loaded in memory for the life of the process. A work queue or a workflow engine (for example Temporal) would
solve distribution but would add infrastructure and a second source of truth next to the run aggregate.

## Decision

- **Leases, not a queue.** A `RunLeaseProvider` SPI grants a time-bounded lease per run to the node executing it:
  acquired when a command or event schedules work, renewed at every mutation, right before every commit and every
  `leaseTtl / 3` while an attempt runs, released when the run waits for a human, is safe-stopped or ends. A node that
  finds its lease taken over writes nothing, abandons the run's attempts and drops the run from memory. Leases are
  not re-entrant; an expired lease can be taken over by any node. A mutation of a run another node holds throws
  `RunLeasedElsewhereException`, which the API answers with `409 run-leased-elsewhere` and the holder. Reads never
  need a lease, and a node refreshes a run from the store when the store holds a newer state.
- **Implementations.** `InMemoryRunLeaseProvider` (core default, one engine) and `JdbcRunLeaseProvider` (control
  plane default, `sdlc.engine.lease-provider=jdbc`): a `run_leases` row per run (Flyway V2), portable conditional
  `INSERT`/`UPDATE`/`DELETE` statements, each in its own `REQUIRES_NEW` transaction so a lease change is visible to
  other nodes at once and a failed run commit cannot roll it back.
- **Node identity and recovery.** `sdlc.engine.node-id` defaults to host name, process id and a random suffix; it is
  recorded in `RUN_CREATED`, `RUN_RESUMED`, `REPLAN`, `RUN_INTERRUPTED` and `STAGE_STARTED`. `recoverInterrupted`
  safe-stops only RUNNING runs without a valid lease. `EngineMaintenance` repeats it every
  `sdlc.engine.maintenance-interval` (30 s), so the runs of a crashed node are recovered once its lease (60 s by
  default) has expired. `close()` releases the node's leases so another node can recover at once. A failed release
  is kept as release-pending (the node's own commands keep working, the run is not evicted, maintenance retries);
  `reclaim` lets a node restarted with a fixed id take back its own lease instead of waiting for expiry.
- **Bounded registry.** Runs not executing on the node are evicted after `registry-retention` (1 h) without a
  change, and terminal runs beyond `registry-max-terminal-runs` (500) least recently ended first; a run that is
  RUNNING, has an attempt in flight or is leased here is never evicted, and a run whose commit failed leaves memory
  at once (the mutation is discarded and the next command reloads the stored state).
  `get` and `list` fall back to the store; a command reloads the run first. `listCached()` serves cheap reads.

## Consequences

- Several control-plane nodes can share one PostgreSQL database; commands on a waiting run work on any node
  (`JdbcLeaseMultiNodeTest`, `OrchestrationEngineLeaseTest`, `RunRegistryEvictionTest`).
- There is no work distribution: a run executes on the node that scheduled its work. A crashed node's runs are
  safe-stopped (not migrated) after lease expiry and continue only when an operator resumes them.
- Expiry is judged with each node's clock: clock skew must stay well below the TTL, and the TTL must outlast the
  longest retry backoff (the control plane refuses to start otherwise), because nothing renews a lease while a
  retry waits.
- Retry timers, provider circuits and the event streams of ADR 0017 remain per node.
- The lease table is one more row write per lease change; the provider is on the command path, so a database outage
  stops commands, as it already did through the run store.

## Alternatives considered

- **Distributed lock service (ZooKeeper, Redis locks).** Equivalent semantics with another dependency; the run
  store's database already provides the atomic compare-and-set a lease needs.
- **Work queue or workflow engine.** Real distribution and durable timers, at the cost of a cluster and a second
  state model; kept as the production path if runs must move between nodes without an operator.
- **Sticky routing at the load balancer.** Cannot survive node loss and does not stop a second node's recovery
  from interfering.
