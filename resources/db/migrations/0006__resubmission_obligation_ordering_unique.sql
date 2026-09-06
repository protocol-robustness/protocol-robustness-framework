-- 0006__resubmission_obligation_ordering_unique.sql
-- P1B closure: one receipt obligation per committed transaction ordering.
-- The unique index is idempotent and is safe to apply to already-migrated stores.

CREATE UNIQUE INDEX IF NOT EXISTS prf_resubmission_receipt_obligation_ordering_uidx
    ON prf_resubmission_receipt_obligation (transaction_ordering_hash);
