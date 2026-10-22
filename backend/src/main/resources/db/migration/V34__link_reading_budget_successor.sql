-- A stopped provider ledger may transfer its remaining allowance once only.
ALTER TABLE reading_spend_ledger ADD COLUMN predecessor_id UUID REFERENCES reading_spend_ledger(id);
ALTER TABLE reading_spend_ledger ADD CONSTRAINT reading_budget_one_successor UNIQUE (predecessor_id);
ALTER TABLE reading_spend_ledger ADD CONSTRAINT reading_budget_not_self CHECK (predecessor_id <> id);
