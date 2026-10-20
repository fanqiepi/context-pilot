-- Reading-only execution records. Existing knowledge/research tables are unchanged.
CREATE TABLE reading_task (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    book_id UUID NOT NULL,
    version_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    type VARCHAR(24) NOT NULL CHECK (type IN ('RECAP','CHARACTER_LOOKUP')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','RUNNING','PAUSED','CANCELLED','INVALIDATED','FAILED','COMPLETED','PARTIAL','INSUFFICIENT_EVIDENCE')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    execution_id UUID,
    required_boundary INTEGER NOT NULL CHECK (required_boundary>0),
    payload_json JSONB NOT NULL,
    error_code VARCHAR(64),
    trace_id UUID NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    active_millis BIGINT NOT NULL DEFAULT 0 CHECK (active_millis>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(version_id,book_id,owner_id) REFERENCES book_version(id,book_id,owner_id),
    UNIQUE(owner_id,client_request_id),
    UNIQUE(id,version_id)
);
CREATE TABLE reading_index_job (
    LIKE reading_task INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES
);
-- LIKE copies CHECKs, but does not copy foreign keys.
ALTER TABLE reading_index_job DROP CONSTRAINT reading_task_type_check;
ALTER TABLE reading_index_job ADD CHECK (type='INDEX');
ALTER TABLE reading_index_job DROP CONSTRAINT reading_task_status_check;
ALTER TABLE reading_index_job ADD CHECK (status IN ('QUEUED','RUNNING','PAUSED','CANCELLED','INVALIDATED','FAILED','READY'));
ALTER TABLE reading_index_job ADD FOREIGN KEY(owner_id) REFERENCES app_user(id);
ALTER TABLE reading_index_job ADD FOREIGN KEY(version_id,book_id,owner_id) REFERENCES book_version(id,book_id,owner_id);
CREATE UNIQUE INDEX reading_index_one_active ON reading_index_job(owner_id,version_id)
    WHERE deleted=0 AND status IN ('QUEUED','RUNNING','PAUSED');
CREATE TABLE reading_job_command (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    task_id UUID REFERENCES reading_task(id),
    index_job_id UUID REFERENCES reading_index_job(id),
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    command VARCHAR(10) NOT NULL CHECK (command IN ('PAUSE','RESUME','CANCEL')),
    result_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK ((task_id IS NULL) <> (index_job_id IS NULL)),
    UNIQUE(owner_id,client_request_id)
);
CREATE TABLE reading_spend_ledger (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    currency VARCHAR(3) NOT NULL CHECK (currency='CNY'),
    limit_amount NUMERIC(18,8) NOT NULL CHECK (limit_amount>0),
    reserved_amount NUMERIC(18,8) NOT NULL DEFAULT 0 CHECK (reserved_amount>=0),
    settled_amount NUMERIC(18,8) NOT NULL DEFAULT 0 CHECK (settled_amount>=0),
    pricing_version VARCHAR(120) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK (reserved_amount+settled_amount<=limit_amount)
);
CREATE TABLE reading_ai_call (
    id UUID PRIMARY KEY,
    task_id UUID REFERENCES reading_task(id),
    index_job_id UUID REFERENCES reading_index_job(id),
    execution_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal>0),
    phase VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN')),
    input_characters INTEGER NOT NULL,
    input_bytes INTEGER NOT NULL,
    output_token_limit INTEGER NOT NULL,
    authorization_id UUID REFERENCES reading_spend_ledger(id),
    reserved_amount NUMERIC(18,8),
    settled_amount NUMERIC(18,8),
    input_token_rate NUMERIC(18,10),
    output_token_rate NUMERIC(18,10),
    CHECK ((authorization_id IS NULL AND reserved_amount IS NULL) OR
           (authorization_id IS NOT NULL AND reserved_amount>0 AND input_token_rate>=0 AND output_token_rate>=0)),
    usage_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK ((task_id IS NULL) <> (index_job_id IS NULL)),
    UNIQUE(task_id,ordinal), UNIQUE(index_job_id,ordinal)
);
CREATE TABLE reading_task_event (
    id UUID PRIMARY KEY,
    task_id UUID REFERENCES reading_task(id),
    index_job_id UUID REFERENCES reading_index_job(id),
    revision BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK ((task_id IS NULL) <> (index_job_id IS NULL)),
    UNIQUE(task_id,revision), UNIQUE(index_job_id,revision)
);
CREATE TABLE reading_task_evidence (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    version_id UUID NOT NULL,
    evidence_key VARCHAR(8) NOT NULL,
    source_json JSONB NOT NULL,
    text_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(task_id,version_id) REFERENCES reading_task(id,version_id),
    UNIQUE(task_id,evidence_key)
);
CREATE TABLE reading_index_segment (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    paragraph_id UUID NOT NULL,
    paragraph_ordinal INTEGER NOT NULL CHECK (paragraph_ordinal>0),
    start_offset INTEGER NOT NULL CHECK (start_offset>=0),
    end_offset INTEGER NOT NULL CHECK (end_offset>start_offset),
    text_hash CHAR(64) NOT NULL,
    profile_id VARCHAR(100) NOT NULL,
    generation UUID NOT NULL,
    status VARCHAR(8) NOT NULL CHECK (status IN ('STAGED','READY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(paragraph_id,version_id) REFERENCES book_paragraph(id,version_id),
    FOREIGN KEY(generation,version_id) REFERENCES reading_index_job(id,version_id),
    UNIQUE(version_id,paragraph_id,profile_id,generation,start_offset,end_offset)
);
CREATE TABLE reading_vector_store (
    id UUID PRIMARY KEY REFERENCES reading_index_segment(id),
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL
);
CREATE INDEX reading_segment_scope ON reading_index_segment(version_id,profile_id,paragraph_ordinal);
