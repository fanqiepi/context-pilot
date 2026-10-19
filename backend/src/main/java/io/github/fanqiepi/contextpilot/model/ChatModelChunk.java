package io.github.fanqiepi.contextpilot.model;

public record ChatModelChunk(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Boolean refused) {
    public ChatModelChunk(String content, String model, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, model, promptTokens, completionTokens, totalTokens, null);
    }
}
