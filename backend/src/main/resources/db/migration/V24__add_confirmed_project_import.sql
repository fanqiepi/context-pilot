ALTER TABLE action_request ALTER COLUMN conversation_id DROP NOT NULL;
ALTER TABLE action_request ALTER COLUMN user_message_id DROP NOT NULL;
ALTER TABLE action_request ALTER COLUMN assistant_message_id DROP NOT NULL;
ALTER TABLE action_request ADD CONSTRAINT action_request_chat_origin_check CHECK (
    (action_type='IMPORT_PROJECT_DOCUMENTS' AND conversation_id IS NULL AND user_message_id IS NULL AND assistant_message_id IS NULL)
    OR (action_type<>'IMPORT_PROJECT_DOCUMENTS' AND conversation_id IS NOT NULL AND user_message_id IS NOT NULL AND assistant_message_id IS NOT NULL)
);
DO $$
DECLARE constraint_name TEXT; old_expression TEXT;
BEGIN
    FOREACH constraint_name IN ARRAY ARRAY['action_request_action_type_check','action_request_parameters_check',
        'action_request_target_check','action_request_result_resource_check'] LOOP
        SELECT pg_get_expr(conbin,conrelid) INTO STRICT old_expression
            FROM pg_constraint WHERE conrelid='action_request'::regclass AND conname=constraint_name;
        EXECUTE format('ALTER TABLE action_request DROP CONSTRAINT %I',constraint_name);
        EXECUTE format('ALTER TABLE action_request ADD CONSTRAINT %I CHECK (action_type = ''IMPORT_PROJECT_DOCUMENTS'' OR (%s))',constraint_name,old_expression);
    END LOOP;
END $$;
ALTER TABLE action_request ADD CONSTRAINT action_request_project_import_check CHECK (
    action_type <> 'IMPORT_PROJECT_DOCUMENTS' OR (
        target_document_id IS NULL AND health_issue_id IS NULL AND upgrade_run_id IS NULL
        AND jsonb_typeof(parameters)='object'
        AND parameters ?& ARRAY['previewId','knowledgeBaseId']
        AND jsonb_typeof(parameters->'previewId')='string'
        AND jsonb_typeof(parameters->'knowledgeBaseId')='string'
        AND parameters - 'previewId' - 'knowledgeBaseId' = '{}'::jsonb
        AND ((status='SUCCEEDED' AND result_resource_id=(parameters->>'previewId')::uuid)
            OR (status<>'SUCCEEDED' AND result_resource_id IS NULL))
    )
);
CREATE TABLE project_import_request (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    client_request_id UUID NOT NULL,
    preview_id UUID NOT NULL REFERENCES project_source_preview(id) ON DELETE RESTRICT,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    action_request_id UUID NOT NULL UNIQUE REFERENCES action_request(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id)
);
CREATE TABLE project_document_origin (
    document_id UUID PRIMARY KEY REFERENCES source_document(id) ON DELETE RESTRICT,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    repository VARCHAR(100) NOT NULL CHECK (repository='spring-projects/spring-ai'),
    commit CHAR(40) NOT NULL CHECK (commit ~ '^[0-9a-f]{40}$'),
    path VARCHAR(400) NOT NULL,
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    fetched_at TIMESTAMPTZ NOT NULL,
    preview_item_id UUID NOT NULL REFERENCES project_source_preview_item(id) ON DELETE RESTRICT,
    action_proposal_id UUID NOT NULL REFERENCES action_request(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(knowledge_base_id,repository,commit,path,sha256)
);
CREATE TABLE project_import_result (
    action_request_id UUID NOT NULL REFERENCES action_request(id) ON DELETE RESTRICT,
    document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    PRIMARY KEY(action_request_id,document_id)
);
