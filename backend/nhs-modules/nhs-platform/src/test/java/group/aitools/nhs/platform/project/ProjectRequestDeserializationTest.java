package group.aitools.nhs.platform.project;

import group.aitools.nhs.platform.project.web.CreateProjectRequest;
import group.aitools.nhs.platform.project.web.UpdateProjectRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ProjectRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientsCannotInjectLifecycleOwnershipOrPersistenceFields() {
        String create = """
            {
              "idempotencyKey":"create-1",
              "name":"Project",
              "status":"archived",
              "ownerId":999,
              "extraJson":{"creationRequestHash":"forged"}
            }
            """;
        String update = """
            {
              "name":"Project",
              "status":"archived",
              "ownerId":999,
              "extraJson":{"creationRequestHash":"forged"}
            }
            """;

        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(create, CreateProjectRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(update, UpdateProjectRequest.class));
    }
}
