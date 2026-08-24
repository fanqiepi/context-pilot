package io.github.fanqiepi.contextpilot.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

import io.github.fanqiepi.contextpilot.common.BadRequestException;

public final class UserMemoryContent {

    public static final int MAX_LENGTH = 300;

    private UserMemoryContent() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw invalid();
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            throw invalid();
        }
        return normalized;
    }

    public static String hash(String normalizedContent) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedContent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException(
                "USER_MEMORY_CONTENT_INVALID",
                "Response preference content must contain between 1 and 300 characters");
    }
}
