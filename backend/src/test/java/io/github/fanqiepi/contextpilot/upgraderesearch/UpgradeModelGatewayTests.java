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
    @Test @org.junit.jupiter.api.Timeout(25)
    void providerTransportAllowsResponseBeyondDefaultTenSeconds() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/delayed",exchange->{
            requests.incrementAndGet();
            try {
                Thread.sleep(11_000);
                byte[] body="ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });
        server.start();
        try {
            var client=UpgradeResearchModelGateway.httpClientBuilder().build();
            assertThat(client.get().uri("http://127.0.0.1:"+server.getAddress().getPort()+"/delayed")
                    .retrieve().body(String.class)).isEqualTo("ok");
            assertThat(requests.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
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
