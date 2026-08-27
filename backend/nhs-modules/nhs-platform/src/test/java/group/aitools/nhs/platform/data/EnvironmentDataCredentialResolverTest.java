package group.aitools.nhs.platform.data.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class EnvironmentDataCredentialResolverTest {

    @Test
    void resolvesOnlyStrictEnvironmentJsonReferences() {
        EnvironmentDataCredentialResolver resolver = new EnvironmentDataCredentialResolver(
            Map.of("REPORTING_DB", "{\"username\":\"reader\",\"password\":\"secret-value\"}"),
            JsonMapper.builder().build()
        );

        DataCredential credential = resolver.resolve("env:REPORTING_DB");

        assertEquals("reader", credential.username());
        assertEquals("secret-value", credential.password());
    }

    @Test
    void rejectsRawMissingAndExtendedCredentialDocumentsWithoutEchoingSecrets() {
        EnvironmentDataCredentialResolver resolver = new EnvironmentDataCredentialResolver(
            Map.of(
                "BAD_DB", "{\"username\":\"reader\",\"password\":\"secret-value\",\"token\":\"x\"}",
                "BROKEN_DB", "not-json"
            ),
            JsonMapper.builder().build()
        );

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("reader:secret-value"));
        IllegalStateException extended = assertThrows(
            IllegalStateException.class, () -> resolver.resolve("env:BAD_DB")
        );
        assertTrue(!extended.getMessage().contains("secret-value"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("env:BROKEN_DB"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("env:MISSING_DB"));
    }
}
