package io.github.fanqiepi.contextpilot.document.processing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTests {

    @Test
    void keepsShortTextInOneChunk() {
        DocumentChunker chunker = chunker(100, 10);

        List<DocumentChunk> chunks = chunker.chunk(List.of(
                new ParsedDocumentPart("short text", Map.of("part_index", 0))));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.getFirst().text()).isEqualTo("short text");
        assertThat(chunks.getFirst().metadata())
                .containsEntry("part_index", 0)
                .containsEntry("chunk_index", 0);
    }

    @Test
    void splitsWithStableOverlapAndIndexes() {
        DocumentChunker chunker = chunker(20, 5);
        String text = "0123456789012345678901234567890123456789";

        List<DocumentChunk> first = chunker.chunk(List.of(new ParsedDocumentPart(text, Map.of())));
        List<DocumentChunk> second = chunker.chunk(List.of(new ParsedDocumentPart(text, Map.of())));

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(DocumentChunk::index).containsExactly(0, 1, 2);
        assertThat(first.get(0).text()).isEqualTo("01234567890123456789");
        assertThat(first.get(1).text()).startsWith(first.get(0).text().substring(15));
        assertThat(first).allMatch(chunk -> chunk.text().length() <= 20);
    }

    @Test
    void prefersParagraphBoundaryAndContinuesIndexesAcrossParts() {
        DocumentChunker chunker = chunker(24, 4);
        List<ParsedDocumentPart> parts = List.of(
                new ParsedDocumentPart("first paragraph.\n\nsecond paragraph is longer.", Map.of("part_index", 0)),
                new ParsedDocumentPart("third part", Map.of("part_index", 1)));

        List<DocumentChunk> chunks = chunker.chunk(parts);

        assertThat(chunks.getFirst().text()).isEqualTo("first paragraph.");
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        for (int index = 0; index < chunks.size(); index++) {
            assertThat(chunks.get(index).index()).isEqualTo(index);
        }
        assertThat(chunks.getLast().metadata()).containsEntry("part_index", 1);
    }

    @Test
    void rejectsInvalidChunkingConfiguration() {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        properties.setMaxCharacters(10);
        properties.setOverlapCharacters(10);
        DocumentChunker chunker = new DocumentChunker(properties);

        assertThatThrownBy(() -> chunker.chunk(List.of(
                new ParsedDocumentPart("content", Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap-characters");
    }

    private DocumentChunker chunker(int maxCharacters, int overlapCharacters) {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        properties.setMaxCharacters(maxCharacters);
        properties.setOverlapCharacters(overlapCharacters);
        return new DocumentChunker(properties);
    }
}
