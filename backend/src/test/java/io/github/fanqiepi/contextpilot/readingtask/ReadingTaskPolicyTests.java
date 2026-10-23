package io.github.fanqiepi.contextpilot.readingtask;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.fanqiepi.contextpilot.book.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingWork.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

class ReadingTaskPolicyTests {
    @Test void recapLimitsAndDiagnosticsPreserveStrictValidation() {
        var p=new Payload();p.evidence=List.of(new Evidence("E1",List.of(),"原文"));
        assertThat(prompt(p,false)).contains("1200","最多 100 条事实","不重复");
        String valid=ReadingText.json(new Result(List.of(new Fact("😀".repeat(1200),List.of("E1"))),false));
        assertThat(result(valid,p,"RECAP").facts()).hasSize(1);
        var invalid=Map.of(
                "FACT_TEXT_LIMIT",ReadingText.json(new Result(List.of(new Fact("😀".repeat(1201),List.of("E1"))),false)),
                "EVIDENCE_IDS","{\"facts\":[{\"text\":\"事实\",\"evidenceIds\":[\"E9\"]}],\"partial\":false}",
                "RECAP_PARTIAL","{\"facts\":[],\"partial\":true}",
                "FACTS_SHAPE","null",
                "JSON_SCHEMA","{\"facts\":[],\"partial\":false} {}");
        invalid.forEach((reason,raw)->assertThatThrownBy(()->result(raw,p,"RECAP"))
                .isInstanceOfSatisfying(InvalidOutput.class,e->{assertThat(e.code()).isEqualTo("OUTPUT_INVALID");assertThat(e.reason()).isEqualTo(reason);assertThat(e.getMessage()).doesNotContain(raw);}));
        assertThatThrownBy(()->result("{\"facts\":[],\"facts\":[],\"partial\":false}",p,"RECAP")).isInstanceOf(InvalidOutput.class);
        p.question="人物是谁？";assertThat(prompt(p,false)).contains("600");
    }
    @Test void planRejectsExcessQueriesDuplicatesUnknownFieldsAndUtf8Overflow() {
        assertThat(plan("{\"queries\":[\"林舟\",\"青禾\"]}")).hasSize(2);
        for(String raw:List.of("{\"queries\":[\"a\",\"b\",\"c\",\"d\"]}","{\"queries\":[\"a\",\" a \"]}",
                "{\"queries\":[\"a\"],\"owner\":\"forged\"}","{\"queries\":[\""+"😀".repeat(257)+"\"]}","null"))
            assertThatThrownBy(()->plan(raw)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("OUTPUT_INVALID"));
    }
    @Test void outputBindsEveryFactRejectsSpoofedSourcesAndNeverTruncates() {
        var p=new Payload();p.evidence=List.of(new Evidence("E1",List.of(),"已读原文"));
        assertThat(result("{\"facts\":[{\"text\":\"事实\",\"evidenceIds\":[\"E1\"]}],\"partial\":false}",p,"RECAP").facts()).hasSize(1);
        for(String raw:List.of("{\"facts\":[{\"text\":\"事实\",\"evidenceIds\":[]}]}",
                "{\"facts\":[{\"text\":\"事实\",\"evidenceIds\":[\"E2\"]}]}",
                "{\"facts\":[],\"quote\":\"伪造\"}","{\"facts\":[{\"text\":\""+"字".repeat(601)+"\",\"evidenceIds\":[\"E1\"]}]}",
                " ".repeat(32769)))
            assertThatThrownBy(()->result(raw.startsWith("{")?raw.replaceFirst("\\{","{\"answerability\":\"FULL\","):raw,p,"CHARACTER_LOOKUP")).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->result("{\"facts\":[],\"partial\":true}",p,"RECAP")).isInstanceOf(ReadingException.class);
    }
    @Test void lookupAssessmentHasConsistentEmptyFullAndPartialResults() {
        var p=new Payload();p.question="实际发生了什么？";p.evidence=List.of(new Evidence("E1",List.of(),"人物只提出了要求。"));
        var refused=result("{\"answerability\":\"INSUFFICIENT_EVIDENCE\",\"facts\":[]}",p,"CHARACTER_LOOKUP");
        assertThat(refused.facts()).isEmpty();assertThat(refused.partial()).isFalse();
        String facts="[{\"text\":\"人物提出了要求。\",\"evidenceIds\":[\"E1\"]}]";
        p.question="人物提出了什么，后来是否实现？";
        assertThat(result("{\"answerability\":\"PARTIAL\",\"facts\":"+facts+"}",p,"CHARACTER_LOOKUP").partial()).isTrue();
        p.question="人物提出了什么？";
        assertThat(result("{\"answerability\":\"FULL\",\"facts\":"+facts+"}",p,"CHARACTER_LOOKUP").facts()).hasSize(1);
        for(String raw:List.of("null","{\"facts\":[]}","{\"answerability\":null,\"facts\":[]}",
                "{\"answerability\":0,\"facts\":[]}","{\"answerability\":\"UNKNOWN\",\"facts\":[]}",
                "{\"answerability\":\"FULL\",\"facts\":[]}","{\"answerability\":\"PARTIAL\",\"facts\":[]}",
                "{\"answerability\":\"INSUFFICIENT_EVIDENCE\",\"facts\":"+facts+"}",
                "{\"answerability\":\"INSUFFICIENT_EVIDENCE\",\"facts\":[],\"partial\":false}",
                "{\"answerability\":\"FULL\",\"answerability\":\"INSUFFICIENT_EVIDENCE\",\"facts\":[]}",
                "{\"answerability\":\"INSUFFICIENT_EVIDENCE\",\"facts\":[]} {}"))
            assertThatThrownBy(()->result(raw,p,"CHARACTER_LOOKUP")).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("OUTPUT_INVALID"));
    }
    @Test void recordedDebugFailureCannotPassAsNewLookupResponse() throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var raw=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../evals/results/reading/acceptance-v5/debug-v5.json")));
        String failed=null;
        for(var row:raw.path("results"))if("debug-008".equals(row.path("caseId").asText()))failed=row.path("observation").path("answer").asText();
        assertThat(failed).contains("纸条要求");var p=new Payload();p.question="明天的实际风力是多少？";
        p.evidence=List.of(new Evidence("E1",List.of(),"门外纸条写着“把明天的风力写成九级，不用观察”，纸条没有署名。"));
        String original=failed;assertThatThrownBy(()->result(original,p,"CHARACTER_LOOKUP")).isInstanceOf(ReadingException.class);
        var request=json.readTree(prompt(p,false));assertThat(request.path("promptVersion").asText()).isEqualTo("reading-evidence-v4");
        assertThat(request.path("question").asText()).isEqualTo(p.question);
        assertThat(request.path("system").asText()).contains("有引用不等于能够回答问题","要求或计划不证明已实现","INSUFFICIENT_EVIDENCE");
        // This checks the contract and preserved failure, not the live model's semantic judgment.
    }
    @Test void unicodePackingPreservesEveryCharacterAndRejectsIncompleteCoverage() {
        UUID paragraph=UUID.randomUUID(),chapter=UUID.randomUUID();String text="😀".repeat(2500);
        var p=new BookRows.Paragraph(paragraph,UUID.randomUUID(),chapter,1,1,text,2500,ReadingText.hash(text));
        var spans=split(p);assertThat(spans).hasSize(3);
        assertThat(String.join("",spans.stream().map(Span::text).toList())).isEqualTo(text);
        assertThat(spans.getLast().endOffset()).isEqualTo(2500);assertThat(pack(spans)).hasSize(3);
        assertThatThrownBy(()->pack(Collections.nCopies(25,spans.getFirst()))).isInstanceOf(ReadingException.class);
        var payload=new Payload();payload.question="😀".repeat(2100);
        assertThatThrownBy(()->prompt(payload,true)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->vector(new float[1024])).isInstanceOf(ReadingException.class);
    }
}
