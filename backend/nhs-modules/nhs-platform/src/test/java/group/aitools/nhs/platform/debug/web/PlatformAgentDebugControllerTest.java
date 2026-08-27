package group.aitools.nhs.platform.debug.web;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class PlatformAgentDebugControllerTest {

    @Test
    void lastEventIdAdvancesButNeverRewindsPersistedReplay() {
        assertEquals(12L, PlatformAgentDebugController.resumeCursor(12L, "9"));
        assertEquals(13L, PlatformAgentDebugController.resumeCursor(12L, " 13 "));
        assertEquals(12L, PlatformAgentDebugController.resumeCursor(12L, null));
    }

    @Test
    void rejectsMalformedOrNegativeReplayCursor() {
        assertThrows(
            ServiceException.class,
            () -> PlatformAgentDebugController.resumeCursor(0L, "bad")
        );
        assertThrows(
            ServiceException.class,
            () -> PlatformAgentDebugController.resumeCursor(0L, "-1")
        );
    }
}
