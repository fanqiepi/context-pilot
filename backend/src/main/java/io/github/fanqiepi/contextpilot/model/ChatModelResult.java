package io.github.fanqiepi.contextpilot.model;

public record ChatModelResult(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        boolean refused) {
    public ChatModelResult(String content, String model, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, model, promptTokens, completionTokens, totalTokens, false);
    }
}
