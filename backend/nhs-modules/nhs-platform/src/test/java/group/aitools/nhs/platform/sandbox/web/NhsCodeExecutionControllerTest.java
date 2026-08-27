package group.aitools.nhs.platform.sandbox.web;

import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionSseService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsCodeExecutionControllerTest {

    @Test
    void unavailableRunnerUsesRealHttp503AndStableMachineCode() {
        NhsCodeExecutionController controller = new NhsCodeExecutionController(
            mock(ChatCodeExecutionService.class), mock(ChatCodeExecutionSseService.class)
        );

        var response = controller.serviceError(new ServiceException(
            "sandbox_unavailable: 当前没有可用的代码执行Runner", 503
        ));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("sandbox_unavailable", response.getBody().get("code"));
        assertEquals("当前没有可用的代码执行Runner", response.getBody().get("message"));
    }

    @Test
    void lastEventIdCannotMoveCursorBackwardsOrBeNegative() {
        assertEquals(9L, NhsCodeExecutionController.resumeCursor(9L, "7"));
        assertEquals(12L, NhsCodeExecutionController.resumeCursor(9L, "12"));
        assertThrows(
            ServiceException.class,
            () -> NhsCodeExecutionController.resumeCursor(0L, "-1")
        );
    }

    @Test
    void conversationDiscoveryDelegatesToOwnerScopedService() {
        ChatCodeExecutionService service = mock(ChatCodeExecutionService.class);
        NhsCodeExecutionController controller = new NhsCodeExecutionController(
            service, mock(ChatCodeExecutionSseService.class)
        );
        when(service.list("7", 25)).thenReturn(List.of());

        var response = controller.list("7", 25);

        assertEquals(0, response.getData().size());
        verify(service).list("7", 25);
    }

    @Test
    void invalidNewExecutionResumeCursorIsRejectedBeforeSubmission() {
        ChatCodeExecutionService service = mock(ChatCodeExecutionService.class);
        NhsCodeExecutionController controller = new NhsCodeExecutionController(
            service, mock(ChatCodeExecutionSseService.class)
        );

        assertThrows(ServiceException.class, () -> controller.execute(
            new ChatCodeExecutionRequest("python", "print(1)", "7", null), "2"
        ));

        verify(service, never()).submit(org.mockito.ArgumentMatchers.any());
    }
}
