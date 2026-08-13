ALTER TABLE source_document
    ADD COLUMN embedding_profile_id VARCHAR(100),
    ADD COLUMN embedding_provider VARCHAR(32),
    ADD COLUMN embedding_model VARCHAR(100),
    ADD COLUMN embedding_dimensions INTEGER,
    ADD COLUMN embedding_profile_version VARCHAR(32),
    ADD COLUMN indexed_at TIMESTAMPTZ;

ALTER TABLE source_document
    ADD CONSTRAINT source_document_embedding_dimensions_check
        CHECK (embedding_dimensions IS NULL OR embedding_dimensions > 0),
    ADD CONSTRAINT source_document_embedding_index_complete_check
        CHECK (
            (embedding_profile_id IS NULL
                AND embedding_provider IS NULL
                AND embedding_model IS NULL
                AND embedding_dimensions IS NULL
                AND embedding_profile_version IS NULL
                AND indexed_at IS NULL)
            OR
            (embedding_profile_id IS NOT NULL
                AND embedding_provider IS NOT NULL
                AND embedding_model IS NOT NULL
                AND embedding_dimensions IS NOT NULL
                AND embedding_profile_version IS NOT NULL
                AND indexed_at IS NOT NULL
                AND length(btrim(embedding_profile_id)) > 0
                AND length(btrim(embedding_provider)) > 0
                AND length(btrim(embedding_model)) > 0
                AND length(btrim(embedding_profile_version)) > 0)
        );

CREATE INDEX source_document_embedding_profile_idx
    ON source_document (knowledge_base_id, embedding_profile_id)
    WHERE deleted = 0 AND status = 'SUCCEEDED';
