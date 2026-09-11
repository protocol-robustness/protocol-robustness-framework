-- Durable authoritative publication for application-bound pro-rata operations.
-- The partition row is the conflict-key serialization point. A successful
-- transaction retains the immutable ordering and binding before advancing head.

CREATE TABLE IF NOT EXISTS prf_economic_publication_partition (
    partition_id      TEXT PRIMARY KEY,
    conflict_key_edn  TEXT NOT NULL,
    head_edn          TEXT NOT NULL,
    head_root         TEXT NOT NULL,
    store_version     BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT prf_economic_publication_version_nonnegative CHECK (store_version >= 0)
);

CREATE TABLE IF NOT EXISTS prf_economic_publication_ordering (
    ordering_hash     TEXT PRIMARY KEY,
    ordering_edn      TEXT NOT NULL,
    retained_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS prf_economic_publication_binding (
    binding_root      TEXT PRIMARY KEY,
    ordering_hash     TEXT NOT NULL REFERENCES prf_economic_publication_ordering(ordering_hash) ON DELETE RESTRICT,
    binding_edn       TEXT NOT NULL,
    retained_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS prf_economic_publication_binding_ordering_idx
    ON prf_economic_publication_binding (ordering_hash);
