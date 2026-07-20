CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT knowledge_base_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT knowledge_base_status_check CHECK (status IN ('ACTIVE'))
);

CREATE UNIQUE INDEX knowledge_base_name_ci_uq
    ON knowledge_base (lower(name));

CREATE INDEX knowledge_base_created_at_idx
    ON knowledge_base (created_at DESC, id DESC);
