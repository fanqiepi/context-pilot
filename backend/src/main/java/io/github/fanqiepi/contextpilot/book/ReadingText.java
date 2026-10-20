package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class ReadingText {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private ReadingText() {}

    public static int length(String text) { return text.codePointCount(0, text.length()); }
    public static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
    public static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String json(Object value) { return JSON.writeValueAsString(value); }
    public static <T> T read(String value, Class<T> type) {
        try {
            T result=JSON.readValue(value,type);
            if(result==null)throw new IllegalArgumentException();
            return result;
        }
        catch (RuntimeException error) { throw ReadingException.invalid("INVALID_REQUEST", "请求字段或格式不正确"); }
    }
    public static String bounded(String value, int min, int max, String name) {
        String text = value == null ? "" : value.strip();
        if (length(text) < min || length(text) > max || text.indexOf('\0') >= 0)
            throw ReadingException.invalid("INVALID_REQUEST", name + "长度或内容不合法");
        return text;
    }
    public static void require(boolean valid, String message) {
        if (!valid) throw ReadingException.invalid("INVALID_REQUEST", message);
    }
}
