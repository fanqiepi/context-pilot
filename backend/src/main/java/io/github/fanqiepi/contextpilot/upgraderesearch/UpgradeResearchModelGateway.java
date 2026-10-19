package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="contextpilot.upgrade-research",name="enabled",havingValue="true")
public class UpgradeResearchModelGateway {
    private final ChatModel model;
    static final List<DeepSeekApi.FunctionTool> TOOLS=List.of(
        tool("search_selected_documents","Search selected local documents only.",Map.of(
            "query",Map.of("type","string","maxLength",500),
            "documentIds",Map.of("type","array","minItems",1,"maxItems",3,"items",Map.of("type","string"))),List.of("query","documentIds")),
        tool("read_evidence","Read a previously returned evidence ID in this run.",Map.of("evidenceId",Map.of("type","string")),List.of("evidenceId")),
        tool("get_release_notes","Read one explicitly selected release tag in the fixed repository.",Map.of("tag",Map.of("type","string")),List.of("tag")));

    @Autowired
    public UpgradeResearchModelGateway(@Value("${spring.ai.deepseek.api-key:}") String key) {
        this(DeepSeekChatModel.builder().deepSeekApi(DeepSeekApi.builder().apiKey(key)
                .restClientBuilder(httpClientBuilder()).build())
                .options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build());
    }
    UpgradeResearchModelGateway(ChatModel model){this.model=model;}
    static org.springframework.web.client.RestClient.Builder httpClientBuilder(){
        // The classpath-selected Reactor factory otherwise stops silent responses at 10s,
        // before the executor's approved 60s hard deadline. Keep both bounds aligned.
        var factory=new org.springframework.http.client.ReactorClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(UpgradeBudget.MODEL_SECONDS));
        return org.springframework.web.client.RestClient.builder().requestFactory(factory);
    }
    public ChatResponse call(List<Message> history,boolean toolsAllowed){
        int characters=0;
        for(var message:history){
            characters+=message.getText()==null?0:message.getText().length();
            characters+=UpgradeJson.write(message.getMetadata()).length();
            if(message instanceof AssistantMessage assistant)characters+=UpgradeJson.write(assistant.getToolCalls()).length();
            if(message instanceof ToolResponseMessage response)characters+=UpgradeJson.write(response.getResponses()).length();
        }
        if(toolsAllowed)for(var tool:TOOLS)characters+=UpgradeJson.write(tool).length();
        if(characters>UpgradeBudget.INPUT_CHARACTERS)throw new UpgradeToolException("INPUT_BUDGET_EXCEEDED");
        var builder=DeepSeekChatOptions.builder().model("deepseek-v4-flash")
                .responseFormat(org.springframework.ai.deepseek.api.ResponseFormat.builder()
                    .type(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT).build());
        if(toolsAllowed)builder.tools(TOOLS);else builder.toolChoice("none");
        var response=model.call(new Prompt(history,builder.build()));
        if(response==null || response.getResult()==null || response.getResult().getOutput()==null)
            throw new UpgradeToolException("MODEL_RESPONSE_EMPTY");
        return response;
    }
    private static DeepSeekApi.FunctionTool tool(String name,String description,Map<String,Object> properties,List<String> required){
        return new DeepSeekApi.FunctionTool(new DeepSeekApi.FunctionTool.Function(description,name,
                Map.of("type","object","properties",properties,"required",required,"additionalProperties",false),false));
    }
}
