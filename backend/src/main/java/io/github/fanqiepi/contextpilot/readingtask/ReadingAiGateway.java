package io.github.fanqiepi.contextpilot.readingtask;

import java.time.Duration;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One invocation is exactly one HTTP request, with no SDK/HTTP retry or hidden splitting.
 * Production remains fail-closed until a separately authorized, priced adapter can guarantee
 * the reading-specific output limits. Tests supply an offline implementation of this port.
 */
public interface ReadingAiGateway {
    record Response(String text,Long inputTokens,Long outputTokens,String finishReason) {
        public Response(String text,Long inputTokens,Long outputTokens) { this(text,inputTokens,outputTokens,"stop"); }
    }
    record Pricing(UUID authorizationId,String version,BigDecimal inputTokenRate,BigDecimal outputTokenRate) {}
    record EmbeddingResponse(List<float[]> vectors,Long inputTokens) {
        public Response usage() { return new Response("",inputTokens,inputTokens==null?null:0L); }
    }
    /** Exact serialized generation input, including the provider envelope, for bounds/reservation. */
    default String generationInput(String prompt,int outputTokens) { return prompt; }
    /** Only test fixtures may opt out of the spend ledger. No production offline bean is registered. */
    default boolean offline() { return false; }
    default Pricing pricing(String phase) { throw ReadingTaskPolicy.failure("PRICING_UNKNOWN"); }
    void requireAuthorized();
    Response generate(String prompt,int outputTokens,Duration timeout);
    List<float[]> embed(List<String> text,Duration timeout);
    default EmbeddingResponse embedWithUsage(List<String> text,Duration timeout) {
        return new EmbeddingResponse(embed(text,timeout),null);
    }
}
