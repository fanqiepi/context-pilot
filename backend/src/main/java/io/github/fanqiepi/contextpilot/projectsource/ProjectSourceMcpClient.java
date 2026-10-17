package io.github.fanqiepi.contextpilot.projectsource;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A per-preview connection, not attached to any ChatClient or model tool registry. */
public final class ProjectSourceMcpClient implements ProjectSourcePort, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final McpSyncClient client;
    private final Instant deadline;
    public ProjectSourceMcpClient(String endpoint, Instant deadline) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new ProjectSourceException("SOURCE_ENDPOINT_INVALID");
        this.deadline = deadline;
        var http = reactor.netty.http.client.HttpClient.create().followRedirect(false)
                .disableRetry(true).responseTimeout(Duration.ofSeconds(15));
        var web = WebClient.builder().baseUrl(endpoint).clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(ProjectSourcePolicy.HTTP_BYTES));
        var transport = WebClientStreamableHttpTransport.builder(web).endpoint("/mcp")
                .supportedProtocolVersions(List.of("2025-11-25"))
                .openConnectionOnStartup(false).resumableStreams(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15))
                .capabilities(McpSchema.ClientCapabilities.builder().build()).build();
        try {
            var initialized = client.initialize();
            if (!"2025-11-25".equals(initialized.protocolVersion())) throw new ProjectSourceException("SOURCE_PROTOCOL_INCOMPATIBLE");
            var result = client.listTools();
            if (result.nextCursor() != null || result.tools().size() != 2) throw new ProjectSourceException("SOURCE_TOOLS_INVALID");
            for (String name : List.of(ProjectSourceMcpTools.RESOLVE, ProjectSourceMcpTools.READ)) {
                var matches = result.tools().stream().filter(t -> name.equals(t.name())).toList();
                if (matches.size() != 1 || !ProjectSourceMcpTools.definition(name).inputSchema().equals(matches.getFirst().inputSchema()))
                    throw new ProjectSourceException("SOURCE_TOOLS_INVALID");
            }
            checkDeadline(deadline);
        } catch (RuntimeException error) {
            client.close();
            if (error instanceof ProjectSourceException safe) throw safe;
            throw new ProjectSourceException("SOURCE_CONNECTION_FAILED");
        }
    }
    @Override public String resolveRef(String ref, Instant end) {
        ProjectSourcePolicy.ref(ref);
        JsonNode value = call(ProjectSourceMcpTools.RESOLVE, Map.of("ref", ref), end);
        if (!ProjectSourcePolicy.REPOSITORY.equals(value.path("repository").asText()) || !ref.equals(value.path("ref").asText()))
            throw new ProjectSourceException("SOURCE_SCOPE_INVALID");
        return ProjectSourcePolicy.commit(value.path("commit").asText());
    }
    @Override public ProjectDocumentSnapshot readDocument(String commit, String path, Instant end) {
        ProjectSourcePolicy.commit(commit); ProjectSourcePolicy.path(path);
        try {
            var snapshot = JSON.treeToValue(call(ProjectSourceMcpTools.READ, Map.of("commit", commit, "path", path), end), ProjectDocumentSnapshot.class);
            if (!commit.equals(snapshot.commit()) || !path.equals(snapshot.path())) throw new ProjectSourceException("SOURCE_SCOPE_INVALID");
            return snapshot;
        } catch (ProjectSourceException error) { throw error; }
        catch (RuntimeException error) { throw new ProjectSourceException("SOURCE_RESPONSE_INVALID"); }
    }
    private JsonNode call(String name, Map<String, Object> args, Instant end) {
        checkDeadline(end);
        try {
            var result = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build());
            checkDeadline(end);
            if (result.content() == null || result.content().size() != 1 || !(result.content().getFirst() instanceof McpSchema.TextContent text))
                throw new ProjectSourceException("SOURCE_RESPONSE_INVALID");
            if (Boolean.TRUE.equals(result.isError())) {
                String code = text.text();
                throw new ProjectSourceException(code != null && code.matches("SOURCE_[A-Z_]{1,60}") ? code : "SOURCE_TOOL_FAILED");
            }
            JsonNode json = JSON.readTree(text.text());
            if (json == null || !json.isObject()) throw new ProjectSourceException("SOURCE_RESPONSE_INVALID");
            return json;
        } catch (ProjectSourceException error) { throw error; }
        catch (RuntimeException error) { throw new ProjectSourceException("SOURCE_TOOL_FAILED"); }
    }
    private void checkDeadline(Instant end) {
        if (!Instant.now().isBefore(deadline) || !Instant.now().isBefore(end)) throw new ProjectSourceException("SOURCE_TIMEOUT");
    }
    @Override public void close() { client.closeGracefully(); }
}
