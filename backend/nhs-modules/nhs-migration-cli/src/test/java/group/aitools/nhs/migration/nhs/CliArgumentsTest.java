package group.aitools.nhs.migration.nhs;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class CliArgumentsTest {

    @Test
    void verifyDefaultsToVerifyRunTypeAndReadsSecretsOnlyFromEnvironment() {
        Map<String, String> environment = environment();
        CliArguments arguments = CliArguments.parse(new String[]{
            "verify", "--run-id=42", "--run-key=verify-42"
        }, environment);

        assertEquals("verify", arguments.migrationType());
        assertEquals(42L, arguments.runId());
        assertEquals("source-secret", arguments.sourcePassword(environment));
        assertEquals("target-secret", arguments.targetPassword(environment));
    }

    @Test
    void commandLinePasswordsAreRejectedBeforeAnyConnection() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            CliArguments.parse(new String[]{"inventory", "--source-password=leak"}, environment())
        );

        assertTrue(exception.getMessage().contains("only from environment"));
    }

    @Test
    void missingTargetIsAllowedOnlyForInventory() {
        Map<String, String> environment = environment();
        environment.remove("NHS_TARGET_JDBC_URL");
        environment.remove("NHS_TARGET_DB_USER");

        CliArguments inventory = CliArguments.parse(new String[]{"inventory"}, environment);
        assertEquals("inventory", inventory.command());
        assertThrows(IllegalArgumentException.class, () ->
            CliArguments.parse(new String[]{"migrate"}, environment)
        );
    }

    private Map<String, String> environment() {
        Map<String, String> result = new HashMap<>();
        result.put("NHS_SOURCE_JDBC_URL", "jdbc:postgresql://source/nhs");
        result.put("NHS_SOURCE_DB_USER", "source");
        result.put("NHS_SOURCE_DB_PASSWORD", "source-secret");
        result.put("NHS_TARGET_JDBC_URL", "jdbc:postgresql://target/agent");
        result.put("NHS_TARGET_DB_USER", "target");
        result.put("NHS_TARGET_DB_PASSWORD", "target-secret");
        return result;
    }
}
