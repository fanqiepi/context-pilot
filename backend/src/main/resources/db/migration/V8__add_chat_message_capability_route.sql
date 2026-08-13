ALTER TABLE chat_message
    ADD COLUMN capability_id VARCHAR(32),
    ADD COLUMN capability_version VARCHAR(32),
    ADD COLUMN capability_match_reason VARCHAR(64),
    ADD CONSTRAINT chat_message_capability_id_check CHECK (
        capability_id IS NULL
        OR capability_id IN ('SIMPLE_CHAT', 'KNOWLEDGE_QA', 'BUSINESS_ACTION')
    ),
    ADD CONSTRAINT chat_message_capability_match_reason_check CHECK (
        capability_match_reason IS NULL
        OR capability_match_reason IN (
            'SIMPLE_INTERACTION_WHITELIST',
            'EXPLICIT_CREATE_KNOWLEDGE_BASE',
            'DEFAULT_KNOWLEDGE_QA'
        )
    ),
    ADD CONSTRAINT chat_message_capability_route_check CHECK (
        (
            capability_id IS NULL
            AND capability_version IS NULL
            AND capability_match_reason IS NULL
        )
        OR (
            capability_id IS NOT NULL
            AND capability_version IS NOT NULL
            AND capability_match_reason IS NOT NULL
            AND length(btrim(capability_version)) > 0
            AND length(btrim(capability_match_reason)) > 0
        )
    );

CREATE INDEX chat_message_capability_created_at_idx
    ON chat_message (capability_id, created_at DESC, id DESC)
    WHERE deleted = 0 AND capability_id IS NOT NULL;
