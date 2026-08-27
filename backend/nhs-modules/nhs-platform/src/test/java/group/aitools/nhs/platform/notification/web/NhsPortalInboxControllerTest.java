package group.aitools.nhs.platform.notification.web;

import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsPortalInboxControllerTest {

    private final NotificationApplicationService service = mock(NotificationApplicationService.class);
    private final NhsPortalInboxController controller = new NhsPortalInboxController(
        service, JsonMapper.builder().build()
    );

    @Test
    void deleteOneDelegatesTheExactNotificationId() {
        controller.deleteOne(900L);

        verify(service).deleteOne(900L);
    }

    @Test
    void deleteReadReturnsTheActualDeletedCount() {
        when(service.deleteRead()).thenReturn(3);

        var response = controller.deleteRead();

        assertEquals(3, response.getData().get("deleted"));
        verify(service).deleteRead();
    }
}
