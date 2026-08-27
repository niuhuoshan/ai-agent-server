package group.aitools.nhs.platform.task;

import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.UpdateTaskRequest;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class TaskRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientCannotInjectOwnerStatusCurrentVersionOrPersistenceMetadata() {
        String create = """
            {
              "idempotencyKey":"create-1",
              "title":"Task",
              "objective":"Objective",
              "agentVersionId":88,
              "ownerId":999,
              "status":"completed",
              "currentVersionId":777,
              "extraJson":{"creationRequestHash":"forged"}
            }
            """;
        String update = """
            {
              "title":"Task",
              "objective":"Objective",
              "agentVersionId":88,
              "ownerId":999,
              "status":"completed",
              "currentVersionId":777
            }
            """;

        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(create, CreateTaskRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(update, UpdateTaskRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "idempotencyKey":"convert-1",
              "title":"Task",
              "objective":"Objective",
              "agentVersionId":88,
              "taskId":999,
              "currentVersionId":777
            }
            """, ConvertConversationToTaskRequest.class));
    }
}
