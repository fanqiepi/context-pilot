package io.github.fanqiepi.contextpilot.projectsource;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.DisposableServer;

/** Independent entry point: no database, chat model, component scanning, or business APIs. */
public final class ProjectSourceMcpServer implements AutoCloseable {
    private final McpSyncServer mcp;
    private final DisposableServer http;
    public ProjectSourceMcpServer(int port, ProjectSourcePort reader) {
        this(port,ProjectSourceMcpTools.specifications(reader));
    }
    ProjectSourceMcpServer(int port,java.util.List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tools) {
        var transport = WebFluxStreamableServerTransportProvider.builder().messageEndpoint("/mcp").build();
        mcp = McpServer.sync(transport).serverInfo("contextpilot-project-source", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .requestTimeout(Duration.ofSeconds(15)).tools(tools).build();
        var strategies = HandlerStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(8192))
                .webFilter((exchange, chain) -> {
                    // The local adapter is backend-only; cross-origin browser calls are never needed.
                    if (exchange.getRequest().getHeaders().containsHeader("Origin")) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                }).build();
        var handler = RouterFunctions.toHttpHandler(transport.getRouterFunction(), strategies);
        http = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(port)
                .httpRequestDecoder(spec -> spec.maxHeaderSize(8192).maxInitialLineLength(2048))
                .handle(new ReactorHttpHandlerAdapter(handler)).bindNow();
    }
    public int port() { return http.port(); }
    @Override public void close() { mcp.closeGracefully(); http.disposeNow(); }
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PROJECT_SOURCE_MCP_PORT", "8091"));
        var server = new ProjectSourceMcpServer(port,
                new GitHubProjectSourceReader(System.getenv("GITHUB_PROJECT_SOURCE_TOKEN")));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.http.onDispose().block();
    }
}
