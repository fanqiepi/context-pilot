package io.github.fanqiepi.contextpilot.projectsource;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class ProjectSourceMcpTools {
    public static final String RESOLVE = "resolve_project_ref", READ = "read_project_document";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ProjectSourceMcpTools() { }
    public static McpSchema.Tool definition(String name) {
        Map<String, Object> fields = switch (name) {
            case RESOLVE -> Map.of("ref", Map.of("type", "string", "maxLength", 100));
            case READ -> Map.of("commit", Map.of("type", "string", "pattern", "^[0-9a-f]{40}$"),
                    "path", Map.of("type", "string", "maxLength", 400));
            default -> throw new ProjectSourceException("SOURCE_TOOL_NOT_ALLOWED");
        };
        var schema = new McpSchema.JsonSchema("object", fields, fields.keySet().stream().sorted().toList(), false, null, null);
        return McpSchema.Tool.builder().name(name).description("Read fixed public Spring AI project data")
                .inputSchema(schema).build();
    }
    public static List<McpServerFeatures.SyncToolSpecification> specifications(ProjectSourcePort reader) {
        return List.of(specification(RESOLVE, reader), specification(READ, reader));
    }
    private static McpServerFeatures.SyncToolSpecification specification(String name, ProjectSourcePort reader) {
        return new McpServerFeatures.SyncToolSpecification(definition(name), (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                Set<String> expected = name.equals(RESOLVE) ? Set.of("ref") : Set.of("commit", "path");
                if (args == null || !args.keySet().equals(expected) || args.values().stream().anyMatch(v -> !(v instanceof String)))
                    throw new ProjectSourceException("SOURCE_ARGUMENT_INVALID");
                Object result = name.equals(RESOLVE)
                        ? Map.of("repository", ProjectSourcePolicy.REPOSITORY, "ref", args.get("ref"),
                                "commit", reader.resolveRef((String) args.get("ref"), Instant.now().plusSeconds(15)))
                        : reader.readDocument((String) args.get("commit"), (String) args.get("path"), Instant.now().plusSeconds(15));
                return McpSchema.CallToolResult.builder().addTextContent(JSON.writeValueAsString(result)).isError(false).build();
            } catch (ProjectSourceException error) {
                return McpSchema.CallToolResult.builder().addTextContent(error.code()).isError(true).build();
            } catch (RuntimeException error) {
                return McpSchema.CallToolResult.builder().addTextContent("SOURCE_SERVICE_FAILED").isError(true).build();
            }
        });
    }
}
