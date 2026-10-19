package io.github.fanqiepi.contextpilot.projectsource;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GitHubProjectSourceReaderTests {
    private static final String COMMIT = "a".repeat(40), TREE = "b".repeat(40);
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private GitHubProjectSourceReader reader;
    private volatile int status = 200;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = bodies.getOrDefault(exchange.getRequestURI().getPath(), "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        reader = new GitHubProjectSourceReader(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void readsImmutableBlobWithVerifiedContentAndSource() {
        byte[] bytes = "# Public project\n公开项目".getBytes(StandardCharsets.UTF_8);
        fixture(bytes, "100644");
        bodies.put("/git/ref/tags/v1.0.0", "{\"ref\":\"refs/tags/v1.0.0\",\"object\":{\"type\":\"commit\",\"sha\":\"" + COMMIT + "\"}}");
        String resolved = reader.resolveRef("v1.0.0", Instant.now().plusSeconds(30));
        ProjectDocumentSnapshot snapshot = reader.readDocument(resolved, "README.md", Instant.now().plusSeconds(30));
        assertThat(snapshot.commit()).isEqualTo(COMMIT);
        assertThat(snapshot.sha256()).isEqualTo(ProjectSourcePolicy.hash(bytes));
        assertThat(snapshot.byteCount()).isEqualTo(bytes.length);
        assertThat(requests).hasValue(4);
    }
    @Test void rejectsSymlinkWithoutFetchingBlob() {
        fixture("target".getBytes(StandardCharsets.UTF_8), "120000");
        assertThatThrownBy(() -> reader.readDocument(COMMIT, "README.md", Instant.now().plusSeconds(30)))
                .hasMessage("SOURCE_FILE_TYPE_UNSUPPORTED");
        assertThat(requests).hasValue(2);
    }
    @Test void rejectsTamperedBlobAndExpiredDeadline() {
        fixture("text".getBytes(StandardCharsets.UTF_8), "100644");
        String sha = GitHubProjectSourceReader.gitBlobHash("text".getBytes(StandardCharsets.UTF_8));
        bodies.put("/git/blobs/" + sha, "{\"sha\":\"" + sha + "\",\"encoding\":\"base64\",\"size\":4,\"content\":\"ZXZpbA==\"}");
        assertThatThrownBy(() -> reader.readDocument(COMMIT, "README.md", Instant.now().plusSeconds(30)))
                .hasMessage("SOURCE_CONTENT_HASH_MISMATCH");
        int before = requests.get();
        assertThatThrownBy(() -> reader.resolveRef(COMMIT, Instant.now().minusSeconds(1))).hasMessage("SOURCE_TIMEOUT");
        assertThat(requests).hasValue(before);
    }
    @Test void rejectsRedirectAndOversizedResponse() {
        status = 302;
        assertThatThrownBy(() -> reader.resolveRef(COMMIT, Instant.now().plusSeconds(30))).hasMessage("SOURCE_REDIRECT_REJECTED");
        assertThat(requests).hasValue(1);
        status = 200;
        bodies.put("/git/commits/" + COMMIT, "x".repeat(ProjectSourcePolicy.HTTP_BYTES + 1));
        assertThatThrownBy(() -> reader.resolveRef(COMMIT, Instant.now().plusSeconds(30))).hasMessage("SOURCE_RESPONSE_TOO_LARGE");
    }
    private void fixture(byte[] bytes, String mode) {
        String blob = GitHubProjectSourceReader.gitBlobHash(bytes);
        bodies.put("/git/commits/" + COMMIT, "{\"sha\":\"" + COMMIT + "\",\"tree\":{\"sha\":\"" + TREE + "\"}}");
        bodies.put("/git/trees/" + TREE, JsonMapper.builder().build().writeValueAsString(Map.of("sha", TREE, "truncated", false,
                "tree", java.util.List.of(Map.of("path", "README.md", "mode", mode, "type", "blob", "sha", blob, "size", bytes.length)))));
        bodies.put("/git/blobs/" + blob, JsonMapper.builder().build().writeValueAsString(Map.of("sha", blob,
                "encoding", "base64", "size", bytes.length, "content", Base64.getEncoder().encodeToString(bytes))));
    }
}
