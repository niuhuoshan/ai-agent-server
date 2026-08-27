package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService.ConversationExport;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformConversationControllerTest {

    private final ConversationApplicationService conversations = mock(ConversationApplicationService.class);
    private final ConversationTurnApplicationService turns = mock(ConversationTurnApplicationService.class);
    private final ConversationAttachmentService attachments = mock(ConversationAttachmentService.class);
    private final ConversationGovernanceService governance = mock(ConversationGovernanceService.class);
    private final ConversationExportService exports = mock(ConversationExportService.class);
    private final PlatformConversationController controller = new PlatformConversationController(
        conversations, turns, attachments, governance, exports
    );

    @Test
    void delegatesConversationGovernanceEndpointsWithoutChangingRequests() {
        ConversationFeedbackRequest feedback = new ConversationFeedbackRequest(
            11L, 12L, "down", "incorrect", "wrong result", "trace-1"
        );
        ConversationResourceScopeRequest scope = new ConversationResourceScopeRequest(
            3, Map.of("tool_ids", List.of(21L), "dataset_ids", List.of(31L))
        );

        controller.delete(7L);
        controller.feedback(7L, feedback);
        controller.resourceScope(7L);
        controller.updateResourceScope(7L, scope);

        verify(governance).deleteConversation(7L);
        verify(governance).saveFeedback(7L, feedback);
        verify(governance).resourceScope(7L);
        verify(governance).updateResourceScope(7L, scope);
    }

    @Test
    void returnsConversationExportAsUtf8Attachment() {
        byte[] content = "# exported".getBytes(StandardCharsets.UTF_8);
        when(exports.export(7L, "markdown")).thenReturn(new ConversationExport(
            "conversation-7.md", "text/markdown;charset=UTF-8", content
        ));

        var response = controller.export(7L, "markdown");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(
            MediaType.parseMediaType("text/markdown;charset=UTF-8")
        );
        assertThat(response.getHeaders().getContentLength()).isEqualTo(content.length);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("attachment", "conversation-7.md");
        assertThat(response.getBody()).isEqualTo(content);
        verify(exports).export(7L, "markdown");
    }
}
