package io.github.fanqiepi.contextpilot.document;

import java.util.Objects;
import java.util.regex.Pattern;

public record EmbeddingIndexProfile(
        String id,
        String provider,
        String model,
        int dimensions,
        String version) {

    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    public EmbeddingIndexProfile {
        id = required(id, "Embedding index profile id");
        provider = required(provider, "Embedding index provider");
        model = required(model, "Embedding index model");
        version = required(version, "Embedding index profile version");
        if (!PROFILE_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Embedding index profile id must contain only lowercase letters, digits, dots, underscores, or hyphens");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding index dimensions must be positive");
        }
        maximumLength(provider, 32, "Embedding index provider");
        maximumLength(model, 100, "Embedding index model");
        maximumLength(version, 32, "Embedding index profile version");
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label + " must not be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static void maximumLength(String value, int maximum, String label) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(label + " must not exceed " + maximum + " characters");
        }
    }
}
