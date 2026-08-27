package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.web.KnowledgeDocumentView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class KnowledgeDocumentViewTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void preservesLegacyMetadataWhenTheNewCatalogArrayIsEmpty() {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(10L);
        document.setKnowledgeBaseId(20L);
        document.setName("legacy.txt");
        document.setMetadataJson("{\"tags\":[\"制度\",\"人事\"],\"remark\":\"历史备注\"}");
        document.setTagsJson("[]");

        KnowledgeDocumentView view = KnowledgeDocumentView.from(document, mapper);

        assertEquals(java.util.List.of("制度", "人事"), view.tags());
        assertEquals("历史备注", view.remark());
    }

    @Test
    void anExplicitlyClearedCatalogDoesNotResurrectOldMetadata() {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(11L);
        document.setKnowledgeBaseId(20L);
        document.setName("updated.txt");
        document.setMetadataJson("{\"tags\":[],\"remark\":\"\"}");
        document.setTagsJson("[]");

        KnowledgeDocumentView view = KnowledgeDocumentView.from(document, mapper);

        assertEquals(java.util.List.of(), view.tags());
        assertEquals(null, view.remark());
    }
}
