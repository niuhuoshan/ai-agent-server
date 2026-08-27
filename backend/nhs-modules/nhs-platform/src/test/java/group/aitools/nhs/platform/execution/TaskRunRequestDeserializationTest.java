package group.aitools.nhs.platform.execution;

import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class TaskRunRequestDeserializationTest {

    @Test
    void unknownSecretFieldsAreRejectedInsteadOfSilentlyIgnored() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThrows(RuntimeException.class, () -> mapper.readValue("""
            {"idempotencyKey":"request-1","input":"work","apiKey":"raw-secret"}
            """, CreateTaskRunRequest.class));
    }
}
