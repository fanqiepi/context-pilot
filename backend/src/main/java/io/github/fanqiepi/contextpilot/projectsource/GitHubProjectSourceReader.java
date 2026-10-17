package io.github.fanqiepi.contextpilot.projectsource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only the dedicated MCP server uses this fixed-host upstream reader. */
public final class GitHubProjectSourceReader implements ProjectSourcePort {
    private static final URI BASE = URI.create("https://api.github.com/repos/spring-projects/spring-ai/");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public GitHubProjectSourceReader(String token) { this(BASE, token); }
    // Test seam only, never populated from HTTP or production configuration.
    GitHubProjectSourceReader(URI base, String token) { this.base = base; this.token = token; }

    @Override public String resolveRef(String ref, Instant deadline) {
        ProjectSourcePolicy.ref(ref);
        Instant end = limit(deadline);
        if (ref.matches("[0-9a-f]{40}")) {
            JsonNode value = get("git/commits/" + ref, end);
            if (!ref.equals(value.path("sha").asText())) fail("SOURCE_RESPONSE_INVALID");
            return ref;
        }
        JsonNode value = get("git/ref/tags/" + ref, end);
        if (!("refs/tags/" + ref).equals(value.path("ref").asText())) fail("SOURCE_RESPONSE_INVALID");
        JsonNode object = value.path("object");
        for (int nesting = 0; nesting < 4; nesting++) {
            String sha = ProjectSourcePolicy.commit(object.path("sha").asText());
            if ("commit".equals(object.path("type").asText())) return sha;
            if (!"tag".equals(object.path("type").asText())) fail("SOURCE_REF_INVALID");
            JsonNode tag = get("git/tags/" + sha, end);
            if (!sha.equals(tag.path("sha").asText())) fail("SOURCE_RESPONSE_INVALID");
            object = tag.path("object");
        }
        throw new ProjectSourceException("SOURCE_TAG_DEPTH_EXCEEDED");
    }

