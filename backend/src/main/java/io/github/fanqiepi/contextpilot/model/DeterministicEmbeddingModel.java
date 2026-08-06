package io.github.fanqiepi.contextpilot.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("offline")
public class DeterministicEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>(request.getInstructions().size());
        for (int index = 0; index < request.getInstructions().size(); index++) {
            embeddings.add(new Embedding(vectorize(request.getInstructions().get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] vectorize(String text) {
        float[] vector = new float[DIMENSIONS];
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        int[] codePoints = normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .toArray();
        for (int index = 0; index < codePoints.length; index++) {
            addFeature(vector, codePoints[index], 0x9E3779B9);
            if (index + 1 < codePoints.length) {
                addFeature(vector, 31 * codePoints[index] + codePoints[index + 1], 0x85EBCA6B);
            }
        }
        normalize(vector);
        return vector;
    }

    private void addFeature(float[] vector, int value, int salt) {
        int hash = value ^ salt;
        hash ^= hash >>> 16;
        hash *= 0x7FEB352D;
        hash ^= hash >>> 15;
        int position = Math.floorMod(hash, DIMENSIONS);
        vector[position] += (hash & 1) == 0 ? 1.0f : -1.0f;
    }

    private void normalize(float[] vector) {
        double squaredLength = 0;
        for (float value : vector) {
            squaredLength += value * value;
        }
        if (squaredLength == 0) {
            vector[0] = 1.0f;
            return;
        }
        float length = (float) Math.sqrt(squaredLength);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= length;
        }
    }
}
