-- 0001__admission_partition.sql
--
-- PostgreSQL schema for the authoritative resubmission admission store.
--
-- Serialization point: each admission family partition is exactly one row in
-- `prf_resubmission_admission_partition`. All authoritative mutations lock that
-- row (SELECT ... FOR UPDATE) inside a SERIALIZABLE transaction, obtain ONE
-- database time value, evaluate the pure transition, and update the row before
-- commit. Ordinary row locking plus declarative constraints provide the
-- correctness boundary; no application-level mutex is authoritative.
--
-- Forward-only: scripts after this are appended, never this file edited.

-- The family partition row holds the pixel-complete pure admission state
-- (`state_edn`) plus structured authority columns that the adapter keeps in
-- sync so the database can enforce and expose the monotonic fence/version and
-- act as a queryable serialization point.
CREATE TABLE IF NOT EXISTS prf_resubmission_admission_partition (
    partition_key      TEXT     PRIMARY KEY,
    -- Monotonic concurrency fence for this family partition. Every reservation
    -- grant and terminal transition advances it exactly once.
    concurrency_fence  BIGINT   NOT NULL DEFAULT 0,
    -- Advances only on canonical finalization (family head/version publication).
    family_version     BIGINT   NOT NULL DEFAULT 0,
    -- Canonical serialized pure admission state (the authoritative payload).
    state_edn          TEXT     NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT prf_admission_fence_nonneg   CHECK (concurrency_fence >= 0),
    CONSTRAINT prf_admission_version_nonneg CHECK (family_version >= 0)
);

-- Accommodate any pre-existing dev copy of the old 3-column table created by the
-- legacy ad-hoc `ensure-schema!` before migrations existed. These adds are
-- no-ops on a fresh database and are still forward-only.
ALTER TABLE prf_resubmission_admission_partition
    ADD COLUMN IF NOT EXISTS concurrency_fence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE prf_resubmission_admission_partition
    ADD COLUMN IF NOT EXISTS family_version    BIGINT NOT NULL DEFAULT 0;

-- Index supporting authoritative transaction paths (family snapshot lookups
-- and diagnostics). partition_key is already unique via the PRIMARY KEY.
CREATE INDEX IF NOT EXISTS prf_admission_partition_family_idx
    ON prf_resubmission_admission_partition (partition_key);

-- Keep updated_at honest under explicit updates as well as the trigger-free
-- DEFAULT above.
CREATE OR REPLACE FUNCTION prf_admission_touch_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prf_admission_touch_updated_at
    ON prf_resubmission_admission_partition;
CREATE TRIGGER trg_prf_admission_touch_updated_at
    BEFORE UPDATE ON prf_resubmission_admission_partition
    FOR EACH ROW EXECUTE FUNCTION prf_admission_touch_updated_at();