package io.github.fanqiepi.contextpilot.projectsource;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class ProjectSourcePolicy {
    public static final String SOURCE_ID = "spring-ai";
    public static final String REPOSITORY = "spring-projects/spring-ai";
    public static final int FILE_BYTES = 128 * 1024;
    public static final int BATCH_BYTES = 512 * 1024;
    public static final int HTTP_BYTES = 1024 * 1024;
    private ProjectSourcePolicy() { }

    public static String ref(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || value.contains("..")) throw new ProjectSourceException("SOURCE_REF_INVALID");
        return value;
    }
    public static String commit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}"))
            throw new ProjectSourceException("SOURCE_COMMIT_INVALID");
        return value;
    }
    public static String path(String value) {
        if (value == null || value.length() > 400 || !value.matches("[A-Za-z0-9_. /-]+")
                || !value.equals(value.strip())) throw new ProjectSourceException("SOURCE_PATH_INVALID");
        String[] segments = value.split("/", -1);
        if (segments.length > 8) throw new ProjectSourceException("SOURCE_PATH_INVALID");
        for (String segment : segments) {
            if (segment.isBlank() || segment.length()>255 || segment.equals(".") || segment.equals("..")
                    || !segment.equals(segment.strip())) throw new ProjectSourceException("SOURCE_PATH_INVALID");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")))
            throw new ProjectSourceException("SOURCE_FILE_TYPE_UNSUPPORTED");
        return value;
    }
    public static List<String> paths(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 5)
            throw new ProjectSourceException("SOURCE_PATH_COUNT_INVALID");
        List<String> checked = values.stream().map(ProjectSourcePolicy::path).toList();
        if (checked.stream().distinct().count() != checked.size())
            throw new ProjectSourceException("SOURCE_PATH_DUPLICATE");
        return checked;
    }
    public static String text(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > FILE_BYTES)
            throw new ProjectSourceException("SOURCE_CONTENT_SIZE_INVALID");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.chars().anyMatch(c -> c == 0 || (c < 32 && c != 9 && c != 10 && c != 13)))
                throw new ProjectSourceException("SOURCE_CONTENT_INVALID");
            return text;
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new ProjectSourceException("SOURCE_ENCODING_INVALID");
        }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
