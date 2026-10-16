CREATE TABLE upgrade_research_run (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    question VARCHAR(2000) NOT NULL CHECK (length(btrim(question)) > 0),
    document_snapshots JSONB NOT NULL CHECK (jsonb_typeof(document_snapshots) = 'array'
        AND jsonb_array_length(document_snapshots) BETWEEN 1 AND 3),
    repository_key VARCHAR(32) NOT NULL CHECK (repository_key = 'spring-ai'),
    release_tags JSONB NOT NULL CHECK (jsonb_typeof(release_tags) = 'array'
        AND jsonb_array_length(release_tags) BETWEEN 1 AND 3),
    retry_of_run_id UUID REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    budget_version VARCHAR(40) NOT NULL CHECK (budget_version = 'upgrade-budget-v1'),
    prompt_version VARCHAR(40) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','REFUSED','FAILED','CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    deadline TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(100) NOT NULL CHECK (length(btrim(trace_id)) > 0),
    model_calls INTEGER NOT NULL DEFAULT 0 CHECK (model_calls BETWEEN 0 AND 4),
    tool_requests INTEGER NOT NULL DEFAULT 0 CHECK (tool_requests BETWEEN 0 AND 8),
    retrieval_calls INTEGER NOT NULL DEFAULT 0 CHECK (retrieval_calls BETWEEN 0 AND 9),
    http_calls INTEGER NOT NULL DEFAULT 0 CHECK (http_calls BETWEEN 0 AND 3),
    draft TEXT,
    draft_hash CHAR(64),
    gaps JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(gaps) = 'array'),
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (owner_id, client_request_id),
    CHECK (deadline > created_at AND deadline <= created_at + INTERVAL '180 seconds'),
    CHECK ((status IN ('SUCCEEDED','PARTIAL') AND draft IS NOT NULL AND length(btrim(draft)) BETWEEN 1 AND 3000
            AND draft_hash IS NOT NULL AND draft_hash ~ '^[0-9a-f]{64}$')
        OR (status NOT IN ('SUCCEEDED','PARTIAL') AND draft IS NULL AND draft_hash IS NULL)),
    CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
);
CREATE INDEX upgrade_run_owner_kb_idx ON upgrade_research_run(owner_id, knowledge_base_id, created_at DESC, id DESC) WHERE deleted=0;
CREATE INDEX upgrade_run_deadline_idx ON upgrade_research_run(deadline) WHERE status IN ('QUEUED','RUNNING') AND deleted=0;

CREATE TABLE upgrade_research_model_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 4),
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED','SUCCEEDED','FAILED','CANCELLED')),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    total_tokens BIGINT CHECK (total_tokens >= 0),
    latency_ms BIGINT CHECK (latency_ms >= 0),
    error_code VARCHAR(80),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (run_id, ordinal),
    UNIQUE (run_id, id),
    CHECK ((status='STARTED' AND finished_at IS NULL) OR (status<>'STARTED' AND finished_at IS NOT NULL))
);
CREATE TABLE upgrade_research_tool_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 8),
    model_call_id UUID NOT NULL,
    provider_tool_call_id VARCHAR(200) NOT NULL CHECK (length(btrim(provider_tool_call_id))>0),
    tool_name VARCHAR(50) NOT NULL CHECK (tool_name IN ('search_selected_documents','read_evidence','get_release_notes')),
    parameter_hash CHAR(64) NOT NULL CHECK (parameter_hash ~ '^[0-9a-f]{64}$'),
    parameter_summary VARCHAR(300) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED','SUCCEEDED','FAILED','CANCELLED')),
    cache_hit BOOLEAN NOT NULL DEFAULT false,
    error_code VARCHAR(80),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (run_id, ordinal),
    UNIQUE (model_call_id, provider_tool_call_id),
    FOREIGN KEY (run_id, model_call_id) REFERENCES upgrade_research_model_call(run_id,id) ON DELETE RESTRICT,
    CHECK ((status='STARTED' AND finished_at IS NULL) OR (status<>'STARTED' AND finished_at IS NOT NULL))
);
CREATE TABLE upgrade_research_source (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    source_id VARCHAR(40) NOT NULL,
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('LOCAL','RELEASE')),
    document_id UUID REFERENCES source_document(id) ON DELETE RESTRICT,
    chunk_id VARCHAR(100),
    profile_id VARCHAR(100),
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    release_id BIGINT,
    release_tag VARCHAR(100),
    source_url VARCHAR(300),
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    excerpt TEXT NOT NULL CHECK (length(btrim(excerpt)) > 0),
    truncated BOOLEAN NOT NULL,
    prerelease BOOLEAN NOT NULL DEFAULT false,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (run_id, source_id),
    CHECK ((source_type='LOCAL' AND document_id IS NOT NULL AND chunk_id IS NOT NULL AND profile_id IS NOT NULL
        AND release_id IS NULL AND release_tag IS NULL AND source_url IS NULL AND length(excerpt)<=1200)
      OR (source_type='RELEASE' AND document_id IS NULL AND chunk_id IS NULL AND profile_id IS NULL
        AND release_id IS NOT NULL AND release_tag IS NOT NULL
        AND release_tag ~ '^[A-Za-z0-9._-]{1,100}$'
        AND source_url IS NOT NULL AND source_url='https://github.com/spring-projects/spring-ai/releases/tag/' || release_tag
        AND published_at IS NOT NULL AND length(excerpt)<=6000))
);
