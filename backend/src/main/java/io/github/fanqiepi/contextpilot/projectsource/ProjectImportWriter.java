package io.github.fanqiepi.contextpilot.projectsource;

import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.ProjectDocumentRegistrationService;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProjectImportWriter {
    private final ProjectPreviewMapper previews;
    private final ProjectImportMapper imports;
    private final ProjectSourcePreviewService snapshots;
    private final CurrentSubjectProvider subjects;
    private final ActionRequestMapper actions;
    private final ProjectDocumentRegistrationService documents;
    private final io.github.fanqiepi.contextpilot.document.StorageService storage;
    public ProjectImportWriter(ProjectPreviewMapper previews,ProjectImportMapper imports,ProjectSourcePreviewService snapshots,
            CurrentSubjectProvider subjects,ActionRequestMapper actions,ProjectDocumentRegistrationService documents,
            io.github.fanqiepi.contextpilot.document.StorageService storage) {
        this.previews=previews;this.imports=imports;this.snapshots=snapshots;this.subjects=subjects;this.actions=actions;this.documents=documents;
        this.storage=storage;
    }
    public ActionExecutionResult execute(ImportProjectDocumentsActionParameters parameters,ActionExecutionContext context) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Import requires action transaction");
        var action=actions.selectById(context.actionRequestId());
        var request=imports.forAction(context.actionRequestId());
        if(action==null || action.getStatus()!=ActionRequestStatus.EXECUTING || action.getActionType()!=ActionType.IMPORT_PROJECT_DOCUMENTS
                || request==null || !request.ownerId().equals(subjects.currentSubject().id())
                || !request.previewId().equals(parameters.previewId()) || !request.knowledgeBaseId().equals(parameters.knowledgeBaseId()))
            throw conflict("SOURCE_CONFIRMATION_REQUIRED");
        var preview=previews.lockOwned(request.ownerId(),parameters.previewId());
        if(preview==null || !"READY".equals(preview.status) || !preview.expiresAt.isAfter(OffsetDateTime.now()))throw conflict("SOURCE_PREVIEW_EXPIRED");
        if(!Integer.valueOf(0).equals(imports.baseDeleted(parameters.knowledgeBaseId())))throw conflict("SOURCE_KNOWLEDGE_BASE_UNAVAILABLE");
        var items=previews.items(preview.id);
        if(items.isEmpty() || items.size()>5)throw conflict("SOURCE_SNAPSHOT_INVALID");
        var staged=imports.staged(context.actionRequestId());
        if(staged.size()!=items.size())throw conflict("SOURCE_STAGING_INVALID");
        // Serialize overlapping imports in this KB before deriving deterministic document IDs.
        previews.lockKey("source-import:"+parameters.knowledgeBaseId());
        Map<UUID,ProjectPreviewMapper.Item> fresh=new LinkedHashMap<>();List<UUID> ids=new ArrayList<>();int bytes=0;
        for(var item:items) {
            snapshots.readSnapshot(item);bytes+=item.byteCount();
            UUID id=documentId(parameters.knowledgeBaseId(),preview,item);
            if(staged.stream().noneMatch(s->s.documentId().equals(id) && s.previewItemId().equals(item.id())
                    && s.storageKey().equals(storageKey(parameters.knowledgeBaseId(),id,item))))throw conflict("SOURCE_STAGING_INVALID");
            Integer deleted=imports.documentDeleted(id);
            if(deleted!=null && deleted!=0)throw conflict("SOURCE_DOCUMENT_DELETED");
            if(deleted==null)fresh.put(id,item);
            else if(imports.origin(id)==null)throw conflict("SOURCE_IMPORT_ID_CONFLICT");
            ids.add(id);
        }
        if(bytes>ProjectSourcePolicy.BATCH_BYTES)throw conflict("SOURCE_BATCH_TOO_LARGE");
        // All expected validation failures occur before any business write.
        for(var entry:fresh.entrySet()) {
            var item=entry.getValue();
            String key=storageKey(parameters.knowledgeBaseId(),entry.getKey(),item);
            if(!storage.exists(key))storage.store(key,new java.io.ByteArrayInputStream(snapshots.readSnapshot(item).getBytes(StandardCharsets.UTF_8)));
            try(var input=storage.open(key)) {
                byte[] bytesStored=input.readNBytes(ProjectSourcePolicy.FILE_BYTES+1);
                if(bytesStored.length!=item.byteCount() || !ProjectSourcePolicy.hash(bytesStored).equals(item.sha256()))
                    throw new ProjectSourceException("SOURCE_STAGED_FILE_INVALID");
            }catch(java.io.IOException e){throw new ProjectSourceException("SOURCE_STAGED_FILE_UNAVAILABLE");}
            documents.register(entry.getKey(),parameters.knowledgeBaseId(),item.path(),item.mediaType(),item.byteCount(),key,item.sha256());
            imports.insertOrigin(new ProjectImportMapper.Origin(entry.getKey(),parameters.knowledgeBaseId(),preview.repository,preview.resolvedCommit,
                    item.path(),item.sha256(),item.fetchedAt(),item.id(),context.actionRequestId()));
        }
        for(UUID id:ids)imports.insertResult(context.actionRequestId(),id);
        return new ActionExecutionResult("已登记 "+ids.size()+" 份项目资料，索引处理结果请查看文档状态。",preview.id);
    }
    private static ConflictException conflict(String code) { return new ConflictException(code,"当前资料状态不允许导入"); }
    static UUID documentId(UUID base,ProjectPreviewEntity preview,ProjectPreviewMapper.Item item){
        return UUID.nameUUIDFromBytes((base+"\n"+preview.repository+"\n"+preview.resolvedCommit+"\n"+item.path()+"\n"+item.sha256()).getBytes(StandardCharsets.UTF_8));
    }
    static String storageKey(UUID base,UUID id,ProjectPreviewMapper.Item item){
        return "project-imports/"+base+"/"+id+"/document."+("text/plain".equals(item.mediaType())?"txt":"md");
    }
}
