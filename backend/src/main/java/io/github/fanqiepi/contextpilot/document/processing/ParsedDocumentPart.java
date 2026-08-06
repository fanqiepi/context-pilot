package io.github.fanqiepi.contextpilot.document.processing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ParsedDocumentPart(String text, Map<String, Object> metadata) {

    public ParsedDocumentPart {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Parsed document text must not be blank");
        }
        text = text.strip();
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
