package io.github.fanqiepi.contextpilot.projectsource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProjectImportMapper {
    record StagedFile(UUID actionRequestId,UUID documentId,UUID previewItemId,String storageKey) { }
    @Insert("INSERT INTO project_import_staged_file(action_request_id,document_id,preview_item_id,storage_key) VALUES(#{actionRequestId},#{documentId},#{previewItemId},#{storageKey})")
    int stage(StagedFile file);
    @Select("SELECT action_request_id,document_id,preview_item_id,storage_key FROM project_import_staged_file WHERE action_request_id=#{id} AND deleted=0 ORDER BY document_id")
    List<StagedFile> staged(UUID id);
    record Request(UUID id, UUID ownerId, UUID clientRequestId, UUID previewId, UUID knowledgeBaseId, UUID actionRequestId) { }
    @Select("SELECT id,owner_id,client_request_id,preview_id,knowledge_base_id,action_request_id FROM project_import_request WHERE owner_id=#{owner} AND client_request_id=#{key} AND deleted=0")
    Request request(@Param("owner") UUID owner,@Param("key") UUID key);
    @Select("SELECT id,owner_id,client_request_id,preview_id,knowledge_base_id,action_request_id FROM project_import_request WHERE action_request_id=#{id} AND deleted=0")
    Request forAction(UUID id);
    @Insert("INSERT INTO project_import_request(id,owner_id,client_request_id,preview_id,knowledge_base_id,action_request_id) VALUES(#{id},#{ownerId},#{clientRequestId},#{previewId},#{knowledgeBaseId},#{actionRequestId})")
    int insertRequest(Request request);
    record Origin(UUID documentId, UUID knowledgeBaseId, String repository, String commit, String path, String sha256,
            OffsetDateTime fetchedAt, UUID previewItemId, UUID actionProposalId) { }
    @Select("SELECT document_id,knowledge_base_id,repository,commit,path,sha256,fetched_at,preview_item_id,action_proposal_id FROM project_document_origin WHERE document_id=#{id} AND deleted=0")
    Origin origin(UUID id);
    @Insert("""
        INSERT INTO project_document_origin(document_id,knowledge_base_id,repository,commit,path,sha256,fetched_at,preview_item_id,action_proposal_id)
        VALUES(#{documentId},#{knowledgeBaseId},#{repository},#{commit},#{path},#{sha256},#{fetchedAt},#{previewItemId},#{actionProposalId})
        """)
    int insertOrigin(Origin origin);
    @Select("SELECT deleted FROM source_document WHERE id=#{id} FOR UPDATE")
    Integer documentDeleted(UUID id);
    @Select("SELECT deleted FROM knowledge_base WHERE id=#{id} FOR SHARE")
    Integer baseDeleted(UUID id);
    @Insert("INSERT INTO project_import_result(action_request_id,document_id) VALUES(#{action},#{document})")
    int insertResult(@Param("action") UUID action,@Param("document") UUID document);
    @Select("SELECT document_id FROM project_import_result WHERE action_request_id=#{id} AND deleted=0 ORDER BY document_id")
    List<UUID> results(UUID id);
}
