ALTER TABLE knowledge_base
    ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT knowledge_base_deleted_check CHECK (deleted IN (0, 1));

ALTER TABLE source_document
    ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT source_document_deleted_check CHECK (deleted IN (0, 1));

DROP INDEX knowledge_base_name_ci_uq;

CREATE UNIQUE INDEX knowledge_base_name_ci_uq
    ON knowledge_base (lower(name))
    WHERE deleted = 0;
