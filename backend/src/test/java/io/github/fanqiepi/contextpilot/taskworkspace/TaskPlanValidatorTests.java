package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskPlanValidatorTests {
    private TaskState state() {
        var state=new TaskState(); state.sources=List.of(new TaskState.Source(UUID.randomUUID(),"plan.md","hash","profile","time"));return state;
    }
    @Test void validatesEntirePlanBeforeAnyExecutionAndRejectsForeignToolsAndDocuments() {
        var state=state();var doc=state.sources.getFirst().id();
        var valid=new TaskState.Step("Compare","SEARCH_DOCUMENTS","deployment",List.of(doc));
        assertThat(TaskPlanValidator.plan(TaskJson.write(new TaskState.Plan(List.of(),List.of(valid))),state).steps()).hasSize(1);
        for(var invalid:List.of(new TaskState.Step("escape","HTTP","query",List.of(doc)),
                new TaskState.Step("escape","SEARCH_DOCUMENTS","query",List.of(UUID.randomUUID())),
                new TaskState.Step("repeat","SEARCH_DOCUMENTS","query",List.of(doc,doc)))) {
            assertThatThrownBy(()->TaskPlanValidator.plan(TaskJson.write(new TaskState.Plan(List.of(),List.of(valid,invalid))),state))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void clarificationAndExecutableStepsAreMutuallyExclusive() {
        var state=state();
        assertThat(TaskPlanValidator.plan("{\"questions\":[\"What is the budget?\"],\"steps\":[]}",state).questions()).hasSize(1);
        assertThatThrownBy(()->TaskPlanValidator.plan("{\"questions\":[],\"steps\":[]}",state)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void finalAnswerRequiresOnlyCurrentEvidenceIdsAndBoundedText() {
        var state=state();state.evidence=List.of(new TaskState.Evidence("E1",state.sources.getFirst().id(),"plan.md","chunk",null,"fact"));
        assertThat(TaskPlanValidator.answer("{\"answer\":\"Fact [E1]\"}",state)).isEqualTo("Fact [E1]");
        for(String answer:List.of("unsupported","Fact [E2]","Fact [E1] and fake [E999]","x".repeat(4001)+"[E1]"))
            assertThatThrownBy(()->TaskPlanValidator.answer(TaskJson.write(Map.of("answer",answer)),state)).isInstanceOf(IllegalArgumentException.class);
    }
}
