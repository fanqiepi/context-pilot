ALTER TABLE reading_task ADD UNIQUE(id,version_id,book_id,owner_id);

CREATE TABLE reading_note_proposal (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    book_id UUID NOT NULL,
    version_id UUID NOT NULL,
    task_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('AI_RECAP','AI_CHARACTER')),
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 5000),
    citations_json JSONB NOT NULL CHECK (jsonb_typeof(citations_json)='array'),
    required_boundary INTEGER NOT NULL CHECK (required_boundary>0),
    content_hash CHAR(64) NOT NULL,
    scope_epoch BIGINT NOT NULL CHECK (scope_epoch>=0),
    user_edited BOOLEAN NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_CONFIRMATION','EXECUTING','SUCCEEDED','FAILED','REJECTED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    supersedes_id UUID,
    result_note_id UUID,
    trace_id UUID NOT NULL,
    error_code VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(task_id,version_id,book_id,owner_id) REFERENCES reading_task(id,version_id,book_id,owner_id),
    UNIQUE(owner_id,client_request_id),
    UNIQUE(id,task_id),
    UNIQUE(id,task_id,version_id,book_id,owner_id),
    FOREIGN KEY(supersedes_id,task_id) REFERENCES reading_note_proposal(id,task_id),
    CHECK ((status='SUCCEEDED')=(result_note_id IS NOT NULL))
);
CREATE UNIQUE INDEX reading_note_proposal_pending_uq ON reading_note_proposal(task_id) WHERE status='PENDING_CONFIRMATION';
CREATE INDEX reading_note_proposal_task_idx ON reading_note_proposal(task_id,created_at DESC,id);

ALTER TABLE reading_note DROP CONSTRAINT reading_note_kind_check;
ALTER TABLE reading_note ADD CHECK (kind IN ('MANUAL','EXCERPT','AI_RECAP','AI_CHARACTER'));
ALTER TABLE reading_note ADD COLUMN source_task_id UUID;
ALTER TABLE reading_note ADD COLUMN source_proposal_id UUID;
ALTER TABLE reading_note ADD COLUMN citations_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(citations_json)='array');
ALTER TABLE reading_note ADD COLUMN user_edited BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reading_note ADD UNIQUE(source_task_id);
ALTER TABLE reading_note ADD UNIQUE(source_proposal_id);
ALTER TABLE reading_note ADD UNIQUE(id,source_proposal_id);
ALTER TABLE reading_note ADD FOREIGN KEY(source_proposal_id,source_task_id,version_id,book_id,owner_id)
    REFERENCES reading_note_proposal(id,task_id,version_id,book_id,owner_id);
ALTER TABLE reading_note ADD CHECK (
    (kind IN ('AI_RECAP','AI_CHARACTER') AND source_task_id IS NOT NULL AND source_proposal_id IS NOT NULL AND anchor_paragraph_id IS NULL)
    OR (kind IN ('MANUAL','EXCERPT') AND source_task_id IS NULL AND source_proposal_id IS NULL AND citations_json='[]'::jsonb)
);
ALTER TABLE reading_note_proposal ADD FOREIGN KEY(result_note_id,id)
    REFERENCES reading_note(id,source_proposal_id) DEFERRABLE INITIALLY DEFERRED;
