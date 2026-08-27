package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.identity.web.CreateServiceAccountRequest;
import group.aitools.nhs.platform.identity.web.IssueApiCredentialRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class MachineIdentityRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientsCannotInjectIdentityRolesOrCredentialStorageFields() {
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "accountKey":"worker",
              "name":"Worker",
              "status":"active",
              "principalType":"human",
              "roles":["platform_admin"]
            }
            """, CreateServiceAccountRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "serviceAccountId":1,
              "scopes":["tasks:read"],
              "secret":"chosen-secret",
              "secretHash":"forged",
              "keyPrefix":"forged"
            }
            """, IssueApiCredentialRequest.class));
    }
}
