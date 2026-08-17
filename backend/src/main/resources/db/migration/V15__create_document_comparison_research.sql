CREATE TABLE research_run (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_message_id UUID NOT NULL,
    assistant_message_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    plan_version VARCHAR(32) NOT NULL,
    selected_document_ids JSONB NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    answer_status VARCHAR(16),
    max_plan_steps INTEGER NOT NULL,
    max_retrieval_calls INTEGER NOT NULL,
    max_raw_hits INTEGER NOT NULL,
    max_evidence_chunks INTEGER NOT NULL,
    max_evidence_characters INTEGER NOT NULL,
    hard_timeout_millis BIGINT NOT NULL,
    actual_retrieval_calls INTEGER NOT NULL DEFAULT 0,
    actual_raw_hits INTEGER NOT NULL DEFAULT 0,
    actual_evidence_chunks INTEGER NOT NULL DEFAULT 0,
    actual_evidence_characters INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    current_step_ordinal INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    trace_id VARCHAR(100) NOT NULL,
    retry_of_run_id UUID,
    started_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT research_run_knowledge_base_fk FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT research_run_conversation_fk FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE RESTRICT,
    CONSTRAINT research_run_user_message_fk FOREIGN KEY (user_message_id)
        REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT research_run_assistant_message_fk FOREIGN KEY (assistant_message_id)
        REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT research_run_retry_fk FOREIGN KEY (retry_of_run_id)
        REFERENCES research_run (id) ON DELETE RESTRICT,
    CONSTRAINT research_run_task_type_check CHECK (task_type = 'DOCUMENT_COMPARISON'),
    CONSTRAINT research_run_execution_status_check CHECK (execution_status IN (
        'PLANNING', 'EXECUTING', 'SYNTHESIZING',
        'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT research_run_answer_status_check CHECK (
        answer_status IS NULL OR answer_status IN ('ANSWERED', 'REFUSED')
    ),
    CONSTRAINT research_run_terminal_combination_check CHECK (
        (execution_status = 'SUCCEEDED' AND answer_status IN ('ANSWERED', 'REFUSED'))
        OR (execution_status = 'PARTIAL' AND answer_status = 'ANSWERED')
        OR (execution_status IN ('FAILED', 'CANCELLED') AND answer_status IS NULL)
        OR (execution_status IN ('PLANNING', 'EXECUTING', 'SYNTHESIZING') AND answer_status IS NULL)
    ),
    CONSTRAINT research_run_selected_documents_check CHECK (
        jsonb_typeof(selected_document_ids) = 'array'
        AND jsonb_array_length(selected_document_ids) BETWEEN 2 AND 5
    ),
    CONSTRAINT research_run_budget_check CHECK (
        max_plan_steps BETWEEN 1 AND 4
        AND max_retrieval_calls BETWEEN 1 AND 20
        AND max_raw_hits BETWEEN 1 AND 60
        AND max_evidence_chunks BETWEEN 1 AND 24
        AND max_evidence_characters BETWEEN 1 AND 24000
        AND hard_timeout_millis BETWEEN 1 AND 90000
        AND actual_retrieval_calls BETWEEN 0 AND max_retrieval_calls
        AND actual_raw_hits BETWEEN 0 AND max_raw_hits
        AND actual_evidence_chunks BETWEEN 0 AND max_evidence_chunks
        AND actual_evidence_characters BETWEEN 0 AND max_evidence_characters
    ),
    CONSTRAINT research_run_token_check CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
    ),
    CONSTRAINT research_run_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX research_run_client_request_uq
    ON research_run (client_request_id) WHERE deleted = 0;
CREATE UNIQUE INDEX research_run_assistant_message_uq
    ON research_run (assistant_message_id) WHERE deleted = 0;
CREATE INDEX research_run_conversation_created_idx
    ON research_run (conversation_id, created_at DESC, id DESC) WHERE deleted = 0;
CREATE INDEX research_run_active_idx
    ON research_run (execution_status, updated_at, id)
    WHERE deleted = 0 AND execution_status IN ('PLANNING', 'EXECUTING', 'SYNTHESIZING');

CREATE TABLE research_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    goal VARCHAR(300) NOT NULL,
    query VARCHAR(2000) NOT NULL,
    document_ids JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 0,
    retained_evidence_count INTEGER NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    error_summary VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT research_step_run_fk FOREIGN KEY (run_id)
        REFERENCES research_run (id) ON DELETE RESTRICT,
    CONSTRAINT research_step_ordinal_check CHECK (ordinal BETWEEN 1 AND 4),
    CONSTRAINT research_step_goal_check CHECK (length(btrim(goal)) BETWEEN 1 AND 300),
    CONSTRAINT research_step_query_check CHECK (length(btrim(query)) BETWEEN 1 AND 2000),
    CONSTRAINT research_step_documents_check CHECK (
        jsonb_typeof(document_ids) = 'array' AND jsonb_array_length(document_ids) BETWEEN 2 AND 5
    ),
    CONSTRAINT research_step_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT research_step_metrics_check CHECK (
        hit_count >= 0 AND retained_evidence_count >= 0
        AND (latency_ms IS NULL OR latency_ms >= 0)
    ),
    CONSTRAINT research_step_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX research_step_run_ordinal_uq
    ON research_step (run_id, ordinal) WHERE deleted = 0;

CREATE TABLE research_evidence (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    document_id UUID NOT NULL,
    vector_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    chunk_index INTEGER NOT NULL,
    page_number INTEGER,
    embedding_profile_id VARCHAR(100) NOT NULL,
    score DOUBLE PRECISION,
    excerpt VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT research_evidence_run_fk FOREIGN KEY (run_id)
        REFERENCES research_run (id) ON DELETE RESTRICT,
    CONSTRAINT research_evidence_document_fk FOREIGN KEY (document_id)
        REFERENCES source_document (id) ON DELETE RESTRICT,
    CONSTRAINT research_evidence_chunk_check CHECK (chunk_index >= 0),
    CONSTRAINT research_evidence_page_check CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT research_evidence_excerpt_check CHECK (length(excerpt) BETWEEN 1 AND 1000),
    CONSTRAINT research_evidence_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX research_evidence_run_vector_uq
    ON research_evidence (run_id, vector_id) WHERE deleted = 0;

CREATE TABLE research_step_evidence (
    id UUID PRIMARY KEY,
    step_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    rank_index INTEGER NOT NULL,
    score DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT research_step_evidence_step_fk FOREIGN KEY (step_id)
        REFERENCES research_step (id) ON DELETE RESTRICT,
    CONSTRAINT research_step_evidence_evidence_fk FOREIGN KEY (evidence_id)
        REFERENCES research_evidence (id) ON DELETE RESTRICT,
    CONSTRAINT research_step_evidence_rank_check CHECK (rank_index > 0),
    CONSTRAINT research_step_evidence_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX research_step_evidence_pair_uq
    ON research_step_evidence (step_id, evidence_id) WHERE deleted = 0;

ALTER TABLE message_citation
    ADD COLUMN research_evidence_id UUID,
    ADD CONSTRAINT message_citation_research_evidence_fk
        FOREIGN KEY (research_evidence_id) REFERENCES research_evidence (id) ON DELETE RESTRICT;

ALTER TABLE chat_message DROP CONSTRAINT chat_message_status_check;
ALTER TABLE chat_message ADD CONSTRAINT chat_message_status_check
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE chat_message DROP CONSTRAINT chat_message_capability_match_reason_check;
ALTER TABLE chat_message ADD CONSTRAINT chat_message_capability_match_reason_check CHECK (
    capability_match_reason IS NULL
    OR capability_match_reason IN (
        'SIMPLE_INTERACTION_WHITELIST',
        'EXPLICIT_CREATE_KNOWLEDGE_BASE',
        'EXPLICIT_KNOWLEDGE_BASE_HEALTH',
        'HEALTH_REPORT_ISSUE_SELECTED',
        'EXPLICIT_DOCUMENT_COMPARISON',
        'DEFAULT_KNOWLEDGE_QA'
    )
);
