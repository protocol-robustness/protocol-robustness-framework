-- 0002__benchmark_execution_coordination.sql
--
-- Operational control plane for distributed benchmark chunk execution.
-- These tables do NOT define benchmark/package completion and never store
-- artifact bodies. Canonical completion remains completion.json +
-- verify-benchmark after coordinator reconciliation and publication.

CREATE TABLE IF NOT EXISTS benchmark_execution_run (
    run_id                  TEXT PRIMARY KEY,
    run_plan_root           TEXT NOT NULL,
    execution_plan_root     TEXT NOT NULL,
    coordination_status     TEXT NOT NULL DEFAULT 'dispatching',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT benchmark_execution_run_status
      CHECK (coordination_status IN ('dispatching', 'execution-complete', 'failed'))
);

CREATE TABLE IF NOT EXISTS benchmark_execution_chunk (
    run_id                  TEXT NOT NULL REFERENCES benchmark_execution_run(run_id) ON DELETE CASCADE,
    chunk_id                TEXT NOT NULL,
    expected_work_root      TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'pending',
    worker_id               TEXT,
    fence                   BIGINT NOT NULL DEFAULT 0,
    attempt                 BIGINT NOT NULL DEFAULT 0,
    lease_expires_at        TIMESTAMPTZ,
    result_root             TEXT,
    result_manifest_root    TEXT,
    result_ref              TEXT,
    sensitivity_root        TEXT,
    sensitivity_level       TEXT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, chunk_id),
    CONSTRAINT benchmark_execution_chunk_status
      CHECK (status IN ('pending', 'leased', 'completed', 'failed')),
    CONSTRAINT benchmark_execution_chunk_fence_nonnegative CHECK (fence >= 0),
    CONSTRAINT benchmark_execution_chunk_attempt_nonnegative CHECK (attempt >= 0),
    CONSTRAINT benchmark_execution_chunk_completed_payload
      CHECK ((status <> 'completed') OR
             (result_root IS NOT NULL AND result_manifest_root IS NOT NULL
              AND result_ref IS NOT NULL AND sensitivity_root IS NOT NULL)),
    CONSTRAINT benchmark_execution_chunk_lease_shape
      CHECK ((status = 'leased' AND worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
             OR (status <> 'leased'))
);

-- Claiming scans many independently schedulable fixed chunks. SKIP LOCKED can
-- therefore safely make progress without changing execution-plan semantics.
CREATE INDEX IF NOT EXISTS benchmark_execution_chunk_claim_idx
  ON benchmark_execution_chunk (run_id, status, lease_expires_at, chunk_id);

CREATE OR REPLACE FUNCTION benchmark_execution_touch_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_benchmark_execution_run_touch_updated_at
  ON benchmark_execution_run;
CREATE TRIGGER trg_benchmark_execution_run_touch_updated_at
  BEFORE UPDATE ON benchmark_execution_run
  FOR EACH ROW EXECUTE FUNCTION benchmark_execution_touch_updated_at();

DROP TRIGGER IF EXISTS trg_benchmark_execution_chunk_touch_updated_at
  ON benchmark_execution_chunk;
CREATE TRIGGER trg_benchmark_execution_chunk_touch_updated_at
  BEFORE UPDATE ON benchmark_execution_chunk
  FOR EACH ROW EXECUTE FUNCTION benchmark_execution_touch_updated_at();
