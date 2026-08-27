package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.service.KnowledgeDocumentParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class KnowledgeDocumentParserTest {

    private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser();

    @Test
    void extractsAndNormalizesPlainTextWithBoundedMetadata() {
        var parsed = parser.parse(
            new ByteArrayInputStream("first\r\n\r\n\r\nsecond\0".getBytes(StandardCharsets.UTF_8)),
            "procedure.txt",
            "text/plain"
        );

        assertEquals("first\n\nsecond", parsed.content());
        assertFalse(parsed.parserType().isBlank());
    }

    @Test
    void rejectsDocumentsWithoutSearchableText() {
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(
                new ByteArrayInputStream("  \n\t".getBytes(StandardCharsets.UTF_8)),
                "empty.txt",
                "text/plain"
            )
        );
    }

    @Test
    void rejectsRarByFileNameOrDeclaredMimeType() {
        var content = "searchable text".getBytes(StandardCharsets.UTF_8);

        var fileNameError = assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(new ByteArrayInputStream(content), "archive.RAR", "text/plain")
        );
        var mimeTypeError = assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(
                new ByteArrayInputStream(content),
                "archive.bin",
                "Application/X-Rar-Compressed; charset=binary"
            )
        );

        assertEquals("一期不支持 RAR 文档", fileNameError.getMessage());
        assertEquals("一期不支持 RAR 文档", mimeTypeError.getMessage());
    }

    @Test
    void rejectsDisguisedRar4AndRar5ByMagicBytes() {
        byte[] rar4 = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x33};
        byte[] rar5 = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x33};

        var rar4Error = assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(new ByteArrayInputStream(rar4), "renamed.txt", "text/plain")
        );
        var rar5Error = assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(new ByteArrayInputStream(rar5), "renamed.pdf", "application/pdf")
        );

        assertEquals("一期不支持 RAR 文档", rar4Error.getMessage());
        assertEquals("一期不支持 RAR 文档", rar5Error.getMessage());
    }
}
