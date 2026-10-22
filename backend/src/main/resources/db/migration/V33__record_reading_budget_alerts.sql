ALTER TABLE reading_spend_ledger ADD COLUMN warning_emitted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reading_spend_ledger ADD COLUMN stop_reason VARCHAR(64);
