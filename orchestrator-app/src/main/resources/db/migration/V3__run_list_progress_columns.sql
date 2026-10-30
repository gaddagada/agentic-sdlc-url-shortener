-- V3: progress columns of the paged runs list and indexes of its filters (H2 2.x and PostgreSQL; portable SQL, same
-- conventions as V1 and V2: no CHECK constraints, one ALTER TABLE statement per column).
--
-- runs.pending_actions:   stages waiting for a human (approval or clarification) after the last save.
-- runs.stages_succeeded:  stages in SUCCEEDED after the last save.
-- runs.stages_total:      stages of the run's workflow.
-- Like the V2 listing columns they are copies of snapshot data, written with every save, so GET /api/v1/runs never
-- reads a snapshot. Rows written before V3 keep NULLs until the run is saved again.

ALTER TABLE runs ADD COLUMN pending_actions INT;
ALTER TABLE runs ADD COLUMN stages_succeeded INT;
ALTER TABLE runs ADD COLUMN stages_total INT;

CREATE INDEX idx_runs_scenario_updated ON runs (scenario, updated_at);
CREATE INDEX idx_runs_started_by_updated ON runs (started_by, updated_at);
