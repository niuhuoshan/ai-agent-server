package group.aitools.nhs.platform.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class FlywayPlatformMigrationIntegrationTest {

    private static final String EXPECTED_SCHEMA_VERSION = "89";

    @Test
    void takesOverExistingSchemaAndSecondStartupHasNoPendingMigration() {
        Flyway flyway = Flyway.configure()
            .dataSource(
                System.getenv("NHS_TEST_JDBC_URL"),
                environmentOrDefault("NHS_TEST_DB_USER", "agent_server"),
                environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server")
            )
            .locations("classpath:db/migration/agent")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .executeInTransaction(false)
            .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertTrue(first.success);
        assertTrue(first.migrationsExecuted >= 0);
        assertTrue(second.success);
        assertEquals(0, second.migrationsExecuted);
        assertEquals(EXPECTED_SCHEMA_VERSION, flyway.info().current().getVersion().getVersion());
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
