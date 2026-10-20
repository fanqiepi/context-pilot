package io.github.fanqiepi.contextpilot.book;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BookRows {
    private BookRows() {}
    public record Book(UUID id, UUID ownerId, String title, String sourceDescription, UUID latestVersionId,
                       long revision, OffsetDateTime createdAt, int deleted, UUID readingVersionId, OffsetDateTime lastReadAt) {}
    public record Version(UUID id, UUID ownerId, UUID bookId, String fileHash, String textHash, String parserId,
                          int parserVersion, String optionsHash, int chapterCount, int paragraphCount, int characterCount,
                          OffsetDateTime createdAt, int deleted) {}
    public record Chapter(UUID id, UUID versionId, int chapterOrdinal, String title, int firstParagraphOrdinal,
                          int lastParagraphOrdinal, String textHash) {}
    public record Paragraph(UUID id, UUID versionId, UUID chapterId, int paragraphOrdinal, int chapterParagraphOrdinal,
                            String text, int codePointCount, String textHash) {}
}
