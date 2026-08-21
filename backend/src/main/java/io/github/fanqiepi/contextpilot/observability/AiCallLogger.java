package io.github.fanqiepi.contextpilot.observability;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiCallLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiCallLogger.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(bearer\\s+)[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_ ]?key|authorization|access[-_ ]?token|secret)\\s*[:=]\\s*)[^\\s,;]+",
            Pattern.CASE_INSENSITIVE);

    private AiCallLogger() {
    }

    public static void started(AiCallContext context) {
        LOGGER.info(
                "ai.call.started operation={} provider={} model={} traceId={} callId={} "
                        + "resourceType={} resourceId={} promptVersion={} inputCharacters={} "
                        + "itemCount={} maxOutputTokens={}",
                value(context.operation()), value(context.provider()), value(context.model()),
                value(context.traceId()), value(context.callId()), value(context.resourceType()),
                value(context.resourceId()), value(context.promptVersion()), value(context.inputCharacters()),
                value(context.itemCount()), value(context.maxOutputTokens()));
    }

    public static void succeeded(
            AiCallContext context,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Integer resultCount) {
        LOGGER.info(
                "ai.call.succeeded operation={} provider={} model={} traceId={} callId={} "
                        + "resourceType={} resourceId={} latencyMs={} promptTokens={} "
                        + "completionTokens={} totalTokens={} resultCount={}",
                value(context.operation()), value(context.provider()), value(context.model()),
                value(context.traceId()), value(context.callId()), value(context.resourceType()),
                value(context.resourceId()), latencyMs, value(promptTokens), value(completionTokens),
                value(totalTokens), value(resultCount));
    }

    public static void failed(AiCallContext context, long latencyMs, Throwable exception) {
        Throwable root = rootCause(exception);
        LOGGER.error(
                "ai.call.failed operation={} provider={} model={} traceId={} callId={} "
                        + "resourceType={} resourceId={} latencyMs={} errorType={} errorMessage={}",
                value(context.operation()), value(context.provider()), value(context.model()),
                value(context.traceId()), value(context.callId()), value(context.resourceType()),
                value(context.resourceId()), latencyMs, root.getClass().getName(),
                safeMessage(root.getMessage()), sanitizedException(exception));
    }

    public static void cancelled(AiCallContext context, long latencyMs) {
        LOGGER.info(
                "ai.call.cancelled operation={} provider={} model={} traceId={} callId={} "
                        + "resourceType={} resourceId={} latencyMs={}",
                value(context.operation()), value(context.provider()), value(context.model()),
                value(context.traceId()), value(context.callId()), value(context.resourceType()),
                value(context.resourceId()), latencyMs);
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").strip();
        normalized = BEARER_SECRET.matcher(normalized).replaceAll("$1[REDACTED]");
        normalized = NAMED_SECRET.matcher(normalized).replaceAll("$1[REDACTED]");
        return normalized.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "-" : value.toString();
    }

    private static RuntimeException sanitizedException(Throwable exception) {
        return sanitizedException(
                exception,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static RuntimeException sanitizedException(Throwable exception, Set<Throwable> visited) {
        if (!visited.add(exception)) {
            return new RuntimeException("Cyclic exception cause omitted");
        }
        RuntimeException sanitizedCause = exception.getCause() == null || exception.getCause() == exception
                ? null
                : sanitizedException(exception.getCause(), visited);
        RuntimeException sanitized = new RuntimeException(
                exception.getClass().getName() + ": " + safeMessage(exception.getMessage()),
                sanitizedCause);
        sanitized.setStackTrace(exception.getStackTrace());
        for (Throwable suppressed : exception.getSuppressed()) {
            sanitized.addSuppressed(sanitizedException(suppressed, visited));
        }
        return sanitized;
    }
}
