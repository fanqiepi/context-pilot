package io.github.fanqiepi.contextpilot.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class V5EvaluationAssets {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private V5EvaluationAssets() {
    }

    public static V5EvaluationDataset dataset() {
        return read("datasets/v5-explicit-memory-v1.json", V5EvaluationDataset.class);
    }

    public static V5EvaluationConfig config() {
        return read("configs/v5-explicit-memory-v1.json", V5EvaluationConfig.class);
    }

    public static Map<String, String> corpus(V5EvaluationDataset dataset) {
        Map<String, String> documents = new LinkedHashMap<>();
        for (V5EvaluationDataset.DocumentFixture fixture : dataset.documents()) {
            Path path = root().resolve(fixture.corpusPath()).normalize();
            if (!path.startsWith(root())) {
                throw new IllegalStateException("Corpus path escapes evaluation root: " + path);
            }
            try {
                documents.put(fixture.id(), Files.readString(path));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read V5 corpus document " + path, exception);
            }
        }
        return Map.copyOf(documents);
    }

    private static <T> T read(String relativePath, Class<T> type) {
        Path path = root().resolve(relativePath).normalize();
        if (!path.startsWith(root())) {
            throw new IllegalStateException("Evaluation asset path escapes root: " + path);
        }
        try {
            return OBJECT_MAPPER.readValue(path.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read V5 evaluation asset " + path, exception);
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
