-- 0003__benchmark_execution_fixed_chunk_lifecycle.sql
--
-- Freeze the complete registration identity for distributed benchmark runs and
-- guard operational lifecycle transitions against direct SQL drift. Legacy rows
-- remain readable but cannot be treated as newly registered fixed-chunk runs.

ALTER TABLE benchmark_execution_run
    ADD COLUMN IF NOT EXISTS chunk_set_root TEXT,
    ADD COLUMN IF NOT EXISTS expected_chunk_count BIGINT,
    ADD COLUMN IF NOT EXISTS registration_complete BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE benchmark_execution_run
    ADD CONSTRAINT benchmark_execution_run_expected_chunk_count_positive
    CHECK (expected_chunk_count IS NULL OR expected_chunk_count > 0);

ALTER TABLE benchmark_execution_run
    ADD CONSTRAINT benchmark_execution_run_registration_shape
    CHECK (
        (registration_complete = FALSE
         AND chunk_set_root IS NULL
         AND expected_chunk_count IS NULL)
        OR
        (registration_complete = TRUE
         AND chunk_set_root IS NOT NULL
         AND btrim(chunk_set_root) <> ''
         AND expected_chunk_count IS NOT NULL
         AND expected_chunk_count > 0)
    );

ALTER TABLE benchmark_execution_chunk
    ADD COLUMN IF NOT EXISTS expected_input_root TEXT;

ALTER TABLE benchmark_execution_chunk
    ADD CONSTRAINT benchmark_execution_chunk_completed_sensitivity_level
    CHECK (status <> 'completed' OR sensitivity_level IN
           ('sensitivity/public', 'sensitivity/internal', 'sensitivity/private',
            'sensitivity/embargoed', 'sensitivity/critical-private'));

CREATE OR REPLACE FUNCTION benchmark_execution_guard_run_transition()
RETURNS trigger AS $$
BEGIN
    IF OLD.registration_complete
       AND (OLD.run_plan_root <> NEW.run_plan_root
            OR OLD.execution_plan_root <> NEW.execution_plan_root
            OR OLD.chunk_set_root IS DISTINCT FROM NEW.chunk_set_root
            OR OLD.expected_chunk_count IS DISTINCT FROM NEW.expected_chunk_count) THEN
        RAISE EXCEPTION 'benchmark execution registration identity is immutable'
          USING ERRCODE = '23514';
    END IF;

    IF OLD.registration_complete AND NOT NEW.registration_complete THEN
        RAISE EXCEPTION 'benchmark execution registration cannot be reopened'
          USING ERRCODE = '23514';
    END IF;

    IF NOT OLD.registration_complete AND NEW.registration_complete
       AND (NEW.chunk_set_root IS NULL OR NEW.expected_chunk_count IS NULL
            OR (SELECT count(*) FROM benchmark_execution_chunk WHERE run_id = OLD.run_id)
               <> NEW.expected_chunk_count
            OR EXISTS (SELECT 1 FROM benchmark_execution_chunk
                       WHERE run_id = OLD.run_id AND expected_input_root IS NULL)) THEN
        RAISE EXCEPTION 'benchmark execution registration is incomplete'
          USING ERRCODE = '23514';
    END IF;

    IF OLD.coordination_status IN ('execution-complete', 'failed')
       AND NEW.coordination_status <> OLD.coordination_status THEN
        RAISE EXCEPTION 'benchmark execution run is terminal: %', OLD.coordination_status
          USING ERRCODE = '23514';
    END IF;

    IF OLD.coordination_status = 'dispatching'
       AND NEW.coordination_status NOT IN ('dispatching', 'execution-complete', 'failed') THEN
        RAISE EXCEPTION 'invalid benchmark execution run transition: % -> %',
          OLD.coordination_status, NEW.coordination_status
          USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_benchmark_execution_guard_run_transition
  ON benchmark_execution_run;
CREATE TRIGGER trg_benchmark_execution_guard_run_transition
BEFORE UPDATE ON benchmark_execution_run
FOR EACH ROW EXECUTE FUNCTION benchmark_execution_guard_run_transition();

CREATE OR REPLACE FUNCTION benchmark_execution_guard_chunk_registration()
RETURNS trigger AS $$
DECLARE
    complete BOOLEAN;
    guarded_run_id TEXT;
BEGIN
    guarded_run_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.run_id ELSE NEW.run_id END;
    SELECT registration_complete INTO complete
      FROM benchmark_execution_run
     WHERE run_id = guarded_run_id
     FOR KEY SHARE;

    IF TG_OP = 'UPDATE'
       AND complete
       AND (OLD.run_id <> NEW.run_id
            OR OLD.chunk_id <> NEW.chunk_id
            OR OLD.expected_input_root IS DISTINCT FROM NEW.expected_input_root
            OR OLD.expected_work_root <> NEW.expected_work_root) THEN
        RAISE EXCEPTION 'benchmark execution chunk descriptor is immutable after registration'
          USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' AND complete THEN
        RAISE EXCEPTION 'benchmark execution chunk set is immutable after registration'
          USING ERRCODE = '23514';
    END IF;

    -- ON DELETE CASCADE invokes this trigger through PostgreSQL's referential
    -- integrity trigger (depth > 1); preserve run cleanup while rejecting a
    -- direct deletion from an otherwise immutable completed registration.
    IF TG_OP = 'DELETE' AND complete AND pg_trigger_depth() = 1 THEN
        RAISE EXCEPTION 'benchmark execution chunk set is immutable after registration'
          USING ERRCODE = '23514';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_benchmark_execution_guard_chunk_registration
  ON benchmark_execution_chunk;
CREATE TRIGGER trg_benchmark_execution_guard_chunk_registration
BEFORE INSERT OR UPDATE OR DELETE ON benchmark_execution_chunk
FOR EACH ROW EXECUTE FUNCTION benchmark_execution_guard_chunk_registration();
