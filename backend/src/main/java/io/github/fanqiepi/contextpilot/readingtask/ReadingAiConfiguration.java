package io.github.fanqiepi.contextpilot.readingtask;

import java.time.Duration;
import java.util.List;
import io.github.fanqiepi.contextpilot.book.ReadingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class ReadingAiConfiguration {
    @Bean @ConditionalOnMissingBean(ReadingAiGateway.class)
    ReadingAiGateway readingAiGateway(@Value("${spring.ai.deepseek.api-key:}") String generationKey,
                                     @Value("${spring.ai.openai.api-key:}") String embeddingKey,ReadingLiveAuthorization live) {
        var provider=new ReadingProviderClient(generationKey,embeddingKey);
        return new ReadingAiGateway() {
            public void requireAuthorized() {
                if(live==null)throw ReadingException.conflict("LIVE_AI_NOT_AUTHORIZED","阅读 AI 尚未获得付费授权，基础阅读仍可使用");
                live.check();
                if(generationKey==null || generationKey.isBlank() || embeddingKey==null || embeddingKey.isBlank()
                        || generationKey.contains("\r") || generationKey.contains("\n") || embeddingKey.contains("\r") || embeddingKey.contains("\n"))
                    throw ReadingTaskPolicy.failure("PROVIDER_CONFIGURATION_INVALID");
            }
            public void authorizeWork(ReadingWork work,ReadingWork.Payload payload) { requireAuthorized();live.authorize(work,payload); }
            public Pricing pricing(String phase) { requireAuthorized();return live.pricing(phase); }
            public int reservationInputTokens(String phase,int inputBytes) { return ReadingLiveAuthorization.tokenBound(phase); }
            public void haltBatch() { if(live!=null)live.halt(); }
            public String generationInput(String prompt,int limit) { return provider.generationInput(prompt,limit); }
            public Response generate(String prompt,int limit,Duration timeout) { requireAuthorized();return provider.generate(prompt,limit,timeout); }
            public List<float[]> embed(List<String> text,Duration timeout) { return embedWithUsage(text,timeout).vectors(); }
            public EmbeddingResponse embedWithUsage(List<String> text,Duration timeout) { requireAuthorized();return provider.embed(text,timeout); }
        };
    }
}
