package io.github.fanqiepi.contextpilot.projectsource;

import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectImportProposalService {
    private final ProjectPreviewMapper previews;
    private final ProjectImportMapper imports;
    private final CurrentSubjectProvider subjects;
    private final ActionRequestMapper mapper;
    private final ActionRequestService actions;
    private final ActionParametersCodec codec;
    private final ActionRequestProperties properties;
    public record Request(UUID knowledgeBaseId,UUID clientRequestId) { }
    public ProjectImportProposalService(ProjectPreviewMapper previews,ProjectImportMapper imports,CurrentSubjectProvider subjects,
            ActionRequestMapper mapper,ActionRequestService actions,ActionParametersCodec codec,ActionRequestProperties properties) {
        this.previews=previews;this.imports=imports;this.subjects=subjects;this.mapper=mapper;this.actions=actions;this.codec=codec;this.properties=properties;
    }
    @Transactional public ActionRequestResponse propose(UUID id,Request request) {
        if(request==null || request.knowledgeBaseId()==null || request.clientRequestId()==null)throw new BadRequestException("SOURCE_REQUEST_INVALID","导入参数不完整");
        UUID owner=subjects.currentSubject().id();previews.lockKey("source-proposal:"+owner+":"+request.clientRequestId());
        var previous=imports.request(owner,request.clientRequestId());
        if(previous!=null){
            if(!previous.previewId().equals(id) || !previous.knowledgeBaseId().equals(request.knowledgeBaseId()))throw new ConflictException("SOURCE_REQUEST_ID_CONFLICT","请求编号已使用");
            return actions.get(previous.actionRequestId());
        }
        var preview=previews.lockOwned(owner,id);var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(preview==null)throw new ResourceNotFoundException("SOURCE_PREVIEW_NOT_FOUND","资料预览不存在");
        if(!"READY".equals(preview.status) || !preview.expiresAt.isAfter(now))throw new ConflictException("SOURCE_PREVIEW_UNAVAILABLE","资料预览尚未就绪或已过期");
        if(!Integer.valueOf(0).equals(imports.baseDeleted(request.knowledgeBaseId())))throw new ResourceNotFoundException("KNOWLEDGE_BASE_NOT_FOUND","知识库不存在");
        var action=new ActionRequestEntity();action.setId(UUID.randomUUID());action.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        action.setCapabilityVersion("project-source-v1");action.setActionType(ActionType.IMPORT_PROJECT_DOCUMENTS);
        action.setParametersJson(codec.write(ActionType.IMPORT_PROJECT_DOCUMENTS,new ImportProjectDocumentsActionParameters(id,request.knowledgeBaseId())));
        action.setDisplaySummary("确认后将 "+previews.items(id).size()+" 份项目资料登记到所选知识库并开始索引处理。");
        action.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);action.setTraceId(preview.traceId);
        var expiry=now.plusMinutes(properties.getConfirmationTimeoutMinutes());action.setExpiresAt(expiry.isBefore(preview.expiresAt)?expiry:preview.expiresAt);
        action.setCreatedAt(now);action.setUpdatedAt(now);mapper.insert(action);
        imports.insertRequest(new ProjectImportMapper.Request(UUID.randomUUID(),owner,request.clientRequestId(),id,request.knowledgeBaseId(),action.getId()));
        for(var item:previews.items(id)){
            UUID documentId=ProjectImportWriter.documentId(request.knowledgeBaseId(),preview,item);
            imports.stage(new ProjectImportMapper.StagedFile(action.getId(),documentId,item.id(),ProjectImportWriter.storageKey(request.knowledgeBaseId(),documentId,item)));
        }
        return actions.get(action.getId());
    }
}
