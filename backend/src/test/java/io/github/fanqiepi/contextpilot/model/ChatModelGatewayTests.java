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
        assertThat(prompt.getValue().getOptions().getMaxTokens()).isEqualTo(3000);
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("deepseek-v4-flash");
    }
}
