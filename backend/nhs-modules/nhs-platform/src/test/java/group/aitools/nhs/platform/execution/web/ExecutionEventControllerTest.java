package group.aitools.nhs.platform.execution.web;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ExecutionEventControllerTest {

    @Test
    void lastEventIdCannotMoveReplayCursorBackwards() {
        assertEquals(20L, ExecutionEventController.resumeCursor(20L, "19"));
        assertEquals(21L, ExecutionEventController.resumeCursor(20L, " 21 "));
        assertEquals(20L, ExecutionEventController.resumeCursor(20L, null));
    }

    @Test
    void malformedOrNegativeLastEventIdIsRejected() {
        assertThrows(
            ServiceException.class,
            () -> ExecutionEventController.resumeCursor(0L, "not-a-number")
        );
        assertThrows(
            ServiceException.class,
            () -> ExecutionEventController.resumeCursor(0L, "-1")
        );
    }
}
