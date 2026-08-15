package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;

final class DocumentActionParameterValidation {

    private DocumentActionParameterValidation() {
    }

    static UUID requireId(UUID value, String code, String fieldName) {
        if (value == null) {
            throw new BadRequestException(code, fieldName + " must not be null");
        }
        return value;
    }

    static String filename(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new BadRequestException(
                    "INVALID_ACTION_FILENAME_SNAPSHOT",
                    "Original filename snapshot must not be blank");
        }
        if (normalized.length() > 255) {
            throw new BadRequestException(
                    "ACTION_FILENAME_SNAPSHOT_TOO_LONG",
                    "Original filename snapshot must not exceed 255 characters");
        }
        return normalized;
    }

    static DocumentStatus requireStatus(DocumentStatus actual, DocumentStatus expected, ActionType actionType) {
        if (actual != expected) {
            throw new BadRequestException(
                    "INVALID_ACTION_DOCUMENT_STATUS",
                    actionType + " requires observed document status " + expected);
        }
        return actual;
    }

    static String embeddingProfileId(String value) {
        String normalized = normalizeNullable(value);
        if (normalized != null && normalized.length() > 100) {
            throw new BadRequestException(
                    "ACTION_EMBEDDING_PROFILE_ID_TOO_LONG",
                    "Observed embedding profile id must not exceed 100 characters");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }
}
