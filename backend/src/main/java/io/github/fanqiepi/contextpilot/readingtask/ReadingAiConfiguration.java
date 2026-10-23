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
                                     @Value("${spring.ai.openai.api-key:}") String embeddingKey,ReadingLiveAuthorization live,
                                     @Value("${contextpilot.reading.ai-enabled:false}") boolean enabled,
                                     @Value("${contextpilot.reading.trial-enabled:false}") boolean trial,
                                     @Value("${contextpilot.reading.live-enabled:false}") boolean evaluation) {
        if(enabled && (trial || evaluation))throw ReadingTaskPolicy.failure("READING_EXECUTION_MODE_CONFLICT");
        var provider=new ReadingProviderClient(generationKey,embeddingKey);
        return gateway(provider,generationKey,embeddingKey,live,enabled);
    }
    ReadingAiGateway gateway(ReadingProviderClient provider,String generationKey,String embeddingKey,ReadingLiveAuthorization live,boolean enabled) {
        return new ReadingAiGateway() {
            public String executionMode() { return enabled?"DIRECT_LIVE":live!=null && live.readerTrial()?"READER_TRIAL":"EVALUATION_ONLY"; }
            public boolean budgetRequired() { return !enabled; }
            public java.util.Map<String,Object> authorizationStatus() {
                if(!enabled)return live==null?java.util.Map.of():live.status();
                String reason="READY";try { requireAuthorized(); }catch(ReadingException e){reason=e.code();}
                return java.util.Map.of("liveAllowed","READY".equals(reason),"reason",reason,"executionScope","DIRECT_LIVE","budgetEnforced",false);
            }
            public void requireAuthorized() {
                if(!enabled) {
                    if(live==null)throw ReadingException.conflict("LIVE_AI_NOT_AUTHORIZED","阅读 AI 未启用，基础阅读仍可使用");
                    live.check();
                }
                if(generationKey==null || generationKey.isBlank() || embeddingKey==null || embeddingKey.isBlank()
                        || generationKey.contains("\r") || generationKey.contains("\n") || embeddingKey.contains("\r") || embeddingKey.contains("\n"))
                    throw ReadingTaskPolicy.failure("PROVIDER_CONFIGURATION_INVALID");
            }
            public void authorizeWork(ReadingWork work,ReadingWork.Payload payload) {
                requireAuthorized();
                if(enabled) {
                    if(payload.liveApprovalDigest!=null)throw ReadingTaskPolicy.failure("LIVE_BATCH_MISMATCH");
                } else live.authorize(work,payload);
            }
            public Pricing pricing(String phase) { requireAuthorized();return live.pricing(phase); }
            public int reservationInputTokens(String phase,int inputBytes) { return ReadingLiveAuthorization.tokenBound(phase); }
            public void haltBatch() { if(!enabled && live!=null)live.halt(); }
            public void haltBatch(String reason) { if(!enabled && live!=null)live.halt(reason); }
            public String generationInput(String prompt,int limit) { return provider.generationInput(prompt,limit); }
            public Response generate(String prompt,int limit,Duration timeout) { requireAuthorized();return provider.generate(prompt,limit,timeout); }
            public List<float[]> embed(List<String> text,Duration timeout) { return embedWithUsage(text,timeout).vectors(); }
            public EmbeddingResponse embedWithUsage(List<String> text,Duration timeout) { requireAuthorized();return provider.embed(text,timeout); }
        };
    }
}
