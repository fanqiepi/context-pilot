package io.github.fanqiepi.contextpilot.document.processing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.fanqiepi.contextpilot.document.DocumentFileType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiDocumentParserTests {

    private final SpringAiDocumentParser parser = new SpringAiDocumentParser();

    @Test
    void parsesAndNormalizesUtf8Text() {
        byte[] content = "\uFEFF第一行  \r\n\r\n\r\n第二行".getBytes(StandardCharsets.UTF_8);

        List<ParsedDocumentPart> parts = parser.parse(
                DocumentFileType.TXT,
                "notes.txt",
                new ByteArrayInputStream(content));

        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().text()).isEqualTo("第一行\n\n第二行");
        assertThat(parts.getFirst().metadata())
                .containsEntry("source_filename", "notes.txt")
                .containsEntry("file_type", "TXT")
                .containsEntry("part_index", 0);
    }

    @Test
    void parsesMarkdownContent() {
        String markdown = "# ContextPilot\n\n知识库内容。\n\n```java\nint answer = 42;\n```";

        List<ParsedDocumentPart> parts = parser.parse(
                DocumentFileType.MARKDOWN,
                "guide.md",
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));

        assertThat(parts).isNotEmpty();
        assertThat(parts).extracting(ParsedDocumentPart::text)
                .anyMatch(text -> text.contains("知识库内容"))
                .anyMatch(text -> text.contains("answer = 42"));
    }

    @Test
    void parsesTextBasedPdfAndKeepsPageMetadata() throws Exception {
        byte[] pdf = textPdf("ContextPilot PDF content");

        List<ParsedDocumentPart> parts = parser.parse(
                DocumentFileType.PDF,
                "guide.pdf",
                new ByteArrayInputStream(pdf));

        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().text()).contains("ContextPilot PDF content");
        assertThat(parts.getFirst().metadata())
                .containsEntry("source_filename", "guide.pdf")
                .containsKeys("page_number");
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};

        assertThatThrownBy(() -> parser.parse(
                DocumentFileType.TXT,
                "broken.txt",
                new ByteArrayInputStream(malformed)))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsDocumentsWithoutExtractableText() throws Exception {
        byte[] pdf = blankPdf();

        assertThatThrownBy(() -> parser.parse(
                DocumentFileType.PDF,
                "scanned.pdf",
                new ByteArrayInputStream(pdf)))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("extractable text");
    }

    private byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            return save(document);
        }
    }

    private byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            return save(document);
        }
    }

    private byte[] save(PDDocument document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
