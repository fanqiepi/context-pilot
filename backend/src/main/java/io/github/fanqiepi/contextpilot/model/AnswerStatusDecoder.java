package io.github.fanqiepi.contextpilot.model;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;

/** Bounded first-line protocol. Never classifies prose, evidence or preference text. */
final class AnswerStatusDecoder {
    static final String PROTOCOL = "[RAG_ANSWER_PROTOCOL_V1]";
    private final StringBuilder header = new StringBuilder();
    private Boolean refused;
    private boolean hasBody;

    String accept(String text) {
        if (text == null || text.isEmpty()) return "";
        if (refused != null) return body(text);
        int newline = text.indexOf('\n');
        String part = newline < 0 ? text : text.substring(0, newline);
        if (header.length() + part.length() > 16) throw invalid();
        header.append(part);
        if (newline < 0) return "";
        String value = header.toString();
        if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
        refused = switch (value) { case "ANSWERED" -> false; case "REFUSED" -> true; default -> throw invalid(); };
        return body(text.substring(newline + 1));
    }
    private String body(String text) { hasBody |= !text.isBlank(); return text; }
    Boolean refused() { return refused; }
    void finish() { if (refused == null || !hasBody) throw invalid(); }
    private static InternalServiceException invalid() {
        return new InternalServiceException("CHAT_ANSWER_PROTOCOL_INVALID", "Chat answer status is invalid",
                new IllegalStateException("Missing, invalid or empty answer envelope"));
    }
}
