-- V2: audit hash chain columns, run leases for multi-node operation, listing columns of runs (H2 2.x and
-- PostgreSQL; portable SQL, same conventions as V1: no CHECK constraints, one ALTER TABLE statement per column).
--
-- audit_events.prev_hash / hash: the per-run SHA-256 hash chain of the engine's audit events (lowercase hex,
--               64 characters). NULL for events written before V2 (legacy, unsigned events).
-- run_leases:   one row per run that an engine node is executing; acquired with INSERT (or a conditional UPDATE of
--               an expired row), renewed and released by token. expires_at is UTC wall-clock time, like every
--               TIMESTAMP column of this schema.
-- runs.*:       queryable copies of snapshot fields for paged listings and filters. Rows written before V2 keep
--               NULLs until the run is saved again.
--
-- TIMESTAMP is TIMESTAMP(6) on both databases (microseconds); the engine truncates instants to milliseconds, so
-- audit timestamps round-trip exactly and the hashes computed over them stay verifiable.

ALTER TABLE audit_events ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN hash VARCHAR(64);

CREATE TABLE run_leases (
    run_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    owner_id   VARCHAR(128) NOT NULL,
    token      VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMP    NOT NULL
);

ALTER TABLE runs ADD COLUMN autonomy VARCHAR(16);
ALTER TABLE runs ADD COLUMN workspace_mode VARCHAR(16);
ALTER TABLE runs ADD COLUMN started_by VARCHAR(128);
ALTER TABLE runs ADD COLUMN risk_level VARCHAR(16);
ALTER TABLE runs ADD COLUMN node_id VARCHAR(128);
ALTER TABLE runs ADD COLUMN audit_head_hash VARCHAR(64);

CREATE INDEX idx_runs_status_updated ON runs (status, updated_at);
CREATE INDEX idx_runs_updated ON runs (updated_at);
