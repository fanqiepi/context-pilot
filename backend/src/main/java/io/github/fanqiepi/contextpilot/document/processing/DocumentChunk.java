package io.github.fanqiepi.contextpilot.document.processing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DocumentChunk(int index, String text, Map<String, Object> metadata) {

    public DocumentChunk {
        if (index < 0) {
            throw new IllegalArgumentException("Document chunk index must not be negative");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Document chunk text must not be blank");
        }
        text = text.strip();
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
