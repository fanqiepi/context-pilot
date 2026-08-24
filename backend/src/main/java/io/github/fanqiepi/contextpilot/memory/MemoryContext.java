package io.github.fanqiepi.contextpilot.memory;

import java.util.List;

public record MemoryContext(
        List<MemoryContextPreference> preferences,
        int characterCount,
        boolean truncated) {

    public MemoryContext {
        preferences = List.copyOf(preferences);
    }

    public static MemoryContext empty() {
        return new MemoryContext(List.of(), 0, false);
    }

    public boolean isEmpty() {
        return preferences.isEmpty();
    }

    public String toPromptBlock() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("<user_preferences>\n");
        preferences.forEach(preference -> block
                .append("- ")
                .append(escape(preference.content()))
                .append('\n'));
        return block.append("</user_preferences>").toString();
    }

    public MemoryContextResponse toResponse() {
        return MemoryContextResponse.from(this);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
