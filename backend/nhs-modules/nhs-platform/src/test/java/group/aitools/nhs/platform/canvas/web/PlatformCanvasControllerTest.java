package group.aitools.nhs.platform.canvas.web;

import group.aitools.nhs.platform.canvas.service.ConversationCanvasService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("dev")
class PlatformCanvasControllerTest {

    private final ConversationCanvasService service = mock(ConversationCanvasService.class);
    private final PlatformCanvasController controller = new PlatformCanvasController(service);

    @Test
    void delegatesTheCompleteCanvasLifecycleWithoutRewritingVersionTokens() {
        CreateCanvasRequest create = new CreateCanvasRequest("draft", "markdown", "body", Map.of());
        UpdateCanvasRequest update = new UpdateCanvasRequest(1, "draft", "markdown", "next", Map.of());
        RestoreCanvasVersionRequest restore = new RestoreCanvasVersionRequest(2);
        SaveCanvasToWorkspaceRequest save = new SaveCanvasToWorkspaceRequest("draft.md", false, 2);

        controller.list(7L, 100);
        controller.create(7L, create);
        controller.get(7L, 501L);
        controller.update(7L, 501L, update);
        controller.versions(7L, 501L, 100);
        controller.restore(7L, 501L, 1, restore);
        controller.saveToWorkspace(7L, 501L, save);
        controller.delete(7L, 501L, 2);

        verify(service).list(7L, 100);
        verify(service).create(7L, create);
        verify(service).get(7L, 501L);
        verify(service).update(7L, 501L, update);
        verify(service).versions(7L, 501L, 100);
        verify(service).restore(7L, 501L, 1, restore);
        verify(service).saveToWorkspace(7L, 501L, save);
        verify(service).delete(7L, 501L, 2);
    }
}
