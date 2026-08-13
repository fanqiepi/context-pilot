CREATE TABLE action_request (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    user_message_id UUID NOT NULL,
    assistant_message_id UUID NOT NULL,
    capability_id VARCHAR(32) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    parameters JSONB NOT NULL,
    display_summary VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_summary VARCHAR(1000),
    error_summary VARCHAR(1000),
    trace_id VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT action_request_conversation_fk
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE RESTRICT,
    CONSTRAINT action_request_user_message_fk
        FOREIGN KEY (user_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT action_request_assistant_message_fk
        FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT action_request_assistant_message_uq UNIQUE (assistant_message_id),
    CONSTRAINT action_request_capability_check CHECK (
        capability_id = 'BUSINESS_ACTION'
        AND length(btrim(capability_version)) > 0
    ),
    CONSTRAINT action_request_action_type_check CHECK (
        action_type = 'CREATE_KNOWLEDGE_BASE'
    ),
    CONSTRAINT action_request_parameters_check CHECK (
        jsonb_typeof(parameters) = 'object'
        AND parameters ? 'name'
        AND jsonb_typeof(parameters -> 'name') = 'string'
        AND length(btrim(parameters ->> 'name')) > 0
        AND (NOT parameters ? 'description' OR jsonb_typeof(parameters -> 'description') = 'string')
    ),
    CONSTRAINT action_request_status_check CHECK (
        status IN (
            'PENDING_CONFIRMATION', 'EXECUTING', 'SUCCEEDED',
            'FAILED', 'REJECTED', 'EXPIRED'
        )
    ),
    CONSTRAINT action_request_summary_not_blank CHECK (length(btrim(display_summary)) > 0),
    CONSTRAINT action_request_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT action_request_deleted_check CHECK (deleted IN (0, 1))
);

CREATE INDEX action_request_conversation_created_at_idx
    ON action_request (conversation_id, created_at, id)
    WHERE deleted = 0;

CREATE INDEX action_request_status_expiry_idx
    ON action_request (status, expires_at, id)
    WHERE deleted = 0 AND status = 'PENDING_CONFIRMATION';

CREATE INDEX action_request_trace_idx
    ON action_request (trace_id)
    WHERE deleted = 0;
