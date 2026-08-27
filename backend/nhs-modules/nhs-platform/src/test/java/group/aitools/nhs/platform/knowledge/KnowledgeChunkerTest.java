package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.service.KnowledgeChunker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void createsDeterministicOverlappingChunksAtSentenceBoundaries() {
        String content = "Alpha procedure. Beta procedure. Gamma procedure.";

        var first = chunker.split(content, 24, 5);
        var second = chunker.split(content, 24, 5);

        assertEquals(first, second);
        assertTrue(first.size() >= 2);
        assertEquals(1, first.getFirst().number());
        assertEquals(64, first.getFirst().contentHash().length());
        assertTrue(first.get(1).startOffset() < first.getFirst().endOffset());
    }

    @Test
    void rejectsInvalidOverlapAndBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> chunker.split("text", 10, 10));
        assertThrows(IllegalArgumentException.class, () -> chunker.split(" ", 10, 1));
    }
}
