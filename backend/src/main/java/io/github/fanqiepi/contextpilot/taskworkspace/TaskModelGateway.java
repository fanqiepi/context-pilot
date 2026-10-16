package io.github.fanqiepi.contextpilot.taskworkspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.retry.*;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class TaskModelGateway {
    private final ChatModel model;
    private final String modelName;
    @Autowired
    public TaskModelGateway(@Value("${spring.ai.deepseek.api-key:}") String key,
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}") String modelName) {
        this(DeepSeekChatModel.builder().deepSeekApi(DeepSeekApi.builder().apiKey(key).build())
                .options(DeepSeekChatOptions.builder().model(modelName).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build(),modelName);
    }
    TaskModelGateway(ChatModel model,String modelName) { this.model=model; this.modelName=modelName; }
    public record Result(String text, TaskState.Call call) { }
    public Result call(String phase,TaskState state) {
        String system=resource("PLANNING".equals(phase)?"task-workspace-plan-v1.txt":"task-workspace-answer-v1.txt");
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("goal",state.goal); input.put("supplements",state.supplements);
        if("PLANNING".equals(phase)) input.put("documents",state.sources.stream()
                .map(s->Map.of("id",s.id(),"filename",s.filename())).toList());
        else input.put("evidence",state.evidence);
        String user=TaskJson.write(input);
        if(system.length()+user.length()>30000) throw new IllegalArgumentException("TASK_INPUT_BUDGET");
        var options=DeepSeekChatOptions.builder().model(modelName).toolChoice("none")
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()).build();
        long start=System.nanoTime();
        var response=model.call(new Prompt(List.of(new SystemMessage(system),new UserMessage(user)),options));
        if(response==null || response.getResult()==null || response.getResult().getOutput()==null
                || response.getResult().getOutput().getText()==null) throw new IllegalArgumentException("TASK_MODEL_EMPTY");
        var metadata=response.getMetadata(); var usage=metadata==null?null:metadata.getUsage();
        if(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage) usage=null;
        return new Result(response.getResult().getOutput().getText(),new TaskState.Call(phase,"SUCCEEDED",
                metadata==null?modelName:metadata.getModel(), usage==null?null:number(usage.getPromptTokens()),
                usage==null?null:number(usage.getCompletionTokens()),usage==null?null:number(usage.getTotalTokens()),
                (System.nanoTime()-start)/1_000_000));
    }
    private Long number(Number number) { return number==null?null:number.longValue(); }
    private String resource(String name) {
        try { return new ClassPathResource("prompts/"+name).getContentAsString(StandardCharsets.UTF_8); }
        catch(IOException error) { throw new IllegalStateException("Task prompt missing",error); }
    }
}
