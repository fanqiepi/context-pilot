CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT conversation_knowledge_base_fk
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT conversation_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT conversation_deleted_check CHECK (deleted IN (0, 1))
);

CREATE INDEX conversation_created_at_idx
    ON conversation (created_at DESC, id DESC)
    WHERE deleted = 0;

CREATE INDEX conversation_knowledge_base_idx
    ON conversation (knowledge_base_id, updated_at DESC, id DESC)
    WHERE deleted = 0;

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_summary VARCHAR(1000),
    trace_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT chat_message_conversation_fk
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE RESTRICT,
    CONSTRAINT chat_message_role_check CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chat_message_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chat_message_deleted_check CHECK (deleted IN (0, 1))
);

CREATE INDEX chat_message_conversation_created_at_idx
    ON chat_message (conversation_id, created_at, id)
    WHERE deleted = 0;

CREATE TABLE message_citation (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    document_id UUID NOT NULL,
    chunk_id VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    chunk_index INTEGER NOT NULL,
    page_number INTEGER,
    rank_index INTEGER NOT NULL,
    score DOUBLE PRECISION,
    excerpt VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT message_citation_message_fk
        FOREIGN KEY (message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT message_citation_document_fk
        FOREIGN KEY (document_id) REFERENCES source_document (id) ON DELETE RESTRICT,
    CONSTRAINT message_citation_chunk_index_check CHECK (chunk_index >= 0),
    CONSTRAINT message_citation_page_number_check CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT message_citation_rank_check CHECK (rank_index > 0),
    CONSTRAINT message_citation_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX message_citation_message_rank_uq
    ON message_citation (message_id, rank_index)
    WHERE deleted = 0;

CREATE TABLE model_call (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    latency_ms BIGINT,
    error_summary VARCHAR(1000),
    trace_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT model_call_message_fk
        FOREIGN KEY (message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT model_call_status_check CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT model_call_tokens_check CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
    ),
    CONSTRAINT model_call_latency_check CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT model_call_deleted_check CHECK (deleted IN (0, 1))
);

CREATE INDEX model_call_message_idx
    ON model_call (message_id, created_at DESC, id DESC)
    WHERE deleted = 0;

CREATE INDEX model_call_trace_idx
    ON model_call (trace_id)
    WHERE deleted = 0;
