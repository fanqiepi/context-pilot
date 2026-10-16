package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskModelGatewayTests {
    @Test void oversizedInputNeverInvokesProvider() {
        var model=mock(ChatModel.class);var gateway=new TaskModelGateway(model,"deepseek-v4-flash");
        var state=new TaskState();state.goal="x".repeat(30000);
        assertThatThrownBy(()->gateway.call("PLANNING",state)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }
    @Test void absentUsageRemainsUnknownAndOnlyOneCallIsMade() {
        var model=mock(ChatModel.class);var gateway=new TaskModelGateway(model,"deepseek-v4-flash");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[\"constraint?\"],\"steps\":[]}")))));
        var state=new TaskState();state.goal="Research";
        var result=gateway.call("PLANNING",state);
        assertThat(result.call().status()).isEqualTo("SUCCEEDED");
        assertThat(result.call().totalTokens()).isNull();
        verify(model,times(1)).call(any(Prompt.class));
    }
}
