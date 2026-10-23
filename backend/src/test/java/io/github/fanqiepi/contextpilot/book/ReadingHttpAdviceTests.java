package io.github.fanqiepi.contextpilot.book;

import java.util.Map;
import java.util.UUID;
import io.github.fanqiepi.contextpilot.readingtask.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class ReadingHttpAdviceTests {
    @org.springframework.web.bind.annotation.RestController
    static class GlobalStreamController {
        SseEmitter emitter;
        @org.springframework.web.bind.annotation.GetMapping("/test/global-stream")
        SseEmitter stream() { return emitter; }
        @org.springframework.web.bind.annotation.GetMapping("/test/global-failure")
        void fail() { throw new IllegalStateException("ordinary error"); }
    }
    @Test void globalFallbackAlsoHandlesBrokenAndCommittedAsyncResponses(CapturedOutput output) throws Exception {
        var controller=new GlobalStreamController();
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ReadingHttpAdvice(),
                new io.github.fanqiepi.contextpilot.common.ApiExceptionHandler()).build();
        for(Exception error:new Exception[]{new AsyncRequestNotUsableException("private disconnect"),
                new IllegalStateException("private stream error")}) {
            controller.emitter=new SseEmitter(1000L);
            var pending=mvc.perform(get("/test/global-stream")).andExpect(request().asyncStarted()).andReturn();
            controller.emitter.send(SseEmitter.event().name("snapshot").data(Map.of("status","FAILED")));
            String original=pending.getResponse().getContentAsString();
            controller.emitter.completeWithError(error);
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(content().string(original));
        }
        assertThat(output.getAll()).doesNotContain("HttpMessageNotWritableException","Failure in @ExceptionHandler","private disconnect","private stream error");
        mvc.perform(get("/test/global-failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
    @Test void committedStreamErrorsNeverAppendJsonOrFailAgain(CapturedOutput output) throws Exception {
        for(Exception error:new Exception[]{new AsyncRequestNotUsableException("private disconnect"),
                new AsyncRequestTimeoutException(),new IllegalStateException("private internal detail"),
                ReadingException.missing()}) {
            var tasks=mock(ReadingTaskService.class);var events=mock(ReadingTaskEvents.class);
            UUID id=UUID.randomUUID();var emitter=new SseEmitter(1000L);
            when(events.subscribe(id)).thenReturn(emitter);
            var mvc=MockMvcBuilders.standaloneSetup(new ReadingTaskController(tasks,events))
                    .setControllerAdvice(new ReadingHttpAdvice()).build();
            var pending=mvc.perform(get("/api/reading/tasks/"+id+"/events")).andExpect(request().asyncStarted()).andReturn();
            emitter.send(SseEmitter.event().name("result").data(Map.of("status","COMPLETED"),MediaType.APPLICATION_JSON));
            assertThat(pending.getResponse().isCommitted()).isTrue();
            assertThat(pending.getResponse().getContentType()).startsWith("text/event-stream");
            String original=pending.getResponse().getContentAsString();
            emitter.completeWithError(error);
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                    .andExpect(content().string(original));
            // MockMvc permits Spring's exception resolver to clear committed headers, unlike Tomcat.
            // The advice must neither replace the stream with JSON nor append any new body.
            assertThat(pending.getResponse().getContentType()).isNotEqualTo(MediaType.APPLICATION_JSON_VALUE);
            verifyNoInteractions(tasks);
        }
        assertThat(output.getAll()).doesNotContain("HttpMessageNotWritableException","Failure in @ExceptionHandler","private disconnect","private internal detail");
    }
    @Test void errorsBeforeStreamStartsAndOrdinaryHttpErrorsRemainJson() throws Exception {
        var tasks=mock(ReadingTaskService.class);var events=mock(ReadingTaskEvents.class);UUID id=UUID.randomUUID();
        var mvc=MockMvcBuilders.standaloneSetup(new ReadingTaskController(tasks,events)).setControllerAdvice(new ReadingHttpAdvice()).build();
        when(events.subscribe(id)).thenThrow(ReadingException.missing());
        mvc.perform(get("/api/reading/tasks/"+id+"/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("READING_RESOURCE_NOT_FOUND"));
        when(tasks.get(false,id)).thenThrow(new IllegalStateException("private sql detail"));
        mvc.perform(get("/api/reading/tasks/"+id)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("READING_RETRYABLE"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
}
