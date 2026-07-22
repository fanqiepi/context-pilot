CREATE TABLE source_document (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    media_type VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_summary VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_document_knowledge_base_fk
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT source_document_file_type_check
        CHECK (file_type IN ('TXT', 'MARKDOWN', 'PDF')),
    CONSTRAINT source_document_size_check CHECK (size_bytes > 0),
    CONSTRAINT source_document_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT source_document_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DELETING')),
    CONSTRAINT source_document_storage_key_uq UNIQUE (storage_key)
);

CREATE INDEX source_document_knowledge_base_created_at_idx
    ON source_document (knowledge_base_id, created_at DESC, id DESC);
