# ADR 0013: Hash-chained audit trail and signed release change records

Status: Accepted (2026-10-30). Amends the audit part of
[ADR 0006](0006-aggregate-snapshot-persistence-with-audit-and-artifact-tables.md).

## Context

The audit log was append-only by contract only: events were numbered per run without gaps and written under the run
lock, but anyone with write access to the database could change, delete or reorder rows without a trace (risk R-20).
The release change record, the document a change board relies on, was an ordinary artifact: a later edit of its
approvals list could not be told apart from the record the engine wrote. A reviewer needs to be able to ask "is this
the trail and the record the engine produced?" and get a mechanical answer.

## Decision

- **Per-run hash chain.** Every `AuditEvent` carries `prevHash` and `hash` when the engine creates it. `hash` is the
  SHA-256 (lowercase hex) of a strict canonical JSON form (`AuditChain.canonicalForm`: `seq`, ISO-8601 UTC
  `timestamp`, `runId`, `stageId`, `type`, `actorType`, `actorId`, `message`, `data` with keys sorted at every level
  and numbers normalized, `prevHash`); the first event links to `AuditChain.GENESIS` (64 zeros). The chain head is
  part of the run snapshot and is resynchronized from the audit sink when a run is loaded or a commit fails, so the
  chain continues across restarts, nodes and failed commits without gaps.
- **Continuity enforced at the store boundary.** `InMemoryAuditSink` and `JpaAuditSink` (inside the commit
  transaction, `AuditChainContinuity`) reject a batch that does not continue the stored head or is not itself an
  intact chain; nothing of that commit is persisted. Events written before the chain existed (`prev_hash` and `hash`
  NULL after Flyway V2) stay readable and are reported as an unsigned chain.
- **Verification.** `AuditChain.verify` reports the first unsigned event after signed ones, foreign run, sequence
  gap or reordering, broken link or modified content, with the expected and the actual hash.
  `GET /api/v1/runs/{id}/audit/integrity` runs it over the stored trail; the console's Audit tab shows the result.
- **Signed change records.** A `ChangeRecordSigner` SPI signs the change record when the engine finalizes it with
  the approvals at run completion. `UnsignedChangeRecordSigner` is the default and never verifies anything (no
  "alg=none"); `HmacChangeRecordSigner` signs the strict canonical JSON of the record without its signature with
  HMAC-SHA256 (key of at least 32 bytes, constant-time verification, key never in `toString`). The control plane
  wires it from `sdlc.governance.signing-key` (raw or `base64:`) and `signing-key-id`; the release artifact view
  carries `signature` and `signatureVerified`. A signer that throws does not block completion: the record stays
  unsigned and the error is audited. The `prod` profile refuses to start without a usable key.

## Consequences

- Editing, inserting, deleting in the middle or reordering events is detected
  (`AuditChainTest`, `JpaAuditSinkTest`, `RunArtifactControllerTest`), and a change record whose approvals were
  edited after signing no longer verifies (`SignedChangeRecordEndToEndTest`).
- The chain is not keyed. Someone who can write the database can recompute every hash after an edit, and cutting
  events off the end is detectable only against a head hash kept elsewhere. Anchoring head hashes outside the
  database (export, write-once storage, a transparency log) is not implemented.
- HMAC is symmetric: whoever can verify can also sign, so the key must be as protected as the signing capability.
  The signature covers the change record, not the audit trail (the record does not include the chain head).
- Strings are hashed exactly as written, so an audit store must not normalize Unicode; Flyway V2 stores timestamps
  in microsecond columns and the engine truncates instants to milliseconds so hashes stay verifiable.
- Hashing adds one SHA-256 per event under the run lock; negligible next to the database write.

## Alternatives considered

- **Event sourcing with the log as the source of truth.** Exact replay, but it would replace the snapshot model of
  ADR 0006 for a property (tamper evidence) that a chain over the existing log provides.
- **Keyed chain (HMAC per event).** Stronger against a database writer, but every node would need the key on the
  hot path, and verification would need it too; signing the one document a change board reads was the better
  place for a key.
- **Asymmetric signatures (KMS or HSM).** The right production choice for non-repudiation; the SPI allows it, but it
  needs infrastructure the project does not assume. HMAC with the JDK only was chosen as the shipped default.
