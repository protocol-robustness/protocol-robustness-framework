-- 0005__resubmission_obligation_integrity.sql
-- Forward-only integrity completion for the P1B receipt-obligation store.

ALTER TABLE prf_resubmission_receipt_obligation
    ADD COLUMN IF NOT EXISTS transaction_ordering_hash TEXT;

ALTER TABLE prf_resubmission_receipt_obligation
    ADD COLUMN IF NOT EXISTS issued_receipt_root TEXT;

-- The initial draft did not retain a SQL-visible transaction anchor. The
-- canonical adapter writes the explicit column for all new rows; legacy rows
-- must be absent before this migration can be strict.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM prf_resubmission_receipt_obligation
             WHERE transaction_ordering_hash IS NULL) THEN
    RAISE EXCEPTION 'cannot complete obligation migration: legacy transaction anchor unavailable';
  END IF;
END $$;

ALTER TABLE prf_resubmission_receipt_obligation
    ALTER COLUMN transaction_ordering_hash SET NOT NULL;

ALTER TABLE prf_resubmission_receipt_obligation
    ADD CONSTRAINT prf_receipt_obligation_transaction_fk
    FOREIGN KEY (transaction_ordering_hash)
    REFERENCES prf_resubmission_committed_transaction(ordering_hash)
    ON DELETE RESTRICT;

ALTER TABLE prf_resubmission_receipt_obligation
    ADD CONSTRAINT prf_receipt_obligation_status_root_ck
    CHECK ((status = 'pending' AND issued_receipt_root IS NULL)
           OR (status = 'issued' AND issued_receipt_root IS NOT NULL));
