package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.service.KnowledgeBaseConfig;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class KnowledgeBaseConfigTest {

    @Test
    void appliesBoundedDefaultsWithoutImplicitEmbedding() {
        KnowledgeBaseConfig config = KnowledgeBaseConfig.from(Map.of());

        assertEquals(1000, config.chunkSize());
        assertEquals(100, config.chunkOverlap());
        assertEquals(6, config.topK());
        assertEquals(0.2, config.similarityThreshold());
        assertEquals(0.7, config.vectorWeight());
        assertNull(config.embeddingModelId());
    }

    @Test
    void rejectsUnknownOrInconsistentEmbeddingConfiguration() {
        assertThrows(
            ServiceException.class,
            () -> KnowledgeBaseConfig.from(Map.of("unknown", true))
        );
        assertThrows(
            ServiceException.class,
            () -> KnowledgeBaseConfig.from(Map.of("embeddingModelId", 10L))
        );
        assertThrows(
            ServiceException.class,
            () -> KnowledgeBaseConfig.from(Map.of("chunkSize", 200, "chunkOverlap", 200))
        );
        assertThrows(
            ServiceException.class,
            () -> KnowledgeBaseConfig.from(Map.of("similarityThreshold", Double.NaN))
        );
    }

    @Test
    void preservesSnowflakeEmbeddingModelIdAsStringAtTheBrowserBoundary() {
        KnowledgeBaseConfig config = KnowledgeBaseConfig.from(Map.of(
            "embeddingModelId", "9007199254740993",
            "embeddingDimension", 1536
        ));

        assertEquals(9007199254740993L, config.embeddingModelId());
        assertEquals("9007199254740993", config.toMap().get("embeddingModelId"));
    }
}
