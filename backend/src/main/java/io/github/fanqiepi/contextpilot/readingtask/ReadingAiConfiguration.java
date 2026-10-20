package io.github.fanqiepi.contextpilot.readingtask;

import java.time.Duration;
import java.util.List;
import io.github.fanqiepi.contextpilot.book.ReadingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class ReadingAiConfiguration {
    @Bean @ConditionalOnMissingBean(ReadingAiGateway.class)
    ReadingAiGateway readingAiGateway() {
        return new ReadingAiGateway() {
            public void requireAuthorized() { throw ReadingException.conflict("LIVE_AI_NOT_AUTHORIZED","阅读 AI 尚未获得付费授权，基础阅读仍可使用"); }
            public Response generate(String prompt,int limit,Duration timeout) { requireAuthorized();throw new IllegalStateException(); }
            public List<float[]> embed(List<String> text,Duration timeout) { requireAuthorized();throw new IllegalStateException(); }
        };
    }
}
