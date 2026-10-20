package io.github.fanqiepi.contextpilot.readingtask;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.fanqiepi.contextpilot.book.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingWork.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

class ReadingTaskPolicyTests {
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
            assertThatThrownBy(()->result(raw,p,"CHARACTER_LOOKUP")).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->result("{\"facts\":[],\"partial\":true}",p,"RECAP")).isInstanceOf(ReadingException.class);
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
