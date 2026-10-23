package io.github.fanqiepi.contextpilot.readingtask;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingTaskEventsTests {
    @Test void failedWritesAreRemovedWithoutCompletingAndOtherSubscribersContinue() throws Exception {
        var tasks=mock(ReadingTaskService.class);var timer=mock(ScheduledExecutorService.class);
        var broken=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);
        var emitters=new ArrayDeque<>(List.of(broken,healthy));
        var events=new ReadingTaskEvents(tasks,timer,emitters::remove);
        var tick=ArgumentCaptor.forClass(Runnable.class);
        verify(timer).scheduleWithFixedDelay(tick.capture(),eq(1L),eq(1L),eq(TimeUnit.SECONDS));
        UUID badId=UUID.randomUUID(),goodId=UUID.randomUUID();
        doThrow(new AsyncRequestNotUsableException("private write failure")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(call->{call.<Consumer<Object>>getArgument(1).accept(Map.of("revision",1L,"status","COMPLETED"));return null;})
                .when(tasks).send(any(),any());
        events.subscribe(badId);events.subscribe(goodId);
        assertThatCode(tick.getValue()::run).doesNotThrowAnyException();
        tick.getValue().run();events.stop();
        verify(tasks,times(1)).send(eq(badId),any());verify(tasks,times(1)).send(eq(goodId),any());
        verify(broken,never()).complete();verify(broken,never()).completeWithError(any());
        verify(healthy).complete();verify(healthy,times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void completionFailureCannotEscapePollOrStopFutureTicks() throws Exception {
        var tasks=mock(ReadingTaskService.class);var timer=mock(ScheduledExecutorService.class);
        var broken=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);
        var emitters=new ArrayDeque<>(List.of(broken,healthy));
        var events=new ReadingTaskEvents(tasks,timer,emitters::remove);
        var tick=ArgumentCaptor.forClass(Runnable.class);
        verify(timer).scheduleWithFixedDelay(tick.capture(),eq(1L),eq(1L),eq(TimeUnit.SECONDS));
        doThrow(new IllegalStateException("response no longer usable")).when(broken).complete();
        doAnswer(call->{call.<Consumer<Object>>getArgument(1).accept(Map.of("revision",1L,"status","FAILED"));return null;})
                .when(tasks).send(any(),any());
        events.subscribe(UUID.randomUUID());assertThatCode(tick.getValue()::run).doesNotThrowAnyException();
        events.subscribe(UUID.randomUUID());assertThatCode(tick.getValue()::run).doesNotThrowAnyException();
        events.stop();verify(broken,times(1)).complete();verify(healthy).complete();
    }

    @Test void serializationFailureUsesErrorCompletionOnceAndShutdownDoesNotCompleteItAgain() throws IOException {
        var tasks=mock(ReadingTaskService.class);var timer=mock(ScheduledExecutorService.class);var emitter=mock(SseEmitter.class);
        var events=new ReadingTaskEvents(tasks,timer,()->emitter);
        var tick=ArgumentCaptor.forClass(Runnable.class);
        verify(timer).scheduleWithFixedDelay(tick.capture(),eq(1L),eq(1L),eq(TimeUnit.SECONDS));
        var error=new IllegalArgumentException("serialization failure");
        doThrow(error).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("already closed")).when(emitter).completeWithError(error);
        doAnswer(call->{call.<Consumer<Object>>getArgument(1).accept(Map.of("revision",1L,"status","RUNNING"));return null;})
                .when(tasks).send(any(),any());
        events.subscribe(UUID.randomUUID());assertThatCode(tick.getValue()::run).doesNotThrowAnyException();
        tick.getValue().run();events.stop();verify(emitter).completeWithError(error);verify(emitter,never()).complete();
    }
}
