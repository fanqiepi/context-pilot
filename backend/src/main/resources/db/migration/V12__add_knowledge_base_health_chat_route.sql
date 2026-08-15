ALTER TABLE chat_message
    DROP CONSTRAINT chat_message_capability_match_reason_check;

ALTER TABLE chat_message
    ADD CONSTRAINT chat_message_capability_match_reason_check CHECK (
        capability_match_reason IS NULL
        OR capability_match_reason IN (
            'SIMPLE_INTERACTION_WHITELIST',
            'EXPLICIT_CREATE_KNOWLEDGE_BASE',
            'EXPLICIT_KNOWLEDGE_BASE_HEALTH',
            'DEFAULT_KNOWLEDGE_QA'
        )
    );
