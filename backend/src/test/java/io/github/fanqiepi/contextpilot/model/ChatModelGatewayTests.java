package io.github.fanqiepi.contextpilot.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatModelGatewayTests {
    @Mock private ObjectProvider<ChatModel> chatModelProvider;
    @Mock private ChatModel chatModel;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ANSWERED", "REFUSED"})
    void decodesDispositionInOneCall(String status) {
        ChatResponse response = response(status + "\nSolstice answer.");
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        ChatModelResult result = new ChatModelGateway(chatModelProvider, "test")
                .generate(AnswerStatusDecoder.PROTOCOL, "Independent fixture");
        assertThat(result.content()).isEqualTo("Solstice answer.");
        assertThat(result.refused()).isEqualTo(status.equals("REFUSED"));
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void streamHidesSplitHeaderAndKeepsDisposition() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        var providerChunks = reactor.core.publisher.Flux.just(
                response("REF"), response("USED\r"), response("\nNo evidence."));
        when(chatModel.stream(any(Prompt.class))).thenReturn(providerChunks);
        var chunks = new ChatModelGateway(chatModelProvider, "test")
                .stream(AnswerStatusDecoder.PROTOCOL, "Independent fixture").collectList().block();
        assertThat(chunks).extracting(ChatModelChunk::content).containsExactly("", "", "No evidence.");
        assertThat(chunks.getLast().refused()).isTrue();
        verify(chatModel).stream(any(Prompt.class));
    }

    private ChatResponse response(String text) {
        ChatResponse response = mock(ChatResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(text);
        return response;
    }

    @Test
    void appliesResearchSpecificMaximumOutputTokensToThePrompt() {
        ChatResponse response = mock(ChatResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        when(response.getResult().getOutput().getText()).thenReturn("answer");
        ChatModelGateway gateway = new ChatModelGateway(chatModelProvider, "deepseek-v4-flash");

        gateway.generate("system", "user", 3000);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(prompt.getValue().getOptions().getMaxTokens()).isEqualTo(3000);
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("deepseek-v4-flash");
    }
}
