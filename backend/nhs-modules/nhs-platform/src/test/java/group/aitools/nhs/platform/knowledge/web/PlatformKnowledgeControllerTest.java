package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeMetricsService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeRetrievalService;
import group.aitools.nhs.platform.provider.ExternalKnowledgeProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformKnowledgeControllerTest {

    private final KnowledgeApplicationService application = mock(KnowledgeApplicationService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final KnowledgeMetricsService metrics = mock(KnowledgeMetricsService.class);
    private final ExternalKnowledgeProviderRegistry providers = new ExternalKnowledgeProviderRegistry(List.of());
    private final PlatformKnowledgeController controller = new PlatformKnowledgeController(
        application, retrieval, metrics, providers
    );

    @Test
    void delegatesChunkPaginationWithoutChangingTheRequest() {
        when(application.chunks(10L, 20L, 40, 25)).thenReturn(List.of());

        controller.chunks(10L, 20L, 40, 25);

        verify(application).chunks(10L, 20L, 40, 25);
    }

    @Test
    void returnsOriginalDocumentAsUtf8NamedBinaryResponse() {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(20L);
        document.setKnowledgeBaseId(10L);
        document.setName("制度说明.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(3L);
        var input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        when(application.download(10L, 20L)).thenReturn(
            new KnowledgeApplicationService.DocumentDownload(document, input)
        );

        var response = controller.download(10L, 20L, false);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("attachment", "filename");
        assertThat(response.getBody()).isInstanceOf(InputStreamResource.class);
        verify(application).download(10L, 20L);
    }

    @Test
    void streamsMigratedDocumentsWithoutARecordedContentLength() {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(21L);
        document.setKnowledgeBaseId(10L);
        document.setName("legacy.txt");
        document.setMimeType("text/plain");
        when(application.download(10L, 21L)).thenReturn(
            new KnowledgeApplicationService.DocumentDownload(
                document, new ByteArrayInputStream("legacy".getBytes(StandardCharsets.UTF_8))
            )
        );

        var response = controller.download(10L, 21L, false);

        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1L);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isInstanceOf(InputStreamResource.class);
    }

    @Test
    void delegatesKnowledgeTreeAndPartialDocumentCatalogUpdates() {
        UpdateKnowledgeDocumentRequest request = new UpdateKnowledgeDocumentRequest();
        request.setExpectedRevision(2L);
        request.setDirectoryId(null);
        when(application.tree(10L)).thenReturn(new KnowledgeTreeView(List.of(), List.of()));
        when(application.updateDocument(10L, 20L, request)).thenReturn(null);

        controller.tree(10L);
        controller.patchDocument(10L, 20L, request);

        verify(application).tree(10L);
        verify(application).updateDocument(10L, 20L, request);
    }

    @Test
    void delegatesDirectoryLifecycleWithExpectedRevision() {
        UpdateKnowledgeDirectoryRequest request = new UpdateKnowledgeDirectoryRequest();
        request.setExpectedRevision(4L);
        request.setName("Policies");
        when(application.updateDirectory(10L, 30L, request)).thenReturn(null);

        controller.patchDirectory(10L, 30L, request);
        controller.deleteDirectory(10L, 30L, 4L);

        verify(application).updateDirectory(10L, 30L, request);
        verify(application).deleteDirectory(10L, 30L, 4L);
    }
}
