package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import java.util.regex.Pattern;

final class TaskPlanValidator {
    static TaskState.Plan plan(String text,TaskState state) {
        if(text==null || text.length()>10000) throw invalid();
        var plan=TaskJson.read(text,TaskState.Plan.class);
        if(plan==null || plan.questions()==null || plan.steps()==null || plan.questions().size()>3
                || plan.steps().size()>4 || plan.questions().isEmpty()==plan.steps().isEmpty()) throw invalid();
        var allowed=new HashSet<>(state.sources.stream().map(TaskState.Source::id).toList());
        for(String question:plan.questions()) if(!bounded(question,300)) throw invalid();
        for(var step:plan.steps()) {
            if(step==null || !bounded(step.title(),100) || !"SEARCH_DOCUMENTS".equals(step.tool())
                    || !bounded(step.query(),500) || step.documentIds()==null || step.documentIds().isEmpty()
                    || step.documentIds().size()>5 || !allowed.containsAll(step.documentIds())
                    || new HashSet<>(step.documentIds()).size()!=step.documentIds().size()) throw invalid();
        }
        return plan;
    }
    record Answer(String answer) { }
    static String answer(String text,TaskState state) {
        if(text==null || text.length()>12000) throw invalid();
        var result=TaskJson.read(text,Answer.class);
        if(result==null || !bounded(result.answer(),4000)) throw invalid();
        var allowed=new HashSet<>(state.evidence.stream().map(TaskState.Evidence::id).toList());
        var matcher=Pattern.compile("\\[E([^\\]]*)\\]").matcher(result.answer());
        boolean found=false;
        while(matcher.find()) { if(!allowed.contains("E"+matcher.group(1))) throw invalid(); found=true; }
        if(!found) throw invalid();
        return result.answer();
    }
    private static boolean bounded(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("TASK_MODEL_RESPONSE_INVALID"); }
    private TaskPlanValidator() { }
}
