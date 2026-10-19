package io.github.fanqiepi.contextpilot.research;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.github.fanqiepi.contextpilot.chat.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResearchStreamingServiceTests {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void preservesPartialAndRefusalSemanticsInDoneEvent(boolean partial) {
        var chat=mock(ResearchChatService.class);var run=mock(ResearchRunResponse.class);
        var id=UUID.randomUUID();when(run.id()).thenReturn(id);
        when(run.steps()).thenReturn(List.of());
        when(run.usage()).thenReturn(new ResearchUsageResponse(2,1,1,20,10,5,15));
        when(run.executionStatus()).thenReturn(partial?ResearchExecutionStatus.PARTIAL:ResearchExecutionStatus.SUCCEEDED);
        when(run.answerStatus()).thenReturn(partial?ResearchAnswerStatus.ANSWERED:ResearchAnswerStatus.REFUSED);
        var request=new ChatRequest(null,UUID.randomUUID(),"独立调试比较问题");
        when(chat.start(request,"trace")).thenReturn(new ResearchStart(
                new PendingChatExchange(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),run,false));
        when(chat.executeAsync(id)).thenReturn(CompletableFuture.completedFuture(new ResearchExecutionResult(
                run,partial?"有效内容和资料缺口":"无足够证据",List.of(),"fixture",1)));
        var events=new ResearchStreamingService(chat).stream(request,"trace").collectList().block(Duration.ofSeconds(2));
        assertThat(events).isNotEmpty();
        var done=(ChatStreamPayload.Done)events.getLast().data();
        assertThat(done.status()).isEqualTo(partial?"PARTIAL":"REFUSED");
        assertThat(done.runId()).isEqualTo(id);
        verify(chat,times(1)).executeAsync(id);
    }
}
