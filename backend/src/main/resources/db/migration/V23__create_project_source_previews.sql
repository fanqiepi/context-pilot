CREATE TABLE project_source_preview (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    repository VARCHAR(100) NOT NULL CHECK (repository = 'spring-projects/spring-ai'),
    requested_ref VARCHAR(100) NOT NULL,
    paths_json TEXT NOT NULL CHECK (jsonb_typeof(paths_json::jsonb) = 'array'),
    resolved_commit CHAR(40),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','RUNNING','READY','FAILED')),
    error_code VARCHAR(100),
    trace_id VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id, client_request_id),
    CHECK (resolved_commit IS NULL OR resolved_commit ~ '^[0-9a-f]{40}$'),
    CHECK (status <> 'READY' OR resolved_commit IS NOT NULL)
);
CREATE TABLE project_source_preview_item (
    id UUID PRIMARY KEY,
    preview_id UUID NOT NULL REFERENCES project_source_preview(id) ON DELETE RESTRICT,
    path VARCHAR(400) NOT NULL,
    media_type VARCHAR(50) NOT NULL CHECK (media_type IN ('text/plain','text/markdown')),
    byte_count INTEGER NOT NULL CHECK (byte_count BETWEEN 1 AND 131072),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    snapshot_storage_key VARCHAR(500) NOT NULL UNIQUE,
    fetched_at TIMESTAMPTZ NOT NULL,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(preview_id, path)
);
CREATE INDEX project_source_preview_owner_idx ON project_source_preview(owner_id,created_at DESC) WHERE deleted=0;
