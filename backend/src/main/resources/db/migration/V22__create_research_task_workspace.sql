CREATE TABLE research_task (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    client_request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PLANNING','READY','WAITING_INPUT','RUNNING','PAUSED','FAILED','CANCELLED','COMPLETED','INSUFFICIENT_EVIDENCE')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    execution_id UUID,
    checkpoint JSONB NOT NULL CHECK (jsonb_typeof(checkpoint) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id)
);
CREATE INDEX research_task_owner_idx ON research_task(owner_id,created_at DESC,id DESC) WHERE deleted=0;
CREATE TABLE research_task_event (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES research_task(id),
    revision BIGINT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    checkpoint JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(task_id,revision)
);
