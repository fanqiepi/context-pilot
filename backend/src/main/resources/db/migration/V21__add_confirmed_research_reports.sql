ALTER TABLE action_request ADD COLUMN upgrade_run_id UUID REFERENCES upgrade_research_run(id) ON DELETE RESTRICT;

-- Preserve the exact V1-V5 predicates, adding one explicitly constrained action branch.
DO $$
DECLARE constraint_name TEXT; old_expression TEXT;
BEGIN
    FOREACH constraint_name IN ARRAY ARRAY['action_request_action_type_check','action_request_parameters_check',
        'action_request_target_check','action_request_result_resource_check'] LOOP
        SELECT pg_get_expr(conbin,conrelid) INTO STRICT old_expression
            FROM pg_constraint WHERE conrelid='action_request'::regclass AND conname=constraint_name;
        EXECUTE format('ALTER TABLE action_request DROP CONSTRAINT %I',constraint_name);
        EXECUTE format('ALTER TABLE action_request ADD CONSTRAINT %I CHECK (action_type = ''SAVE_RESEARCH_REPORT'' OR (%s))',constraint_name,old_expression);
    END LOOP;
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT old_expression FROM pg_constraint
        WHERE conrelid='chat_message'::regclass AND conname='chat_message_capability_match_reason_check';
    ALTER TABLE chat_message DROP CONSTRAINT chat_message_capability_match_reason_check;
    EXECUTE format('ALTER TABLE chat_message ADD CONSTRAINT chat_message_capability_match_reason_check CHECK (capability_match_reason = ''UPGRADE_REPORT_SAVE_SELECTED'' OR (%s))',old_expression);
END $$;

ALTER TABLE action_request ADD CONSTRAINT action_request_upgrade_report_check CHECK (
    (action_type <> 'SAVE_RESEARCH_REPORT' AND upgrade_run_id IS NULL)
    OR (action_type='SAVE_RESEARCH_REPORT' AND upgrade_run_id IS NOT NULL
        AND target_document_id IS NULL AND health_issue_id IS NULL
        AND jsonb_typeof(parameters)='object'
        AND parameters ?& ARRAY['runId','draftHash','title']
        AND jsonb_typeof(parameters->'runId')='string' AND parameters->>'runId'=upgrade_run_id::text
        AND jsonb_typeof(parameters->'draftHash')='string' AND parameters->>'draftHash' ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(parameters->'title')='string' AND length(btrim(parameters->>'title')) BETWEEN 1 AND 120
        AND parameters - 'runId' - 'draftHash' - 'title' = '{}'::jsonb
        AND ((status='SUCCEEDED' AND result_resource_id IS NOT NULL) OR (status<>'SUCCEEDED' AND result_resource_id IS NULL)))
);
CREATE INDEX action_request_upgrade_run_idx ON action_request(upgrade_run_id) WHERE upgrade_run_id IS NOT NULL;

CREATE TABLE research_report (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    run_id UUID NOT NULL UNIQUE REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    source_action_id UUID NOT NULL UNIQUE REFERENCES action_request(id) ON DELETE RESTRICT,
    title VARCHAR(120) NOT NULL CHECK (length(btrim(title))>0),
    body TEXT NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 3000),
    draft_hash CHAR(64) NOT NULL CHECK (draft_hash ~ '^[0-9a-f]{64}$'),
    sources JSONB NOT NULL CHECK (jsonb_typeof(sources)='array'),
    gaps JSONB NOT NULL CHECK (jsonb_typeof(gaps)='array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX research_report_owner_kb_idx ON research_report(owner_id,knowledge_base_id,created_at DESC,id DESC) WHERE deleted=0;

-- V17's shared result column originally referenced only user_memory. Keep its foreign-key
-- guarantee through a typed generated column and add an equally strict report branch.
ALTER TABLE action_request DROP CONSTRAINT action_request_result_memory_fk;
ALTER TABLE action_request
    ADD COLUMN memory_result_resource_id UUID GENERATED ALWAYS AS
        (CASE WHEN action_type='CREATE_USER_MEMORY' THEN result_resource_id END) STORED,
    ADD COLUMN report_result_resource_id UUID GENERATED ALWAYS AS
        (CASE WHEN action_type='SAVE_RESEARCH_REPORT' THEN result_resource_id END) STORED;
ALTER TABLE action_request ADD CONSTRAINT action_request_result_memory_fk
    FOREIGN KEY(memory_result_resource_id) REFERENCES user_memory(id) ON DELETE RESTRICT;
ALTER TABLE research_report ADD CONSTRAINT research_report_action_result_uq UNIQUE(id,source_action_id);
ALTER TABLE action_request ADD CONSTRAINT action_request_result_report_fk
    FOREIGN KEY(report_result_resource_id,id) REFERENCES research_report(id,source_action_id) ON DELETE RESTRICT;

CREATE TABLE research_report_save_request (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    run_id UUID NOT NULL REFERENCES upgrade_research_run(id) ON DELETE RESTRICT,
    action_request_id UUID NOT NULL REFERENCES action_request(id) ON DELETE RESTRICT,
    requested_conversation_id UUID REFERENCES conversation(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id)
);
