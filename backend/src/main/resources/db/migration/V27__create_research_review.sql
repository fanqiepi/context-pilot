CREATE TABLE research_review (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    task_id uuid NOT NULL REFERENCES research_task(id),
    task_revision bigint NOT NULL,
    round integer NOT NULL,
    client_request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    snapshot_hash varchar(64) NOT NULL,
    snapshot_json jsonb NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    overall_verdict varchar(30),
    result_json jsonb,
    revision bigint NOT NULL DEFAULT 0,
    execution_id uuid,
    error_code varchar(80),
    trace_id varchar(64) NOT NULL,
    prompt_version varchar(60) NOT NULL,
    model varchar(120),
    usage_json jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted integer NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id)
);
CREATE INDEX research_review_task ON research_review(owner_id,task_id,created_at DESC,id DESC);
CREATE UNIQUE INDEX research_review_active ON research_review(owner_id,task_id)
    WHERE status IN ('QUEUED','RUNNING') AND deleted=0;
