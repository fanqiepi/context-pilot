ALTER TABLE message_memory_context
    ADD COLUMN context_character_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN context_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT message_memory_context_character_count_non_negative
        CHECK (context_character_count >= 0);
