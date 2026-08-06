package io.github.fanqiepi.contextpilot.document.processing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.fanqiepi.contextpilot.document.DocumentFileType;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class SpringAiDocumentParser {

    private static final String SOURCE_FILENAME = "source_filename";
    private static final String FILE_TYPE = "file_type";
    private static final String PART_INDEX = "part_index";

    public List<ParsedDocumentPart> parse(
            DocumentFileType fileType,
            String filename,
            InputStream content) {
        if (fileType == null) {
            throw new DocumentParsingException("Document file type must not be null");
        }
        if (filename == null || filename.isBlank()) {
            throw new DocumentParsingException("Document filename must not be blank");
        }
        if (content == null) {
            throw new DocumentParsingException("Document content must not be null");
        }

        byte[] bytes = readContent(content);
        if (bytes.length == 0) {
            throw new DocumentParsingException("Document content must not be empty");
        }
        if (fileType != DocumentFileType.PDF) {
            validateUtf8(bytes);
        }

        try {
            Resource resource = namedResource(bytes, filename);
            List<Document> documents = createReader(fileType, resource).read();
            return toParsedParts(documents, fileType, filename);
        } catch (DocumentParsingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentParsingException("Could not parse document " + filename, exception);
        }
    }

    private byte[] readContent(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (IOException exception) {
            throw new DocumentParsingException("Could not read document content", exception);
        }
    }

    private void validateUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw new DocumentParsingException("TXT and Markdown documents must use UTF-8 encoding", exception);
        }
    }

    private DocumentReader createReader(DocumentFileType fileType, Resource resource) {
        return switch (fileType) {
            case TXT -> textReader(resource);
            case MARKDOWN -> markdownReader(resource);
            case PDF -> pdfReader(resource);
        };
    }

    private TextReader textReader(Resource resource) {
        TextReader reader = new TextReader(resource);
        reader.setCharset(StandardCharsets.UTF_8);
        return reader;
    }

    private MarkdownDocumentReader markdownReader(Resource resource) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withIncludeBlockquote(true)
                .withIncludeCodeBlock(true)
                .build();
        return new MarkdownDocumentReader(resource, config);
    }

    private PagePdfDocumentReader pdfReader(Resource resource) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        return new PagePdfDocumentReader(resource, config);
    }

    private List<ParsedDocumentPart> toParsedParts(
            List<Document> documents,
            DocumentFileType fileType,
            String filename) {
        List<ParsedDocumentPart> parts = new ArrayList<>();
        if (documents != null) {
            for (Document document : documents) {
                String text = normalize(document.getText(), fileType);
                if (text.isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = copyMetadata(document.getMetadata());
                metadata.put(SOURCE_FILENAME, filename);
                metadata.put(FILE_TYPE, fileType.name());
                metadata.put(PART_INDEX, parts.size());
                parts.add(new ParsedDocumentPart(text, metadata));
            }
        }
        if (parts.isEmpty()) {
            throw new DocumentParsingException("Document does not contain extractable text");
        }
        return List.copyOf(parts);
    }

    private Map<String, Object> copyMetadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    metadata.put(key, value);
                }
            });
        }
        return metadata;
    }

    private String normalize(String text, DocumentFileType fileType) {
        if (text == null) {
            return "";
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\uFEFF", "");
        if (fileType == DocumentFileType.PDF) {
            normalized = normalized.replaceAll("[\\t ]+", " ");
        }
        normalized = normalized.replaceAll("(?m)[\\t ]+$", "");
        return normalized.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private Resource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes, filename) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
