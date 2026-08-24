ALTER TABLE action_request
    ADD COLUMN result_resource_id UUID;

ALTER TABLE action_request
    ADD CONSTRAINT action_request_result_memory_fk
        FOREIGN KEY (result_resource_id) REFERENCES user_memory (id) ON DELETE RESTRICT;

ALTER TABLE action_request
    DROP CONSTRAINT action_request_action_type_check,
    DROP CONSTRAINT action_request_parameters_check,
    DROP CONSTRAINT action_request_target_check;

ALTER TABLE action_request
    ADD CONSTRAINT action_request_action_type_check CHECK (
        action_type IN (
            'CREATE_KNOWLEDGE_BASE',
            'RETRY_DOCUMENT_PROCESSING',
            'REINDEX_DOCUMENT',
            'CREATE_USER_MEMORY'
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
            OR (
                action_type = 'CREATE_USER_MEMORY'
                AND parameters ? 'memoryType'
                AND parameters ? 'scope'
                AND parameters ? 'content'
                AND jsonb_typeof(parameters -> 'memoryType') = 'string'
                AND jsonb_typeof(parameters -> 'scope') = 'string'
                AND jsonb_typeof(parameters -> 'content') = 'string'
                AND parameters ->> 'memoryType' = 'RESPONSE_PREFERENCE'
                AND parameters ->> 'scope' = 'GLOBAL'
                AND length(parameters ->> 'content') BETWEEN 1 AND 300
                AND parameters ->> 'content' = btrim(parameters ->> 'content')
                AND (
                    NOT parameters ? 'expiresAt'
                    OR jsonb_typeof(parameters -> 'expiresAt') = 'string'
                )
                AND parameters - 'memoryType' - 'scope' - 'content' - 'expiresAt' = '{}'::jsonb
            )
        )
    ),
    ADD CONSTRAINT action_request_target_check CHECK (
        (
            action_type IN ('CREATE_KNOWLEDGE_BASE', 'CREATE_USER_MEMORY')
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
    ),
    ADD CONSTRAINT action_request_result_resource_check CHECK (
        (
            action_type = 'CREATE_USER_MEMORY'
            AND (
                (status = 'SUCCEEDED' AND result_resource_id IS NOT NULL)
                OR (status <> 'SUCCEEDED' AND result_resource_id IS NULL)
            )
        )
        OR (action_type <> 'CREATE_USER_MEMORY' AND result_resource_id IS NULL)
    );

CREATE INDEX action_request_result_resource_idx
    ON action_request (result_resource_id)
    WHERE deleted = 0 AND result_resource_id IS NOT NULL;

ALTER TABLE chat_message
    DROP CONSTRAINT chat_message_capability_match_reason_check;

ALTER TABLE chat_message
    ADD CONSTRAINT chat_message_capability_match_reason_check CHECK (
        capability_match_reason IS NULL
        OR capability_match_reason IN (
            'SIMPLE_INTERACTION_WHITELIST',
            'EXPLICIT_CREATE_KNOWLEDGE_BASE',
            'EXPLICIT_CREATE_USER_MEMORY',
            'EXPLICIT_KNOWLEDGE_BASE_HEALTH',
            'HEALTH_REPORT_ISSUE_SELECTED',
            'EXPLICIT_DOCUMENT_COMPARISON',
            'DEFAULT_KNOWLEDGE_QA'
        )
    );
