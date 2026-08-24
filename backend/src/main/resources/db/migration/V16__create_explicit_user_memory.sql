CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    user_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT app_user_key_not_blank CHECK (length(btrim(user_key)) > 0),
    CONSTRAINT app_user_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT app_user_status_check CHECK (status = 'ACTIVE'),
    CONSTRAINT app_user_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX app_user_key_uq
    ON app_user (user_key)
    WHERE deleted = 0;

INSERT INTO app_user (id, user_key, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'LOCAL_OWNER', 'Local owner', 'ACTIVE');

CREATE TABLE user_memory (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_revision INTEGER NOT NULL,
    content_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT user_memory_owner_fk
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT user_memory_type_check CHECK (memory_type = 'RESPONSE_PREFERENCE'),
    CONSTRAINT user_memory_scope_check CHECK (scope_type = 'GLOBAL'),
    CONSTRAINT user_memory_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT user_memory_revision_positive CHECK (current_revision > 0),
    CONSTRAINT user_memory_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT user_memory_version_positive CHECK (version > 0),
    CONSTRAINT user_memory_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX user_memory_active_hash_uq
    ON user_memory (owner_user_id, memory_type, scope_type, content_hash)
    WHERE deleted = 0 AND status = 'ACTIVE';

CREATE INDEX user_memory_owner_status_updated_idx
    ON user_memory (owner_user_id, status, updated_at DESC, id DESC)
    WHERE deleted = 0;

CREATE INDEX user_memory_owner_expiry_idx
    ON user_memory (owner_user_id, expires_at, id)
    WHERE deleted = 0 AND status = 'ACTIVE' AND expires_at IS NOT NULL;

CREATE TABLE user_memory_revision (
    id UUID PRIMARY KEY,
    memory_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    content VARCHAR(300) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_conversation_id UUID,
    source_message_id UUID,
    change_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT user_memory_revision_memory_fk
        FOREIGN KEY (memory_id) REFERENCES user_memory (id) ON DELETE RESTRICT,
    CONSTRAINT user_memory_revision_conversation_fk
        FOREIGN KEY (source_conversation_id) REFERENCES conversation (id) ON DELETE RESTRICT,
    CONSTRAINT user_memory_revision_message_fk
        FOREIGN KEY (source_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT user_memory_revision_number_positive CHECK (revision_number > 0),
    CONSTRAINT user_memory_revision_content_check CHECK (
        length(content) BETWEEN 1 AND 300 AND content = btrim(content)
    ),
    CONSTRAINT user_memory_revision_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT user_memory_revision_source_check CHECK (
        (source_type = 'CHAT_CONFIRMED'
            AND source_conversation_id IS NOT NULL
            AND source_message_id IS NOT NULL)
        OR (source_type = 'MANAGEMENT'
            AND source_conversation_id IS NULL
            AND source_message_id IS NULL)
    ),
    CONSTRAINT user_memory_revision_reason_not_blank CHECK (length(btrim(change_reason)) > 0),
    CONSTRAINT user_memory_revision_deleted_check CHECK (deleted IN (0, 1)),
    CONSTRAINT user_memory_revision_number_uq UNIQUE (memory_id, revision_number),
    CONSTRAINT user_memory_revision_identity_uq UNIQUE (id, memory_id)
);

ALTER TABLE user_memory
    ADD CONSTRAINT user_memory_current_revision_fk
        FOREIGN KEY (id, current_revision)
        REFERENCES user_memory_revision (memory_id, revision_number)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX user_memory_revision_history_idx
    ON user_memory_revision (memory_id, revision_number DESC)
    WHERE deleted = 0;

CREATE TABLE message_memory_context (
    id UUID PRIMARY KEY,
    assistant_message_id UUID NOT NULL,
    memory_id UUID NOT NULL,
    memory_revision_id UUID NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT message_memory_context_message_fk
        FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT message_memory_context_memory_fk
        FOREIGN KEY (memory_id) REFERENCES user_memory (id) ON DELETE RESTRICT,
    CONSTRAINT message_memory_context_revision_fk
        FOREIGN KEY (memory_revision_id, memory_id)
        REFERENCES user_memory_revision (id, memory_id) ON DELETE RESTRICT,
    CONSTRAINT message_memory_context_order_positive CHECK (display_order > 0),
    CONSTRAINT message_memory_context_deleted_check CHECK (deleted IN (0, 1)),
    CONSTRAINT message_memory_context_revision_uq
        UNIQUE (assistant_message_id, memory_revision_id),
    CONSTRAINT message_memory_context_order_uq
        UNIQUE (assistant_message_id, display_order)
);

CREATE INDEX message_memory_context_message_idx
    ON message_memory_context (assistant_message_id, display_order)
    WHERE deleted = 0;
