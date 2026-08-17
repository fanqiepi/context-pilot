package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ResearchJsonCodec {
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private final ObjectMapper objectMapper;

    public ResearchJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeDocumentIds(List<UUID> documentIds) {
        try {
            return objectMapper.writeValueAsString(documentIds);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Research document IDs could not be serialized", exception);
        }
    }

    public List<UUID> readDocumentIds(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, UUID_LIST));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Persisted research document IDs are invalid", exception);
        }
    }
}
