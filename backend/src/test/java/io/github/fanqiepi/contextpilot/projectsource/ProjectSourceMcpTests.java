package io.github.fanqiepi.contextpilot.projectsource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceMcpTests {
    @Test void realHttpProtocolNegotiatesAndReadsOnlyFixedTools() {
        AtomicInteger calls = new AtomicInteger();
        String commit = "a".repeat(40);
        var reader = new ProjectSourcePort() {
            public String resolveRef(String ref, Instant end) { calls.incrementAndGet(); return commit; }
            public ProjectDocumentSnapshot readDocument(String sha, String path, Instant end) {
                calls.incrementAndGet();
                byte[] bytes = "public".getBytes(StandardCharsets.UTF_8);
                return new ProjectDocumentSnapshot(ProjectSourcePolicy.REPOSITORY, sha, path, "text/markdown",
                        bytes.length, ProjectSourcePolicy.hash(bytes), Instant.now().toString(), "public");
            }
        };
        try (var server = new ProjectSourceMcpServer(0, reader);
                var client = new ProjectSourceMcpClient("http://127.0.0.1:" + server.port(), Instant.now().plusSeconds(90))) {
            assertThat(client.resolveRef("v1.0.0", Instant.now().plusSeconds(15))).isEqualTo(commit);
            assertThat(client.readDocument(commit, "README.md", Instant.now().plusSeconds(15)).content()).isEqualTo("public");
            assertThatThrownBy(() -> client.readDocument(commit, "../private.md", Instant.now().plusSeconds(15)))
                    .hasMessage("SOURCE_PATH_INVALID");
            assertThat(calls).hasValue(2);
        }
    }
    @Test void clientRejectsExternalEndpointBeforeConnecting() {
        assertThatThrownBy(() -> new ProjectSourceMcpClient("https://example.com", Instant.now().plusSeconds(90)))
                .hasMessage("SOURCE_ENDPOINT_INVALID");
    }
    @Test void unavailableServiceFailsWithoutToolCalls() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        assertThatThrownBy(()->new ProjectSourceMcpClient("http://127.0.0.1:"+port,Instant.now().plusSeconds(20)))
                .hasMessage("SOURCE_CONNECTION_FAILED");
    }
    @Test void extraAdvertisedToolIsRejectedBeforeReadingAnything() {
        var extra=new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(
                io.modelcontextprotocol.spec.McpSchema.Tool.builder().name("write_file")
                        .inputSchema(java.util.Map.of("type","object","properties",java.util.Map.of())).build(),
                (exchange,request)->{throw new AssertionError("Untrusted tool must never be invoked");});
        try(var server=new ProjectSourceMcpServer(0,java.util.List.of(extra))) {
            assertThatThrownBy(()->new ProjectSourceMcpClient("http://127.0.0.1:"+server.port(),Instant.now().plusSeconds(20)))
                    .hasMessage("SOURCE_TOOLS_INVALID");
        }
    }
    @Test void incompatibleProtocolCannotProduceAClient() throws Exception {
        var fake=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        fake.createContext("/mcp",exchange->{
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            var input=json.readTree(exchange.getRequestBody().readAllBytes());
            var response=java.util.Map.of("jsonrpc","2.0","id",input.path("id").asText(),"result",java.util.Map.of(
                    "protocolVersion","1900-01-01","capabilities",java.util.Map.of(),"serverInfo",java.util.Map.of("name","fixture","version","1")));
            byte[] body=json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        fake.start();
        try {assertThatThrownBy(()->new ProjectSourceMcpClient("http://127.0.0.1:"+fake.getAddress().getPort(),Instant.now().plusSeconds(20)))
                .isInstanceOf(ProjectSourceException.class);}
        finally{fake.stop(0);}
    }
}
