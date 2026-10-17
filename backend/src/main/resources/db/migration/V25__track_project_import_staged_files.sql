-- Durable preparation metadata is committed with the proposal, before confirmation.
-- Files left by a rolled-back confirmation remain attributable and safely reusable.
CREATE TABLE project_import_staged_file (
    action_request_id UUID NOT NULL REFERENCES action_request(id) ON DELETE RESTRICT,
    document_id UUID NOT NULL,
    preview_item_id UUID NOT NULL REFERENCES project_source_preview_item(id) ON DELETE RESTRICT,
    storage_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    PRIMARY KEY(action_request_id, document_id)
);
