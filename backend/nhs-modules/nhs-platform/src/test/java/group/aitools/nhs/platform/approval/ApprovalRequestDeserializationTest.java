package group.aitools.nhs.platform.approval;

import group.aitools.nhs.platform.approval.web.ApprovalDecisionRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ApprovalRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientCannotInjectToolActionsOrReplyIdentity() {
        assertThrows(
            RuntimeException.class,
            () -> jsonMapper.readValue(
                """
                    {"idempotencyKey":"decision-1","comment":"approve",
                     "replyId":"forged","pendingActions":[{"id":"forged","name":"delete"}]}
                    """,
                ApprovalDecisionRequest.class
            )
        );
    }
}
