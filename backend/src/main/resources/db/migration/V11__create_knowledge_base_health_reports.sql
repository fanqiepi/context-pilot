CREATE TABLE knowledge_base_health_report (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_message_id UUID NOT NULL,
    assistant_message_id UUID NOT NULL,
    capability_id VARCHAR(32) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    health_status VARCHAR(32) NOT NULL,
    completeness VARCHAR(16) NOT NULL,
    completeness_reason VARCHAR(1000),
    data_as_of TIMESTAMPTZ NOT NULL,
    embedding_profile_id VARCHAR(100) NOT NULL,
    embedding_provider VARCHAR(32) NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    embedding_profile_version VARCHAR(32) NOT NULL,
    document_total_count BIGINT NOT NULL,
    document_pending_count BIGINT NOT NULL,
    document_processing_count BIGINT NOT NULL,
    document_succeeded_count BIGINT NOT NULL,
    document_failed_count BIGINT NOT NULL,
    document_deleting_count BIGINT NOT NULL,
    issue_count BIGINT NOT NULL,
    returned_issue_count INTEGER NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT knowledge_base_health_report_knowledge_base_fk
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_report_conversation_fk
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_report_user_message_fk
        FOREIGN KEY (user_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_report_assistant_message_fk
        FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_report_capability_check CHECK (
        capability_id = 'KNOWLEDGE_QA'
        AND length(btrim(capability_version)) > 0
    ),
    CONSTRAINT knowledge_base_health_report_status_check CHECK (
        health_status IN ('EMPTY', 'HEALTHY', 'IN_PROGRESS', 'ATTENTION_REQUIRED', 'UNKNOWN')
    ),
    CONSTRAINT knowledge_base_health_report_completeness_check CHECK (
        completeness IN ('COMPLETE', 'PARTIAL', 'TRUNCATED')
        AND (
            (completeness = 'COMPLETE' AND completeness_reason IS NULL)
            OR (
                completeness IN ('PARTIAL', 'TRUNCATED')
                AND completeness_reason IS NOT NULL
                AND length(btrim(completeness_reason)) > 0
            )
        )
    ),
    CONSTRAINT knowledge_base_health_report_profile_check CHECK (
        length(btrim(embedding_profile_id)) > 0
        AND length(btrim(embedding_provider)) > 0
        AND length(btrim(embedding_model)) > 0
        AND embedding_dimensions > 0
        AND length(btrim(embedding_profile_version)) > 0
    ),
    CONSTRAINT knowledge_base_health_report_document_counts_check CHECK (
        document_total_count >= 0
        AND document_pending_count >= 0
        AND document_processing_count >= 0
        AND document_succeeded_count >= 0
        AND document_failed_count >= 0
        AND document_deleting_count >= 0
        AND document_pending_count + document_processing_count + document_succeeded_count
            + document_failed_count + document_deleting_count = document_total_count
    ),
    CONSTRAINT knowledge_base_health_report_issue_counts_check CHECK (
        issue_count >= 0
        AND returned_issue_count >= 0
        AND returned_issue_count <= 500
        AND returned_issue_count <= issue_count
    ),
    CONSTRAINT knowledge_base_health_report_summary_check CHECK (length(btrim(summary)) > 0),
    CONSTRAINT knowledge_base_health_report_trace_check CHECK (length(btrim(trace_id)) > 0),
    CONSTRAINT knowledge_base_health_report_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX knowledge_base_health_report_assistant_message_uq
    ON knowledge_base_health_report (assistant_message_id)
    WHERE deleted = 0;

CREATE INDEX knowledge_base_health_report_conversation_created_at_idx
    ON knowledge_base_health_report (conversation_id, created_at, id)
    WHERE deleted = 0;

CREATE INDEX knowledge_base_health_report_knowledge_base_data_as_of_idx
    ON knowledge_base_health_report (knowledge_base_id, data_as_of DESC, id DESC)
    WHERE deleted = 0;

CREATE TABLE knowledge_base_health_issue (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    document_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    observed_document_status VARCHAR(32) NOT NULL,
    observed_processing_attempts INTEGER NOT NULL,
    observed_error_summary VARCHAR(1000),
    observed_embedding_profile_id VARCHAR(100),
    observed_vector_count BIGINT,
    source_document_updated_at TIMESTAMPTZ NOT NULL,
    recommended_action_type VARCHAR(64),
    action_eligible BOOLEAN NOT NULL,
    ineligibility_reason_code VARCHAR(64),
    ineligibility_summary VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT knowledge_base_health_issue_report_fk
        FOREIGN KEY (report_id) REFERENCES knowledge_base_health_report (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_issue_document_fk
        FOREIGN KEY (document_id) REFERENCES source_document (id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_base_health_issue_type_check CHECK (
        issue_type IN (
            'DOCUMENT_PROCESSING_FAILED',
            'EMBEDDING_PROFILE_UNKNOWN',
            'EMBEDDING_PROFILE_OUTDATED',
            'VECTOR_INDEX_MISSING'
        )
    ),
    CONSTRAINT knowledge_base_health_issue_severity_check CHECK (severity IN ('ERROR', 'WARNING')),
    CONSTRAINT knowledge_base_health_issue_document_status_check CHECK (
        observed_document_status IN ('FAILED', 'SUCCEEDED')
    ),
    CONSTRAINT knowledge_base_health_issue_attempts_check CHECK (observed_processing_attempts >= 0),
    CONSTRAINT knowledge_base_health_issue_vector_count_check CHECK (
        observed_vector_count IS NULL OR observed_vector_count >= 0
    ),
    CONSTRAINT knowledge_base_health_issue_action_type_check CHECK (
        recommended_action_type IS NULL
        OR recommended_action_type IN ('RETRY_DOCUMENT_PROCESSING', 'REINDEX_DOCUMENT')
    ),
    CONSTRAINT knowledge_base_health_issue_eligibility_check CHECK (
        (
            action_eligible = TRUE
            AND recommended_action_type IS NOT NULL
            AND ineligibility_reason_code IS NULL
            AND ineligibility_summary IS NULL
        )
        OR (
            action_eligible = FALSE
            AND ineligibility_reason_code IN (
                'DOCUMENT_PROCESSING_DISABLED',
                'DOCUMENT_RETRY_LIMIT_REACHED',
                'VECTOR_INDEX_CHECK_UNAVAILABLE'
            )
            AND ineligibility_summary IS NOT NULL
            AND length(btrim(ineligibility_summary)) > 0
        )
    ),
    CONSTRAINT knowledge_base_health_issue_deleted_check CHECK (deleted IN (0, 1))
);

CREATE UNIQUE INDEX knowledge_base_health_issue_report_document_type_uq
    ON knowledge_base_health_issue (report_id, document_id, issue_type)
    WHERE deleted = 0;

CREATE INDEX knowledge_base_health_issue_report_sort_idx
    ON knowledge_base_health_issue (
        report_id, severity, issue_type, source_document_updated_at, document_id, id
    )
    WHERE deleted = 0;

CREATE INDEX vector_store_health_metadata_idx
    ON vector_store (
        (metadata ->> 'knowledge_base_id'),
        (metadata ->> 'document_id'),
        (metadata ->> 'embedding_profile_id')
    );
