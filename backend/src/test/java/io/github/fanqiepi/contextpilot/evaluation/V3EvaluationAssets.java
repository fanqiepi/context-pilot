package io.github.fanqiepi.contextpilot.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class V3EvaluationAssets {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private V3EvaluationAssets() {
    }

    public static V3EvaluationDataset dataset() {
        return read("datasets/v3-health-maintenance-v1.json", V3EvaluationDataset.class);
    }

    public static V3EvaluationConfig config() {
        return read("configs/v3-health-maintenance-v1.json", V3EvaluationConfig.class);
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
