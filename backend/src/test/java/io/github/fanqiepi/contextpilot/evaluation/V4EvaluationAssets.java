package io.github.fanqiepi.contextpilot.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class V4EvaluationAssets {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private V4EvaluationAssets() {
    }

    public static V4EvaluationDataset dataset() {
        return read("datasets/v4-document-comparison-v1.json", V4EvaluationDataset.class);
    }

    public static V4EvaluationConfig config() {
        return read("configs/v4-document-comparison-v1.json", V4EvaluationConfig.class);
    }

    public static Map<String, CorpusDocument> corpus(V4EvaluationDataset dataset) {
        Map<String, CorpusDocument> documents = new LinkedHashMap<>();
        for (V4EvaluationDataset.DocumentFixture fixture : dataset.documents()) {
            Path path = root().resolve(fixture.corpusPath()).normalize();
            if (!path.startsWith(root())) {
                throw new IllegalStateException("Corpus path escapes evaluation root: " + path);
            }
            documents.put(fixture.id(), parse(fixture, path));
        }
        return Map.copyOf(documents);
    }

    private static CorpusDocument parse(V4EvaluationDataset.DocumentFixture fixture, Path path) {
        try {
            List<CorpusChunk> chunks = new ArrayList<>();
            String dimension = null;
            StringBuilder content = new StringBuilder();
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("## ")) {
                    addChunk(chunks, fixture.id(), dimension, content);
                    dimension = line.substring(3).strip();
                    content.setLength(0);
                } else if (dimension != null && !line.startsWith("# ")) {
                    if (!line.isBlank()) {
                        if (!content.isEmpty()) {
                            content.append('\n');
                        }
                        content.append(line.strip());
                    }
                }
            }
            addChunk(chunks, fixture.id(), dimension, content);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("Corpus document has no level-two sections: " + path);
            }
            return new CorpusDocument(
                    fixture.id(), fixture.knowledgeBaseId(), fixture.filename(), path, List.copyOf(chunks));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read V4 corpus document " + path, exception);
        }
    }

    private static void addChunk(
            List<CorpusChunk> chunks,
            String documentId,
            String dimension,
            StringBuilder content) {
        if (dimension != null && !content.isEmpty()) {
            chunks.add(new CorpusChunk(
                    documentId + ":" + dimension,
                    documentId,
                    dimension,
                    content.toString()));
        }
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

    public record CorpusDocument(
            String id,
            String knowledgeBaseId,
            String filename,
            Path path,
            List<CorpusChunk> chunks) {
    }

    public record CorpusChunk(
            String id,
            String documentId,
            String dimension,
            String content) {
    }
}
