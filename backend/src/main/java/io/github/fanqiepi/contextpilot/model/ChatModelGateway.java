package io.github.fanqiepi.contextpilot.model;

import java.util.List;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ChatModelGateway {

    private static final String PROVIDER = "DEEPSEEK";
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final String configuredModel;

    public ChatModelGateway(
            ObjectProvider<ChatModel> chatModelProvider,
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}") String configuredModel) {
        this.chatModelProvider = chatModelProvider;
        this.configuredModel = configuredModel;
    }

    public String configuredModel() {
        return configuredModel;
    }

    public String provider() {
        return PROVIDER;
    }

    public ChatModelResult generate(String systemText, String userText) {
        return generate(prompt(systemText, userText));
    }

    public ChatModelResult generate(String systemText, String userText, int maxOutputTokens) {
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        ChatOptions options = ChatOptions.builder()
                .model(configuredModel)
                .maxTokens(maxOutputTokens)
                .build();
        return generate(new Prompt(
                List.of(new SystemMessage(systemText), new UserMessage(userText)), options));
    }

    private ChatModelResult generate(Prompt prompt) {
        ChatModel chatModel = requireChatModel();
        try {
            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    || response.getResult().getOutput().getText() == null
                    || response.getResult().getOutput().getText().isBlank()) {
                throw new IllegalStateException("Chat model returned an empty response");
            }
            ChatResponseMetadata metadata = response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();
            String model = metadata == null || metadata.getModel() == null
                    ? configuredModel
                    : metadata.getModel();
            return new ChatModelResult(
                    response.getResult().getOutput().getText().strip(),
                    model,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    usage == null ? null : usage.getTotalTokens());
        } catch (RuntimeException exception) {
            if (exception instanceof InternalServiceException internal) {
                throw internal;
            }
            throw new InternalServiceException(
                    "CHAT_MODEL_CALL_FAILED",
                    "Chat model call failed",
                    exception);
        }
    }

    public Flux<ChatModelChunk> stream(String systemText, String userText) {
        return Flux.defer(() -> {
                    ChatModel chatModel = requireChatModel();
                    try {
                        return chatModel.stream(prompt(systemText, userText)).map(this::toChunk);
                    } catch (RuntimeException exception) {
                        return Flux.error(streamFailure(exception));
                    }
                })
                .onErrorMap(exception -> exception instanceof InternalServiceException
                        ? exception
                        : streamFailure(exception));
    }

    private ChatModelChunk toChunk(ChatResponse response) {
        String content = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                ? ""
                : response.getResult().getOutput().getText();
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata == null || metadata.getModel() == null
                ? configuredModel
                : metadata.getModel();
        return new ChatModelChunk(
                content,
                model,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens());
    }

    private Prompt prompt(String systemText, String userText) {
        return new Prompt(List.of(new SystemMessage(systemText), new UserMessage(userText)));
    }

    private ChatModel requireChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new InternalServiceException(
                    "CHAT_MODEL_UNAVAILABLE",
                    "Chat model is not enabled",
                    new IllegalStateException("ChatModel bean is not available"));
        }
        return chatModel;
    }

    private InternalServiceException streamFailure(Throwable cause) {
        return new InternalServiceException(
                "CHAT_MODEL_STREAM_FAILED",
                "Chat model stream failed",
                cause);
    }
}
