package io.github.fanqiepi.contextpilot.readingtask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import static org.assertj.core.api.Assertions.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingWork.*;

/** Replays DEBUG evidence with hand-authored responses; does not evaluate a live model. */
class ReadingLookupCompletenessTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();

    @ParameterizedTest @ValueSource(strings={"debug-006","debug-011"})
    void recordedFailuresRetainPartialWhileCompleteFixturesUseAllRelevantEvidence(String caseId) throws Exception {
        var recorded=JSON.readTree(Files.readAllBytes(Path.of("../evals/results/reading/acceptance-v7/debug-v7.json")));
        JsonNode observation=null;
        for(var row:recorded.path("results"))if(caseId.equals(row.path("caseId").asText()))observation=row.path("observation");
        assertThat(observation).isNotNull();
        assertThat(observation.path("terminal").asText()).isEqualTo("PARTIAL");
        var task=observation.path("rawTask");
        var p=new Payload();p.question=task.path("question").asText();
        // Reconstruct only recorded retrieved paragraphs, not a fresh or expanded search.
        var paragraphs=Files.readAllLines(Path.of("../evals/corpora/reading/acceptance-v1/island-debug-v1.txt"))
                .stream().filter(line->!line.isBlank() && !line.startsWith("第")).toList();
        var evidence=new ArrayList<Evidence>();var ids=new HashMap<Integer,String>();
        int boundary=task.path("scope").path("boundaryOrdinal").asInt();
        for(var ordinal:task.path("coverage").path("evidenceRanges")) {
            int n=ordinal.asInt();assertThat(n).isBetween(1,boundary);
            String id="E"+(evidence.size()+1),text=paragraphs.get(n-1);ids.put(n,id);
            evidence.add(new Evidence(id,List.of(new Span(UUID.randomUUID(),UUID.randomUUID(),n,0,
                    ReadingText.length(text),ReadingText.hash(text),text)),text));
        }
        p.evidence=List.copyOf(evidence);
        var old=ReadingText.read(observation.path("answer").asText(),Result.class);
        // The service must not relabel a model's PARTIAL response just because citations exist.
        var oldAssessment=new LookupAnswer(Answerability.PARTIAL,old.facts());
        assertThat(result(ReadingText.json(oldAssessment),p,"CHARACTER_LOOKUP").partial()).isTrue();
        List<Fact> facts;
        if(caseId.equals("debug-006")) {
            assertThat(ids).containsKeys(4,5);
            facts=List.of(new Fact("记录线傍晚断开，陶禾暂停记录。",List.of(ids.get(4))),
                    new Fact("罗澄换好记录线后，测风仪重新出纸。",List.of(ids.get(5))));
        } else {
            assertThat(ids).containsKeys(3,9);
            facts=List.of(new Fact("不是孟秋系的：原文直接叙述陶禾给花盆系上绿色布条。",List.of(ids.get(3))),
                    new Fact("孟秋也说明布条是陶禾系的，不是自己系的。",List.of(ids.get(9))));
        }
        var complete=result(ReadingText.json(new LookupAnswer(Answerability.FULL,facts)),p,"CHARACTER_LOOKUP");
        assertThat(complete.partial()).isFalse();assertThat(complete.facts()).containsExactlyElementsOf(facts);
        var request=JSON.readTree(prompt(p,false));
        assertThat(request.path("promptVersion").asText()).isEqualTo("reading-evidence-v4");
        assertThat(request.path("evidence").size()).isEqualTo(evidence.size());
        assertThat(request.path("system").asText()).contains("单一事项有明确答案即可 FULL","不能把组织遗漏当成证据缺口",
                "另一项确实缺少依据","与直接叙述分别引用").doesNotContain("罗澄","孟秋","陶禾","debug-");
        // Explicit local replay artifacts, never an OBSERVED result or an acceptance score.
        var out=Path.of("target/reading-completeness-offline");Files.createDirectories(out);
        Files.writeString(out.resolve(caseId+"-prompt.json"),prompt(p,false));
        Files.writeString(out.resolve(caseId+"-synthetic-response.json"),ReadingText.json(new LookupAnswer(Answerability.FULL,facts)));
    }

    @ParameterizedTest @ValueSource(strings={"FULL","PARTIAL","INSUFFICIENT_EVIDENCE"})
    void syntheticMissingAspectsAndAttributedClaimsKeepTheirDistinctStates(String assessment) {
        var p=new Payload();p.evidence=List.of(new Evidence("E1",List.of(),"维修员更换阀门，水流恢复。"),
                new Evidence("E2",List.of(),"值班员计划明天测量流量。"));
        var facts=List.of(new Fact("维修员更换阀门，水流恢复。",List.of("E1")));
        p.question=switch(assessment) {
            case "FULL" -> "维修员怎样处理故障？";
            case "PARTIAL" -> "维修员怎样处理故障，次日实际流量是多少？";
            default -> "次日实际流量是多少？";
        };
        var response=new LookupAnswer(Answerability.valueOf(assessment),assessment.equals("INSUFFICIENT_EVIDENCE")?List.of():facts);
        var result=result(ReadingText.json(response),p,"CHARACTER_LOOKUP");
        assertThat(result.partial()).isEqualTo(assessment.equals("PARTIAL"));
        assertThat(result.facts().isEmpty()).isEqualTo(assessment.equals("INSUFFICIENT_EVIDENCE"));
        String system=JSON.readTree(prompt(p,false)).path("system").asText();
        assertThat(system).contains("要求或计划不证明已实现","说法不证明客观事实","没有任何部分能确认",
                "仅有该说法或要求则不能确认","不推断未记载的原因或后续");
        // A question about the plan itself is answerable, without claiming it happened.
        p.question="值班员计划做什么？";
        var attributed=new Fact("值班员计划明天测量流量。",List.of("E2"));
        assertThat(result(ReadingText.json(new LookupAnswer(Answerability.FULL,List.of(attributed))),p,"CHARACTER_LOOKUP").facts())
                .containsExactly(attributed);
    }
}
