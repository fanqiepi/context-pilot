-- Local operator approval only: no model/user-facing authorization creation endpoint.
ALTER TABLE reading_spend_ledger ADD COLUMN approval_digest CHAR(64);
ALTER TABLE reading_spend_ledger ADD COLUMN call_limit INTEGER CHECK (call_limit>0);
ALTER TABLE reading_spend_ledger ADD COLUMN calls_started INTEGER NOT NULL DEFAULT 0 CHECK (calls_started>=0);
ALTER TABLE reading_spend_ledger ADD CHECK (call_limit IS NULL OR calls_started<=call_limit);
ALTER TABLE reading_spend_ledger ADD CHECK ((approval_digest IS NULL) = (call_limit IS NULL));
CREATE UNIQUE INDEX reading_live_approval_digest ON reading_spend_ledger(approval_digest) WHERE approval_digest IS NOT NULL;
ALTER TABLE reading_ai_call ADD COLUMN input_token_bound INTEGER CHECK (input_token_bound>=0);
