package io.github.fanqiepi.contextpilot.readingtask;

import java.time.Duration;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One invocation is exactly one HTTP request, with no SDK/HTTP retry or hidden splitting.
 * Daily live calls use the explicit configuration switch; legacy batches retain their approval
 * and spend ledgers. All modes retain reading-specific output limits.
 */
public interface ReadingAiGateway {
    record Response(String text,Long inputTokens,Long outputTokens,String finishReason,String model,String requestId) {
        public Response(String text,Long inputTokens,Long outputTokens,String finishReason) { this(text,inputTokens,outputTokens,finishReason,null,null); }
        public Response(String text,Long inputTokens,Long outputTokens) { this(text,inputTokens,outputTokens,"stop",null,null); }
    }
    record Pricing(UUID authorizationId,String version,BigDecimal inputTokenRate,BigDecimal outputTokenRate) {}
    record EmbeddingResponse(List<float[]> vectors,Long inputTokens,String model,String requestId) {
        public EmbeddingResponse(List<float[]> vectors,Long inputTokens) { this(vectors,inputTokens,null,null); }
        public Response usage() { return new Response("",inputTokens,inputTokens==null?null:0L,"stop",model,requestId); }
    }
    /** Exact serialized generation input, including the provider envelope, for bounds/reservation. */
    default String generationInput(String prompt,int outputTokens) { return prompt; }
    /** Only test fixtures identify themselves as offline. */
    default boolean offline() { return false; }
    /** Legacy approved batches reserve money; daily direct calls only record actual usage. */
    default boolean budgetRequired() { return !offline(); }
    default String executionMode() { return offline()?"OFFLINE_FIXTURE":"EVALUATION_ONLY"; }
    default java.util.Map<String,Object> authorizationStatus() { return java.util.Map.of(); }
    default void authorizeWork(ReadingWork work,ReadingWork.Payload payload) {
        if(!offline())throw ReadingTaskPolicy.failure("LIVE_AI_NOT_AUTHORIZED");
    }
    default int reservationInputTokens(String phase,int inputBytes) { return inputBytes; }
    default void haltBatch() {}
    default void haltBatch(String reason) { haltBatch(); }
    default Pricing pricing(String phase) { throw ReadingTaskPolicy.failure("PRICING_UNKNOWN"); }
    void requireAuthorized();
    Response generate(String prompt,int outputTokens,Duration timeout);
    List<float[]> embed(List<String> text,Duration timeout);
    default EmbeddingResponse embedWithUsage(List<String> text,Duration timeout) {
        return new EmbeddingResponse(embed(text,timeout),null);
    }
}