    @Override public ProjectDocumentSnapshot readDocument(String commit, String path, Instant deadline) {
        ProjectSourcePolicy.commit(commit);
        ProjectSourcePolicy.path(path);
        Instant end = limit(deadline);
        JsonNode commitObject = get("git/commits/" + commit, end);
        if (!commit.equals(commitObject.path("sha").asText())) fail("SOURCE_RESPONSE_INVALID");
        String tree = ProjectSourcePolicy.commit(commitObject.path("tree").path("sha").asText());
        String[] segments = path.split("/");
        String blobSha = null;
        int expectedSize = -1;
        // Walk non-recursive trees so symlinks and submodules cannot be followed implicitly.
        for (int index = 0; index < segments.length; index++) {
            JsonNode listing = get("git/trees/" + tree, end);
            if (!tree.equals(listing.path("sha").asText()) || !listing.path("truncated").isBoolean()
                    || listing.path("truncated").asBoolean() || !listing.path("tree").isArray())
                fail("SOURCE_RESPONSE_INVALID");
            JsonNode match = null;
            for (JsonNode entry : listing.path("tree")) {
                if (segments[index].equals(entry.path("path").asText())) {
                    if (match != null) fail("SOURCE_RESPONSE_INVALID");
                    match = entry;
                }
            }
            if (match == null) fail("SOURCE_PATH_NOT_FOUND");
            String sha = ProjectSourcePolicy.commit(match.path("sha").asText());
            if (index < segments.length - 1) {
                if (!"040000".equals(match.path("mode").asText()) || !"tree".equals(match.path("type").asText()))
                    fail("SOURCE_FILE_TYPE_UNSUPPORTED");
                tree = sha;
            } else {
                if (!("100644".equals(match.path("mode").asText()) || "100755".equals(match.path("mode").asText()))
                        || !"blob".equals(match.path("type").asText())) fail("SOURCE_FILE_TYPE_UNSUPPORTED");
                if (!match.path("size").isIntegralNumber() || !match.path("size").canConvertToInt()) fail("SOURCE_RESPONSE_INVALID");
                expectedSize = match.path("size").asInt();
                if (expectedSize <= 0 || expectedSize > ProjectSourcePolicy.FILE_BYTES) fail("SOURCE_CONTENT_SIZE_INVALID");
                blobSha = sha;
            }
        }
        JsonNode blob = get("git/blobs/" + blobSha, end);
        if (!blobSha.equals(blob.path("sha").asText()) || !"base64".equals(blob.path("encoding").asText())
                || !blob.path("size").isIntegralNumber() || blob.path("size").asLong() != expectedSize
                || !blob.path("content").isTextual()) fail("SOURCE_RESPONSE_INVALID");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(blob.path("content").asText().replace("\n", "").replace("\r", "")); }
        catch (IllegalArgumentException error) { throw new ProjectSourceException("SOURCE_ENCODING_INVALID"); }
        if (bytes.length != expectedSize || !blobSha.equals(gitBlobHash(bytes))) fail("SOURCE_CONTENT_HASH_MISMATCH");
        String content = ProjectSourcePolicy.text(bytes);
        if (!Instant.now().isBefore(end)) fail("SOURCE_TIMEOUT");
        return new ProjectDocumentSnapshot(ProjectSourcePolicy.REPOSITORY, commit, path,
                ProjectDocumentSnapshot.mediaTypeFor(path), bytes.length, ProjectSourcePolicy.hash(bytes),
                Instant.now().toString(), content);
    }

    private JsonNode get(String suffix, Instant deadline) {
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis <= 0) throw new ProjectSourceException("SOURCE_TIMEOUT");
        var request = HttpRequest.newBuilder(base.resolve(suffix)).timeout(Duration.ofMillis(millis))
                .header("Accept", "application/vnd.github+json").header("Accept-Encoding", "identity")
                .header("User-Agent", "ContextPilot-project-source").GET();
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        var body = new LimitedBody();
        var future = http.sendAsync(request.build(), info -> {
            if (!"identity".equalsIgnoreCase(info.headers().firstValue("Content-Encoding").orElse("identity")))
                body.reject("SOURCE_ENCODING_INVALID");
            return body;
        });
        try {
            var response = future.get(millis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new ProjectSourceException(switch (response.statusCode()) {
                case 401 -> "SOURCE_UNAUTHORIZED";
                case 403 -> response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                        ? "SOURCE_RATE_LIMITED" : "SOURCE_FORBIDDEN";
                case 404 -> "SOURCE_NOT_FOUND";
                case 429 -> "SOURCE_RATE_LIMITED";
                default -> response.statusCode() >= 300 && response.statusCode() < 400
                        ? "SOURCE_REDIRECT_REJECTED" : "SOURCE_SERVICE_FAILED";
            });
            JsonNode json = JSON.readTree(response.body());
            if (json == null || !json.isObject()) fail("SOURCE_RESPONSE_INVALID");
            return json;
        } catch (ProjectSourceException error) { throw error; }
        catch (TimeoutException error) { throw new ProjectSourceException("SOURCE_TIMEOUT"); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new ProjectSourceException("SOURCE_INTERRUPTED");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof ProjectSourceException safe) throw safe;
            throw new ProjectSourceException("SOURCE_SERVICE_FAILED");
        } catch (RuntimeException error) { throw new ProjectSourceException("SOURCE_RESPONSE_INVALID"); }
        finally { body.cancel(); future.cancel(true); }
    }

    private static Instant limit(Instant deadline) {
        Instant own = Instant.now().plusSeconds(15);
        return own.isBefore(deadline) ? own : deadline;
    }
    static String gitBlobHash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void fail(String code) { throw new ProjectSourceException(code); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (result.isDone()) value.cancel(); else value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > ProjectSourcePolicy.HTTP_BYTES - bytes.size()) {
                    reject("SOURCE_RESPONSE_TOO_LARGE"); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
        void reject(String code) { result.completeExceptionally(new ProjectSourceException(code)); cancel(); }
        void cancel() { if (subscription != null) subscription.cancel(); }
    }
}
