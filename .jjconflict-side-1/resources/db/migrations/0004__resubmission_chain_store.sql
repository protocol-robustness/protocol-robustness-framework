-- 0004__resubmission_chain_store.sql
--
-- Durable P1B storage for resubmission chain transactions. A family row is the
-- serialization point: a committing transaction updates chain state/version,
-- inserts its replay record, and inserts its qualifying receipt obligation in
-- the same PostgreSQL transaction.

CREATE TABLE IF NOT EXISTS prf_resubmission_chain_partition (
    family_id       TEXT PRIMARY KEY,
    state_edn       TEXT NOT NULL,
    chain_version   BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT prf_resubmission_chain_version_nonnegative CHECK (chain_version >= 0)
);

CREATE TABLE IF NOT EXISTS prf_resubmission_committed_transaction (
    ordering_hash   TEXT PRIMARY KEY,
    family_id       TEXT NOT NULL REFERENCES prf_resubmission_chain_partition(family_id) ON DELETE RESTRICT,
    record_edn      TEXT NOT NULL,
    committed_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS prf_resubmission_committed_transaction_family_idx
    ON prf_resubmission_committed_transaction (family_id);

CREATE TABLE IF NOT EXISTS prf_resubmission_receipt_obligation (
    obligation_id   TEXT PRIMARY KEY,
    family_id       TEXT NOT NULL REFERENCES prf_resubmission_chain_partition(family_id) ON DELETE RESTRICT,
    transaction_ordering_hash TEXT NOT NULL REFERENCES prf_resubmission_committed_transaction(ordering_hash) ON DELETE RESTRICT,
    status          TEXT NOT NULL,
    issued_receipt_root TEXT,
    entry_edn       TEXT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT prf_resubmission_receipt_obligation_status
      CHECK (status IN ('pending', 'issued')),
    CONSTRAINT prf_resubmission_receipt_obligation_issued_root
      CHECK ((status = 'pending' AND issued_receipt_root IS NULL)
             OR (status = 'issued' AND issued_receipt_root IS NOT NULL))
);

-- Forward-compatible additions for installations that created the first draft
-- of this table before the transaction/root columns were part of the contract.
ALTER TABLE prf_resubmission_receipt_obligation
    ADD COLUMN IF NOT EXISTS transaction_ordering_hash TEXT;
ALTER TABLE prf_resubmission_receipt_obligation
    ADD COLUMN IF NOT EXISTS issued_receipt_root TEXT;

CREATE INDEX IF NOT EXISTS prf_resubmission_receipt_obligation_pending_idx
    ON prf_resubmission_receipt_obligation (family_id, obligation_id)
    WHERE status = 'pending';
