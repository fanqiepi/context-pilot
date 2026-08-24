package io.github.fanqiepi.contextpilot.memory;

import java.util.List;

public record MemoryContextResponse(
        List<MemoryContextItemResponse> items,
        int count,
        int characterCount,
        boolean truncated) {

    private static final int MAX_SUMMARY_CHARACTERS = 160;

    public MemoryContextResponse {
        items = List.copyOf(items);
    }

    static MemoryContextResponse from(MemoryContext context) {
        List<MemoryContextItemResponse> items = context.preferences().stream()
                .map(preference -> new MemoryContextItemResponse(
                        preference.memoryId(),
                        preference.revision(),
                        summarize(preference.content()),
                        false))
                .toList();
        return new MemoryContextResponse(
                items, items.size(), context.characterCount(), context.truncated());
    }

    public static MemoryContextResponse fromHistoryRows(List<MessageMemoryContextHistoryRow> rows) {
        List<MemoryContextItemResponse> items = rows.stream()
                .map(row -> new MemoryContextItemResponse(
                        row.getMemoryId(),
                        row.getRevisionNumber(),
                        summarize(row.getContent()),
                        row.getContent() == null))
                .toList();
        MessageMemoryContextHistoryRow first = rows.getFirst();
        return new MemoryContextResponse(
                items, items.size(), first.getContextCharacterCount(),
                Boolean.TRUE.equals(first.getContextTruncated()));
    }

    static String summarize(String content) {
        if (content == null) {
            return "该偏好已删除";
        }
        return content.length() <= MAX_SUMMARY_CHARACTERS
                ? content
                : content.substring(0, MAX_SUMMARY_CHARACTERS) + "…";
    }
}
