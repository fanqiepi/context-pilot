package io.github.fanqiepi.contextpilot.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicEmbeddingModelTests {

    private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();

    @Test
    void producesStableNormalizedVectorsWithConfiguredDimensions() {
        float[] first = embeddingModel.embed(new Document("Spring Boot dependency injection"));
        float[] second = embeddingModel.embed(new Document("Spring Boot dependency injection"));

        assertThat(first).hasSize(1024).containsExactly(second);
        assertThat(length(first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void givesRelatedTextHigherLexicalSimilarity() {
        float[] source = embeddingModel.embed(new Document("Spring Boot dependency injection container"));
        float[] related = embeddingModel.embed(new Document("dependency injection in Spring Boot"));
        float[] unrelated = embeddingModel.embed(new Document("banana cake recipe"));

        assertThat(dot(source, related)).isGreaterThan(dot(source, unrelated));
    }

    private double dot(float[] left, float[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }

    private double length(float[] vector) {
        return Math.sqrt(dot(vector, vector));
    }
}
