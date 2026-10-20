package io.github.fanqiepi.contextpilot.readingnote;

import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import io.github.fanqiepi.contextpilot.readingtask.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ReadingNoteProposalService {
    public record Create(UUID clientRequestId,String title,String body,List<String> retainedEvidenceIds,
                         Long expectedScopeRevision,UUID supersedesProposalId) {}
    public record Confirm(String contentHash) {}
    public record Registered(UUID id,boolean created) {}
    public record Confirmation(int httpStatus,Map<String,Object> body) {}
    private final ReadingNoteProposalMapper proposals;
    private final ReadingNoteMapper notes;
    private final ReadingTaskMapper tasks;
    private final ReadingStateService states;
    private final ReadingScopeService scopes;

    public ReadingNoteProposalService(ReadingNoteProposalMapper proposals,ReadingNoteMapper notes,
            ReadingTaskMapper tasks,ReadingStateService states,ReadingScopeService scopes) {
        this.proposals=proposals;this.notes=notes;this.tasks=tasks;this.states=states;this.scopes=scopes;
    }
    private ReadingWork task(UUID id) {
        var t=tasks.get(false,id);
        if(t==null || t.deleted!=0 || !t.ownerId.equals(states.owner()))throw ReadingException.missing();
        return t;
    }
    private ReadingNoteProposal owned(UUID id) {
        var p=proposals.owned(states.owner(),id);
        if(p==null)throw ReadingException.missing();
        return p;
    }
    private static void completed(ReadingWork t) {
        if(!Set.of("COMPLETED","PARTIAL").contains(t.status))
            throw ReadingException.conflict("TASK_NOT_SAVEABLE","仅可保存已完成且有依据的任务结果");
    }
    private void transition(ReadingNoteProposal p,String status,String code) {
        p.status=status;p.errorCode=code;proposals.transition(p);p.revision++;
    }
    private void expire(ReadingNoteProposal p) {
        if(p!=null && "PENDING_CONFIRMATION".equals(p.status) && !p.expiresAt.isAfter(OffsetDateTime.now()))
            transition(p,"EXPIRED","PROPOSAL_EXPIRED");
    }
    public Registered create(UUID taskId,Create r) {
        ReadingText.require(r.clientRequestId()!=null && r.expectedScopeRevision()!=null,"缺少请求标识或范围修订号");
        String title=ReadingText.bounded(r.title(),0,120,"标题"),body=ReadingText.bounded(r.body(),1,5000,"笔记");
        ReadingText.require(r.retainedEvidenceIds()!=null && r.retainedEvidenceIds().size()<=24
                && r.retainedEvidenceIds().stream().allMatch(Objects::nonNull),"引用选择不合法");
        var keys=r.retainedEvidenceIds().stream().sorted().toList();
        String requestHash=ReadingText.hash(ReadingText.json(Arrays.asList(taskId,title,body,keys,r.expectedScopeRevision(),r.supersedesProposalId())));
        var initial=task(taskId);
        return scopes.current(initial.versionId,s->{
            var t=task(taskId);
            var previous=proposals.replay(s.ownerId(),r.clientRequestId());
            if(previous!=null) {
                if(!previous.requestHash.equals(requestHash))throw ReadingException.conflict("IDEMPOTENCY_CONFLICT","请求标识对应其他内容");
                if(previous.deleted!=0)throw new ReadingException(410,"RESOURCE_REMOVED","提案已移除");
                var previousNote=previous.resultNoteId==null?null:notes.owned(s.ownerId(),previous.resultNoteId);
                if(previousNote!=null && previousNote.deleted!=0)
                    throw new ReadingException(410,"NOTE_ALREADY_SAVED_REMOVED","本任务笔记已移除，不会重复创建");
                return new Registered(previous.id,false);
            }
            ReadingScopeService.requireVisible(s,t.versionId,t.requiredBoundary);
            var saved=notes.byTask(s.ownerId(),taskId);
            if(saved!=null) {
                if(saved.deleted!=0)throw new ReadingException(410,"NOTE_ALREADY_SAVED_REMOVED","本任务笔记已移除，不会重复创建");
                return new Registered(saved.sourceProposalId,false);
            }
            ReadingStateService.revision(s.scopeRevision(),r.expectedScopeRevision());
            completed(t);
            var payload=ReadingText.read(t.payloadJson,ReadingWork.Payload.class);
            if(payload.result==null || payload.result.facts().isEmpty())throw ReadingException.conflict("TASK_NOT_SAVEABLE","任务没有可保存的事实");
            var allKeys=payload.result.facts().stream().flatMap(f->f.evidenceIds().stream()).distinct().toList();
            ReadingText.require(allKeys.containsAll(keys) && new HashSet<>(keys).size()==keys.size(),"引用只能从任务结果中删减");
            var evidence=new ArrayList<ReadingWork.Evidence>();
            for(String key:keys) {
                var e=taskEvidence(t,key);
                ReadingNoteEvidence.sources(e,scopes,s,t.requiredBoundary);evidence.add(e);
            }
            var active=proposals.latest(s.ownerId(),taskId);expire(active);
            if(active!=null && "PENDING_CONFIRMATION".equals(active.status)) {
                if(!active.id.equals(r.supersedesProposalId()))
                    throw ReadingException.conflict("PROPOSAL_PENDING","已有待确认预览，请读取或明确替代它");
                transition(active,"REJECTED","SUPERSEDED");
            } else if(r.supersedesProposalId()!=null) {
                // A replaced/expired preview cannot overwrite a newer decision.
                throw ReadingException.conflict("PROPOSAL_CHANGED","原预览已失效，请重新读取当前状态");
            }
            var p=new ReadingNoteProposal();p.id=UUID.randomUUID();p.ownerId=s.ownerId();p.bookId=s.bookId();p.versionId=s.versionId();
            p.taskId=taskId;p.clientRequestId=r.clientRequestId();p.requestHash=requestHash;p.title=title;p.body=body;
            p.kind="RECAP".equals(t.type)?"AI_RECAP":"AI_CHARACTER";p.citationsJson=ReadingText.json(evidence);
            // User editing at a later boundary must never lower the full task input dependency.
            p.requiredBoundary=Math.max(t.requiredBoundary,s.boundaryOrdinal());p.scopeEpoch=s.visibilityEpoch();
            String original=String.join("\n",payload.result.facts().stream().map(ReadingWork.Fact::text).toList());
            p.userEdited=!body.equals(original) || !new HashSet<>(keys).equals(new HashSet<>(allKeys))
                    || !title.equals("RECAP".equals(t.type)?"前情回顾":"人物查证");
            p.status="PENDING_CONFIRMATION";p.expiresAt=OffsetDateTime.now().plusMinutes(30);
            p.supersedesId=r.supersedesProposalId();p.traceId=UUID.randomUUID();p.contentHash=hash(p);
            proposals.create(p);return new Registered(p.id,true);
        });
    }
    private ReadingWork.Evidence taskEvidence(ReadingWork t,String key) {
        String json=tasks.evidenceJson(t.id,key);
        if(json==null)throw ReadingException.conflict("EVIDENCE_INVALID","来源引用已不可用");
        var e=ReadingText.read(json,ReadingWork.Evidence.class);
        if(!e.id().equals(key) || e.sources().isEmpty() || e.sources().stream().anyMatch(span->span.paragraphOrdinal()>t.requiredBoundary))
            throw ReadingException.conflict("EVIDENCE_INVALID","来源引用校验失败");
        return e;
    }
    private static String hash(ReadingNoteProposal p) {
        return ReadingText.hash(ReadingText.json(Arrays.asList(p.ownerId,p.bookId,p.versionId,p.taskId,p.kind,p.title,p.body,
                ReadingNoteEvidence.read(p.citationsJson),p.requiredBoundary,p.scopeEpoch,p.userEdited)));
    }
    public Object latest(UUID taskId) {
        var t=task(taskId);
        return scopes.current(t.versionId,s->{task(taskId);var p=proposals.latest(s.ownerId(),taskId);expire(p);return p==null?null:view(p,s);});
    }
    public Object get(UUID id) {
        var p=owned(id);
        return scopes.current(p.versionId,s->{var current=owned(id);expire(current);return view(current,s);});
    }
    private Map<String,Object> view(ReadingNoteProposal p,ReadingScopeService.Snapshot s) {
        var v=new LinkedHashMap<String,Object>();v.put("id",p.id);v.put("status",p.status);v.put("revision",p.revision);
        if(!ReadingScopeService.visible(s,p.versionId,p.requiredBoundary)) {v.put("visibility","HIDDEN_BY_SCOPE");return v;}
        v.put("visibility","VISIBLE");v.put("taskId",p.taskId);v.put("versionId",p.versionId);v.put("kind",p.kind);
        v.put("title",p.title);v.put("body",p.body);v.put("retainedEvidenceIds",ReadingNoteEvidence.ids(p.citationsJson));
        v.put("requiredBoundary",p.requiredBoundary);v.put("contentHash",p.contentHash);v.put("expiresAt",p.expiresAt);
        v.put("resultNoteId",p.resultNoteId);v.put("userEdited",p.userEdited);v.put("errorCode",p.errorCode);
        var note=p.resultNoteId==null?null:notes.owned(s.ownerId(),p.resultNoteId);
        v.put("resultNoteRemoved",note!=null && note.deleted!=0);
        v.put("scopeRevision",s.scopeRevision());v.put("visibilityEpoch",s.visibilityEpoch());v.put("dataAsOf",s.dataAsOf());
        return v;
    }
    public Confirmation confirm(UUID id,Confirm r) {
        ReadingText.require(r.contentHash()!=null,"缺少最终预览校验值");
        var first=owned(id);
        try {
            // Returning failures (rather than throwing inside the transaction) preserves their auditable terminal state.
            return scopes.current(first.versionId,s->{
                var p=owned(id);expire(p);
                if("SUCCEEDED".equals(p.status)) {
                    if(!p.contentHash.equals(r.contentHash()))return response(p,s,409,"PREVIEW_CHANGED");
                    var note=notes.owned(s.ownerId(),p.resultNoteId);
                    return response(p,s,note!=null && note.deleted!=0?410:200,note!=null && note.deleted!=0?"NOTE_ALREADY_SAVED_REMOVED":null);
                }
                if(!"PENDING_CONFIRMATION".equals(p.status))
                    return response(p,s,"EXPIRED".equals(p.status)?410:409,p.errorCode==null?"PROPOSAL_NOT_PENDING":p.errorCode);
                String failure=null;
                if(!p.contentHash.equals(r.contentHash()) || !p.contentHash.equals(hash(p)))failure="PREVIEW_CHANGED";
                else if(!ReadingScopeService.visible(s,p.versionId,p.requiredBoundary) || p.scopeEpoch!=s.visibilityEpoch())failure="SCOPE_CHANGED";
                else {
                    try {
                        var t=task(p.taskId);completed(t);
                        if(!t.versionId.equals(p.versionId) || !t.bookId.equals(p.bookId) || t.requiredBoundary>p.requiredBoundary)
                            throw ReadingException.conflict("SOURCE_CHANGED","任务来源已变化");
                        for(var e:ReadingNoteEvidence.read(p.citationsJson)) {
                            if(!e.equals(taskEvidence(t,e.id())))throw ReadingException.conflict("EVIDENCE_INVALID","引用已变化");
                            ReadingNoteEvidence.sources(e,scopes,s,p.requiredBoundary);
                        }
                    } catch(ReadingException e) {failure=e.status()==404?"SOURCE_REMOVED":e.code();}
                }
                if(failure!=null) {transition(p,"FAILED",failure);return response(p,s,409,failure);}
                var existing=notes.byTask(s.ownerId(),p.taskId);
                if(existing!=null) {transition(p,"FAILED","NOTE_ALREADY_SAVED");return response(p,s,409,"NOTE_ALREADY_SAVED");}
                transition(p,"EXECUTING",null);
                var note=new ReadingNote();note.id=UUID.randomUUID();note.ownerId=p.ownerId;note.bookId=p.bookId;note.versionId=p.versionId;
                // The proposal FK is the effect key. Do not collide with caller-chosen manual-note request IDs.
                note.clientRequestId=UUID.randomUUID();note.requestHash=p.contentHash;note.kind=p.kind;note.title=p.title;note.body=p.body;
                note.sourceTaskId=p.taskId;note.sourceProposalId=p.id;note.citationsJson=p.citationsJson;note.userEdited=p.userEdited;
                note.requiredBoundary=p.requiredBoundary;notes.create(note);
                p.resultNoteId=note.id;transition(p,"SUCCEEDED",null);
                return response(p,s,200,null);
            });
        } catch(DataAccessException | TransactionException e) {
            throw new ReadingException(503,"SAVE_RETRYABLE","保存结果暂未确认，请查询提案状态后重试相同确认请求");
        }
    }
    private Confirmation response(ReadingNoteProposal p,ReadingScopeService.Snapshot s,int status,String code) {
        var body=view(p,s);
        if(code!=null) {body.put("code",code);body.put("message","预览状态已变化，请读取当前状态后操作");}
        return new Confirmation(status,body);
    }
    public Object reject(UUID id) {
        var first=owned(id);
        return scopes.current(first.versionId,s->{
            var p=owned(id);expire(p);
            if("SUCCEEDED".equals(p.status))throw ReadingException.conflict("PROPOSAL_SUCCEEDED","已保存笔记不能拒绝，请在笔记管理中编辑或移除");
            if("PENDING_CONFIRMATION".equals(p.status))transition(p,"REJECTED","USER_REJECTED");
            return view(p,s);
        });
    }
}
