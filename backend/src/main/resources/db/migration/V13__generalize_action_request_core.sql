ALTER TABLE action_request
    ADD COLUMN target_document_id UUID,
    ADD COLUMN health_issue_id UUID;

ALTER TABLE action_request
    ADD CONSTRAINT action_request_target_document_fk
        FOREIGN KEY (target_document_id) REFERENCES source_document (id) ON DELETE RESTRICT,
    ADD CONSTRAINT action_request_health_issue_fk
        FOREIGN KEY (health_issue_id) REFERENCES knowledge_base_health_issue (id) ON DELETE RESTRICT;

ALTER TABLE action_request
    DROP CONSTRAINT action_request_action_type_check,
    DROP CONSTRAINT action_request_parameters_check;

ALTER TABLE action_request
    ADD CONSTRAINT action_request_action_type_check CHECK (
        action_type IN (
            'CREATE_KNOWLEDGE_BASE',
            'RETRY_DOCUMENT_PROCESSING',
            'REINDEX_DOCUMENT'
        )
    ),
    ADD CONSTRAINT action_request_parameters_check CHECK (
        jsonb_typeof(parameters) = 'object'
        AND (
            (
                action_type = 'CREATE_KNOWLEDGE_BASE'
                AND parameters ? 'name'
                AND jsonb_typeof(parameters -> 'name') = 'string'
                AND length(btrim(parameters ->> 'name')) > 0
                AND (
                    NOT parameters ? 'description'
                    OR jsonb_typeof(parameters -> 'description') = 'string'
                )
                AND parameters - 'name' - 'description' = '{}'::jsonb
            )
            OR (
                action_type = 'RETRY_DOCUMENT_PROCESSING'
                AND parameters ? 'documentId'
                AND parameters ? 'originalFilenameSnapshot'
                AND parameters ? 'observedDocumentStatus'
                AND parameters ? 'healthReportId'
                AND parameters ? 'healthIssueId'
                AND jsonb_typeof(parameters -> 'documentId') = 'string'
                AND jsonb_typeof(parameters -> 'originalFilenameSnapshot') = 'string'
                AND jsonb_typeof(parameters -> 'observedDocumentStatus') = 'string'
                AND jsonb_typeof(parameters -> 'healthReportId') = 'string'
                AND jsonb_typeof(parameters -> 'healthIssueId') = 'string'
                AND length(btrim(parameters ->> 'originalFilenameSnapshot')) > 0
                AND parameters ->> 'observedDocumentStatus' = 'FAILED'
                AND parameters - 'documentId' - 'originalFilenameSnapshot'
                    - 'observedDocumentStatus' - 'healthReportId' - 'healthIssueId' = '{}'::jsonb
            )
            OR (
                action_type = 'REINDEX_DOCUMENT'
                AND parameters ? 'documentId'
                AND parameters ? 'originalFilenameSnapshot'
                AND parameters ? 'observedDocumentStatus'
                AND parameters ? 'healthReportId'
                AND parameters ? 'healthIssueId'
                AND jsonb_typeof(parameters -> 'documentId') = 'string'
                AND jsonb_typeof(parameters -> 'originalFilenameSnapshot') = 'string'
                AND jsonb_typeof(parameters -> 'observedDocumentStatus') = 'string'
                AND jsonb_typeof(parameters -> 'healthReportId') = 'string'
                AND jsonb_typeof(parameters -> 'healthIssueId') = 'string'
                AND length(btrim(parameters ->> 'originalFilenameSnapshot')) > 0
                AND parameters ->> 'observedDocumentStatus' = 'SUCCEEDED'
                AND (
                    NOT parameters ? 'observedEmbeddingProfileId'
                    OR (
                        jsonb_typeof(parameters -> 'observedEmbeddingProfileId') = 'string'
                        AND length(btrim(parameters ->> 'observedEmbeddingProfileId')) > 0
                    )
                )
                AND parameters - 'documentId' - 'originalFilenameSnapshot'
                    - 'observedDocumentStatus' - 'observedEmbeddingProfileId'
                    - 'healthReportId' - 'healthIssueId' = '{}'::jsonb
            )
        )
    ),
    ADD CONSTRAINT action_request_target_check CHECK (
        (
            action_type = 'CREATE_KNOWLEDGE_BASE'
            AND target_document_id IS NULL
            AND health_issue_id IS NULL
        )
        OR (
            action_type IN ('RETRY_DOCUMENT_PROCESSING', 'REINDEX_DOCUMENT')
            AND target_document_id IS NOT NULL
            AND health_issue_id IS NOT NULL
            AND parameters ->> 'documentId' = target_document_id::text
            AND parameters ->> 'healthIssueId' = health_issue_id::text
        )
    );

CREATE UNIQUE INDEX action_request_health_issue_uq
    ON action_request (health_issue_id)
    WHERE deleted = 0 AND health_issue_id IS NOT NULL;
