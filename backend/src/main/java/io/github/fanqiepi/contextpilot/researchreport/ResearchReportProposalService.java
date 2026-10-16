package io.github.fanqiepi.contextpilot.researchreport;

import java.time.OffsetDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.chat.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.upgraderesearch.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchReportProposalService {
    private final UpgradeRunMapper runs;
    private final UpgradeRunStateService state;
    private final ResearchReportMapper reports;
    private final CurrentSubjectProvider subjects;
    private final ActionRequestService actions;
    private final ActionRequestMapper actionMapper;
    private final ActionParametersCodec codec;
    private final ActionRequestProperties properties;
    private final ChatPersistenceService chat;
    public ResearchReportProposalService(UpgradeRunMapper runs,UpgradeRunStateService state,ResearchReportMapper reports,
            CurrentSubjectProvider subjects,ActionRequestService actions,ActionRequestMapper actionMapper,ActionParametersCodec codec,
            ActionRequestProperties properties,ChatPersistenceService chat){
        this.runs=runs;this.state=state;this.reports=reports;this.subjects=subjects;this.actions=actions;
        this.actionMapper=actionMapper;this.codec=codec;this.properties=properties;this.chat=chat;
    }
    @Transactional
    public ActionRequestResponse propose(UUID runId,ReportSaveRequest request){
        if(request==null || request.clientRequestId()==null || request.title()==null || request.title().isBlank() || request.title().strip().length()>120)
            throw new BadRequestException("REPORT_REQUEST_INVALID","Invalid report save request");
        var owner=subjects.currentSubject().id();String title=request.title().strip();
        String fingerprint=hash(runId+"\n"+title+"\n"+request.conversationId());
        runs.lockKey("report-save:"+owner+":"+request.clientRequestId());
        var previous=reports.request(owner,request.clientRequestId());
        if(previous!=null){
            if(!fingerprint.equals(previous.requestHash()))throw conflict("REPORT_REQUEST_ID_CONFLICT");
            return actions.get(previous.actionRequestId());
        }
        var run=runs.lockOwned(owner,runId);
        if(run==null)throw new ResourceNotFoundException("UPGRADE_RUN_NOT_FOUND","Research run unavailable");
        var saved=reports.byRun(runId,owner);
        if(saved!=null){
            if(saved.deleted()!=0)throw conflict("REPORT_ALREADY_DELETED");
            link(owner,request,fingerprint,runId,saved.sourceActionId());return actions.get(saved.sourceActionId());
        }
        if(!(run.getStatus()==UpgradeRunStatus.SUCCEEDED || run.getStatus()==UpgradeRunStatus.PARTIAL)
                || run.getDraft()==null || !state.sourcesUnchanged(run))throw conflict("REPORT_DRAFT_UNAVAILABLE");
        chat.validateConversation(request.conversationId(),run.getKnowledgeBaseId());
        UUID active=reports.activeAction(runId);
        if(active!=null){
            var current=actions.get(active);
            if(current.status()==ActionRequestStatus.PENDING_CONFIRMATION || current.status()==ActionRequestStatus.EXECUTING){
                var parameters=(SaveResearchReportActionParameters)codec.read(ActionType.SAVE_RESEARCH_REPORT,actionMapper.selectById(active).getParametersJson());
                if(!title.equals(parameters.title()) || !Objects.equals(request.conversationId(),reports.requestedConversation(active)))
                    throw conflict("REPORT_PROPOSAL_CONFLICT");
                link(owner,request,fingerprint,runId,active);return current;
            }
        }
        var exchange=chat.beginReportSave(request.conversationId(),run.getKnowledgeBaseId(),"保存研究报告："+title,run.getTraceId());
        var now=OffsetDateTime.now(java.time.ZoneOffset.UTC);var action=new ActionRequestEntity();
        action.setId(UUID.randomUUID());action.setConversationId(exchange.conversationId());action.setUserMessageId(exchange.userMessageId());
        action.setAssistantMessageId(exchange.assistantMessageId());action.setCapabilityId(CapabilityId.BUSINESS_ACTION);action.setCapabilityVersion("v6");
        action.setActionType(ActionType.SAVE_RESEARCH_REPORT);action.setUpgradeRunId(runId);
        action.setParametersJson(codec.write(ActionType.SAVE_RESEARCH_REPORT,new SaveResearchReportActionParameters(runId,run.getDraftHash(),title)));
        String summary="确认后保存研究报告“"+title+"”。"+(run.getStatus()==UpgradeRunStatus.PARTIAL?"研究存在资料缺口，报告将保留缺口说明。":"");
        action.setDisplaySummary(summary);action.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);action.setTraceId(run.getTraceId());
        action.setExpiresAt(now.plusMinutes(properties.getConfirmationTimeoutMinutes()));action.setCreatedAt(now);action.setUpdatedAt(now);
        actionMapper.insert(action);chat.completeWithoutModel(exchange.assistantMessageId(),summary);
        link(owner,request,fingerprint,runId,action.getId());return actions.get(action.getId());
    }
    private void link(UUID owner,ReportSaveRequest request,String hash,UUID run,UUID action){
        reports.saveRequest(new ResearchReportMapper.SaveRequest(UUID.randomUUID(),owner,request.clientRequestId(),hash,run,action,request.conversationId()));
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static ConflictException conflict(String code){return new ConflictException(code,"Report save request conflicts with its current state");}
}
