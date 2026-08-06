ALTER TABLE source_document
    ADD COLUMN processing_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE source_document
    ADD CONSTRAINT source_document_processing_attempts_check
        CHECK (processing_attempts >= 0);
