package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.fanqiepi.contextpilot.taskworkspace.TaskState;
import static org.assertj.core.api.Assertions.*;
import static io.github.fanqiepi.contextpilot.researchreview.ReviewData.*;

class ReviewValidatorTests {
    static Snapshot snapshot() {
        UUID doc=UUID.randomUUID();
        return new Snapshot(UUID.randomUUID(),3,1,UUID.randomUUID(),"Explain scheduling",List.of(),"FIFO scheduling [E1].",
                List.of(new TaskState.Source(doc,"public.md","b".repeat(64),"profile","now")),
                List.of(new TaskState.Evidence("E1",doc,"public.md","chunk",null,"FIFO scheduling; no priority.")));
    }
    static Result result(Verdict verdict) {return new Result(List.of(new Claim("FIFO scheduling",verdict,List.of("E1"),"The source explicitly describes FIFO.")),List.of(),"");}
    @Test void derivesVerdictsWithoutAcceptingModelOverallJudgement() {
        var s=snapshot();ReviewValidator.snapshot(s);assertThat(ReviewValidator.citationIssues(s)).isNull();
        for(var verdict:Verdict.values()) {
            var r=ReviewValidator.result(write(result(verdict)),s);
            assertThat(ReviewValidator.overall(r)).isEqualTo(switch(verdict) {
                case SUPPORTED -> Overall.NO_ISSUES_FOUND;case PARTIAL -> Overall.NEEDS_MORE_EVIDENCE;default -> Overall.ISSUES_FOUND;
            });
        }
        assertThat(ReviewValidator.overall(new Result(result(Verdict.SUPPORTED).claims(),List.of("Priority missing"),""))).isEqualTo(Overall.NEEDS_MORE_EVIDENCE);
    }
    @Test void rejectsForeignIdsInventedClaimsEmptyNullDuplicateAndOversizedOutputs() {
        var s=snapshot();
        for(var r:List.of(new Result(List.of(),List.of(),""),new Result(null,List.of(),""),
                new Result(List.of(new Claim("Invented",Verdict.SUPPORTED,List.of("E1"),"Reason")),List.of(),""),
                new Result(List.of(new Claim("FIFO",Verdict.SUPPORTED,List.of("E99"),"Reason")),List.of(),""),
                new Result(List.of(new Claim("FIFO",Verdict.SUPPORTED,List.of(),"Reason")),List.of(),""),
                new Result(List.of(new Claim("FIFO",Verdict.PARTIAL,List.of("E1","E1"),"Reason")),List.of(),""),
                new Result(result(Verdict.SUPPORTED).claims(),List.of("x".repeat(301)),""),
                new Result(result(Verdict.SUPPORTED).claims(),List.of(),"x".repeat(2001))))
            assertThatThrownBy(()->ReviewValidator.result(write(r),s)).isInstanceOf(IllegalArgumentException.class);
        for(String text:List.of("null","{}","[]","x".repeat(16001),"{\"claims\":[],\"tool\":\"shell\"}"))
            assertThatThrownBy(()->ReviewValidator.result(text,s)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void detectsMissingMalformedAndForeignCitationsBeforeModelCall() {
        var s=snapshot();
        for(String answer:List.of("FIFO", "FIFO [E99]", "FIFO [E1,E2]", "FIFO [E1", "FIFO [E]", "FIFO [E1] then [E2\nbroken")) {
            var bad=new Snapshot(s.taskId(),s.taskRevision(),s.round(),s.knowledgeBaseId(),s.goal(),s.supplements(),answer,s.sources(),s.evidence());
            assertThat(ReviewValidator.overall(ReviewValidator.citationIssues(bad))).isEqualTo(Overall.ISSUES_FOUND);
        }
    }
    @Test void enforcesEvidenceScopeAndBudgets() {
        var s=snapshot();
        var bad=new Snapshot(s.taskId(),s.taskRevision(),s.round(),s.knowledgeBaseId(),s.goal(),s.supplements(),s.answer(),s.sources(),
                List.of(new TaskState.Evidence("E1",UUID.randomUUID(),"foreign","x",null,"secret")));
        assertThatThrownBy(()->ReviewValidator.snapshot(bad)).isInstanceOf(IllegalArgumentException.class);
        var large=new Snapshot(s.taskId(),s.taskRevision(),s.round(),s.knowledgeBaseId(),s.goal(),s.supplements(),"a".repeat(4001),s.sources(),s.evidence());
        assertThatThrownBy(()->ReviewValidator.snapshot(large)).isInstanceOf(IllegalArgumentException.class);
    }
}
