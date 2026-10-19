package io.github.fanqiepi.contextpilot.projectsource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record ProjectDocumentSnapshot(String repository, String commit, String path,
        String mediaType, int byteCount, String sha256, String fetchedAt, String content) {
    public ProjectDocumentSnapshot {
        if (!ProjectSourcePolicy.REPOSITORY.equals(repository)) throw new ProjectSourceException("SOURCE_SCOPE_INVALID");
        ProjectSourcePolicy.commit(commit);
        ProjectSourcePolicy.path(path);
        if (content == null) throw new ProjectSourceException("SOURCE_CONTENT_INVALID");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ProjectSourcePolicy.text(bytes);
        if (byteCount != bytes.length || !ProjectSourcePolicy.hash(bytes).equals(sha256)
                || !mediaTypeFor(path).equals(mediaType)) throw new ProjectSourceException("SOURCE_SNAPSHOT_INVALID");
        try { Instant.parse(fetchedAt); }
        catch (RuntimeException error) { throw new ProjectSourceException("SOURCE_SNAPSHOT_INVALID"); }
    }
    public static String mediaTypeFor(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".txt") ? "text/plain" : "text/markdown";
    }
}
