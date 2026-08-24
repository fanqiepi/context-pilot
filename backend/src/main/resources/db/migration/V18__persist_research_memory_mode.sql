ALTER TABLE research_run
    ADD COLUMN memory_mode VARCHAR(16) NOT NULL DEFAULT 'USE';

ALTER TABLE research_run
    ADD CONSTRAINT research_run_memory_mode_check
        CHECK (memory_mode IN ('USE', 'IGNORE'));
