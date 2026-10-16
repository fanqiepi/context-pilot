package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class UpgradeModelGatewayTests {
    @Test void inputBudgetIsCheckedBeforeProviderAndNoRetryOccurs(){
        var model=mock(ChatModel.class);var gateway=new UpgradeResearchModelGateway(model);
        assertThatThrownBy(()->gateway.call(List.of(new UserMessage("x".repeat(40001))),true))
            .isInstanceOf(UpgradeToolException.class).hasMessage("INPUT_BUDGET_EXCEEDED");
        verifyNoInteractions(model);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider failure"));
        assertThatThrownBy(()->gateway.call(List.of(new UserMessage("question")),true)).isInstanceOf(IllegalStateException.class);
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void finalRoundDisablesToolsAndNeverSetsMaxTokens(){
        var model=mock(ChatModel.class);var gateway=new UpgradeResearchModelGateway(model);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
        gateway.call(List.of(new UserMessage("question")),false);
        var capture=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model).call(capture.capture());
        var options=(DeepSeekChatOptions)capture.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("none");assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getTools()).isNullOrEmpty();
    }
}
