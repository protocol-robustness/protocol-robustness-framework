-- Upper-bound expected_chunk_count to keep the chunk-registration trigger
-- from scanning an unbounded number of rows on a single run.
--
-- Reintroduced from the pre-reset agent-b hardening migration; integration
-- already has the fixed-chunk lifecycle and trigger depth guards in 0003.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'benchmark_execution_run_expected_chunk_count_upper_bound'
    ) THEN
        ALTER TABLE benchmark_execution_run
            ADD CONSTRAINT benchmark_execution_run_expected_chunk_count_upper_bound
            CHECK (expected_chunk_count IS NULL OR expected_chunk_count <= 1000000);
    END IF;
END
$$;
