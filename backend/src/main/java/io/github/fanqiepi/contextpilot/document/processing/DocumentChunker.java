package io.github.fanqiepi.contextpilot.document.processing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {

    private static final String[] BOUNDARIES = {
            "\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", "; "
    };

    private final DocumentChunkingProperties properties;

    public DocumentChunker(DocumentChunkingProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunk> chunk(List<ParsedDocumentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("Parsed document parts must not be empty");
        }
        properties.validate();

        List<DocumentChunk> chunks = new ArrayList<>();
        for (ParsedDocumentPart part : parts) {
            appendPartChunks(part, chunks);
        }
        return List.copyOf(chunks);
    }

    private void appendPartChunks(ParsedDocumentPart part, List<DocumentChunk> chunks) {
        String text = part.text();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + properties.getMaxCharacters(), text.length());
            int end = findBoundary(text, start, hardEnd);
            String chunkText = text.substring(start, end).strip();
            if (!chunkText.isEmpty()) {
                int index = chunks.size();
                Map<String, Object> metadata = new LinkedHashMap<>(part.metadata());
                metadata.put("chunk_index", index);
                chunks.add(new DocumentChunk(index, chunkText, metadata));
            }
            if (end >= text.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - properties.getOverlapCharacters());
            while (nextStart < end && Character.isWhitespace(text.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }
    }

    private int findBoundary(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int minimumBoundary = start + Math.max(1, properties.getMaxCharacters() / 2);
        int best = -1;
        for (String boundary : BOUNDARIES) {
            int candidate = text.lastIndexOf(boundary, hardEnd - boundary.length());
            int candidateEnd = candidate < 0 ? -1 : candidate + boundary.length();
            if (candidateEnd >= minimumBoundary && candidateEnd > best) {
                best = candidateEnd;
            }
        }
        return best > start ? best : hardEnd;
    }
}
