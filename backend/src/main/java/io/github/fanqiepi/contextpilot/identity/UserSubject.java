package io.github.fanqiepi.contextpilot.identity;

import java.util.Objects;
import java.util.UUID;

public record UserSubject(UUID id, String key) {

    public UserSubject {
        Objects.requireNonNull(id, "Subject ID must not be null");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Subject key must not be blank");
        }
        key = key.strip();
    }
}
