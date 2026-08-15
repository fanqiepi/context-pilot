package io.github.fanqiepi.contextpilot.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class V2EvaluationAssets {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private V2EvaluationAssets() {
    }

    public static V2EvaluationDataset dataset() {
        return read("datasets/v2-routing-action-safety-v1.json", V2EvaluationDataset.class);
    }

    public static V2EvaluationConfig config() {
        return read("configs/v2-routing-action-safety-v1.json", V2EvaluationConfig.class);
    }

    private static <T> T read(String relativePath, Class<T> type) {
        Path path = root().resolve(relativePath).normalize();
        try {
            return OBJECT_MAPPER.readValue(path.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read evaluation asset " + path, exception);
        }
    }

    private static Path root() {
        String configured = System.getProperty("contextpilot.evals.root");
        List<Path> candidates = configured == null || configured.isBlank()
                ? List.of(Path.of("evals"), Path.of("..", "evals"))
                : List.of(Path.of(configured));
        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Evaluation root not found. Set -Dcontextpilot.evals.root=<path>"));
    }
}
