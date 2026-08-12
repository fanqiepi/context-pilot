CREATE TABLE answer_feedback (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT answer_feedback_message_fk
        FOREIGN KEY (message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT answer_feedback_message_uq UNIQUE (message_id),
    CONSTRAINT answer_feedback_deleted_check CHECK (deleted IN (0, 1))
);

CREATE INDEX answer_feedback_created_at_idx
    ON answer_feedback (created_at DESC, id DESC)
    WHERE deleted = 0;
