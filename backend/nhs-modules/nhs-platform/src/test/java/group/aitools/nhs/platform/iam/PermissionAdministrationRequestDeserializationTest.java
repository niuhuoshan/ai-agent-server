package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.management.web.CopyPermissionRequest;
import group.aitools.nhs.platform.iam.management.web.CreatePermissionProfileRequest;
import group.aitools.nhs.platform.iam.management.web.PutPermissionBindingRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class PermissionAdministrationRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientsCannotInjectProfileStatusBindingUserOrCopyTarget() {
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "profileKey":"developer",
              "name":"Developer",
              "status":"published",
              "createdBy":999,
              "entries":[{"resourceType":"tool","resourceId":1,"action":"invoke","effect":"allow"}]
            }
            """, CreatePermissionProfileRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "bindingType":"profile",
              "profileId":1,
              "profileVersion":1,
              "userId":999,
              "status":"active"
            }
            """, PutPermissionBindingRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "idempotencyKey":"copy-1",
              "sourceUserId":1,
              "copyMode":"copy_base",
              "targetUserId":999
            }
            """, CopyPermissionRequest.class));
    }
}
