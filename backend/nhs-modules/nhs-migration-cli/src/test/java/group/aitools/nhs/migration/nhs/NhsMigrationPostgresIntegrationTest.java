package group.aitools.nhs.migration.nhs;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
@TestMethodOrder(OrderAnnotation.class)
class NhsMigrationPostgresIntegrationTest {

    private static final String SOURCE_USER = environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
    private static final String SOURCE_PASSWORD = environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
    private static final String BASE_URL = System.getenv("NHS_TEST_JDBC_URL");
    private static final String SUFFIX = Long.toUnsignedString(System.nanoTime(), 36);
    private static final String SOURCE_SCHEMA = "nhs_test_" + SUFFIX;
    private static final String TARGET_SCHEMA = "agent_test_" + SUFFIX;
    private static final String SOURCE_URL = withSchema(BASE_URL, SOURCE_SCHEMA);
    private static final String TARGET_URL = withSchema(BASE_URL, TARGET_SCHEMA);
    private static Path reportDirectory;
    private static long fullRunId;

    @BeforeAll
    static void setUp() throws Exception {
        reportDirectory = Files.createTempDirectory("nhs-migration-test-");
        try (Connection connection = connection(BASE_URL)) {
            execute(connection, "CREATE SCHEMA " + quote(SOURCE_SCHEMA));
            execute(connection, "CREATE SCHEMA " + quote(TARGET_SCHEMA));
        }
        Flyway.configure()
            .dataSource(TARGET_URL, SOURCE_USER, SOURCE_PASSWORD)
            .locations("classpath:db/migration/agent")
            .defaultSchema(TARGET_SCHEMA)
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .executeInTransaction(false)
            .load()
            .migrate();
        try (Connection target = connection(TARGET_URL)) {
            execute(target, resource("nhs-target-prerequisites.sql"));
        }
        try (Connection source = connection(SOURCE_URL)) {
            execute(source, resource("migration/nhs/fixtures/nhs-postgres-fixture.sql"));
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (Connection connection = connection(BASE_URL)) {
            execute(connection, "DROP SCHEMA IF EXISTS " + quote(SOURCE_SCHEMA) + " CASCADE");
            execute(connection, "DROP SCHEMA IF EXISTS " + quote(TARGET_SCHEMA) + " CASCADE");
        }
        if (reportDirectory != null) {
            try (Stream<Path> paths = Files.walk(reportDirectory)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Temporary test evidence is best-effort cleanup.
                    }
                });
            }
        }
    }

    @Test
    @Order(1)
    void fullMigrationIsIdempotentAndSecretsNeverLeaveTheSource() throws Exception {
        String fullRunKey = "full-" + SUFFIX;
        int fullStatus = run("migrate", fullRunKey, "--migration-type=full");
        assertEquals(0, fullStatus, report(fullRunKey));
        fullRunId = runId("full-" + SUFFIX);
        assertTrue(fullRunId > 0);
        assertTrue(scalar("SELECT COUNT(*) FROM agent_migration_mapping WHERE migration_run_id=?", fullRunId) > 0);
        assertEquals(0, scalar("SELECT COUNT(*) FROM agent_migration_mapping WHERE migration_run_id=? AND status='mapped' AND target_hash !~ '^[0-9a-f]{64}$'", fullRunId));
        assertEquals(0, scalar("SELECT COUNT(*) FROM agent_model WHERE model_key LIKE 'nhs.model.%' AND credential_ref IS NOT NULL"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM agent_data_source WHERE credential_ref IS NULL OR credential_ref NOT LIKE 'env:%'"));
        assertEquals("postgresql://analytics.example.invalid:5432",
            scalarText("SELECT endpoint_url FROM agent_data_source WHERE source_key='nhs.datasource.301'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys_user WHERE user_name LIKE 'legacy_%' AND status <> '1'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM agent_service_account WHERE account_key='nhs-migration-automation' AND status <> 'disabled'"));
        assertSecretsAbsent();

        assertEquals(0, run("verify", "verify-full-" + SUFFIX, "--run-id=" + fullRunId, "--migration-type=verify"));
        String noopRunKey = "noop-" + SUFFIX;
        assertEquals(0, run("migrate", noopRunKey, "--migration-type=incremental"));
        long noopRunId = runId(noopRunKey);
        assertEquals(0, scalar("SELECT COALESCE(SUM(source_count), 0) FROM agent_migration_entity_stat WHERE migration_run_id=? AND phase IN ('load','archive')", noopRunId), report(noopRunKey));
        assertEquals(0, run("verify", "verify-noop-" + SUFFIX, "--run-id=" + noopRunId, "--migration-type=verify"));
    }

    @Test
    @Order(2)
    void memoryImportAcceptsSignedExportAndRejectsTampering() throws Exception {
        Path valid = resourcePath("migration/nhs/fixtures/nhs-memory-export.jsonl");
        assertEquals(0, run("memory-import", "memory-" + SUFFIX,
            "--input=" + valid, "--migration-type=incremental"));
        long memoryRunId = runId("memory-" + SUFFIX);
        assertEquals(3, scalar("SELECT COUNT(*) FROM agent_migration_mapping WHERE migration_run_id=? AND status='mapped'", memoryRunId));
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_conversation WHERE session_key LIKE 'nhs-redis:%' AND visibility='private' AND status='archived'"));
        assertTrue(scalar("SELECT COUNT(*) FROM agent_memory WHERE review_status='pending' AND sensitive_level='sensitive'") >= 2);

        Path tampered = resourcePath("migration/nhs/fixtures/nhs-memory-export-tampered.jsonl");
        assertEquals(3, run("memory-import", "memory-tampered-" + SUFFIX,
            "--input=" + tampered, "--migration-type=incremental"));
        long tamperedRunId = runId("memory-tampered-" + SUFFIX);
        assertEquals(1, scalar("SELECT COUNT(*) FROM agent_migration_issue WHERE migration_run_id=? AND issue_code='MEMORY_EXPORT_HASH_MISMATCH'", tamperedRunId));
    }

    @Test
    @Order(3)
    void changedSourceRowsFailClosedWithoutOverwritingTarget() throws Exception {
        String originalName = scalarText("SELECT display_name FROM " + quote("agent_model") + " WHERE model_key='nhs.model.model-chat'");
        assertNotNull(originalName);
        updateSource("UPDATE " + quote("ai_models") + " SET name='Tampered source model' WHERE id='model-chat'");
        try {
            assertEquals(3, run("migrate", "drift-" + SUFFIX, "--migration-type=incremental"));
            long driftRunId = runId("drift-" + SUFFIX);
            assertTrue(scalar("SELECT COUNT(*) FROM agent_migration_issue WHERE migration_run_id=? AND issue_code='ROW_MIGRATION_FAILED'", driftRunId) > 0);
            assertEquals(originalName, scalarText("SELECT display_name FROM " + quote("agent_model") + " WHERE model_key='nhs.model.model-chat'"));
        } finally {
            updateSource("UPDATE " + quote("ai_models") + " SET name='Nhs Chat' WHERE id='model-chat'");
        }
    }

    @Test
    @Order(4)
    void verificationDetectsTargetRowTampering() throws Exception {
        updateTarget("UPDATE " + quote("agent_definition") + " SET description='operator tampered this row' WHERE agent_key='nhs.agent.agent-dev'");
        try {
            assertEquals(3, run("verify", "verify-tampered-" + SUFFIX, "--run-id=" + fullRunId, "--migration-type=verify"));
            long verificationRunId = runId("verify-tampered-" + SUFFIX);
            assertTrue(scalar("SELECT COUNT(*) FROM agent_migration_issue WHERE migration_run_id=? AND issue_code='MAPPED_TARGET_CHANGED'", verificationRunId) > 0);
        } finally {
            updateTarget("UPDATE " + quote("agent_definition") + " SET description='Implements development tasks' WHERE agent_key='nhs.agent.agent-dev'");
        }
    }

    private static int run(String command, String runKey, String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = command;
        args[1] = "--source-schema=" + SOURCE_SCHEMA;
        args[2] = "--run-key=" + runKey;
        System.arraycopy(extra, 0, args, 3, extra.length);
        return NhsMigrationCli.run(args, environment());
    }

    private static Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("NHS_SOURCE_JDBC_URL", SOURCE_URL);
        environment.put("NHS_SOURCE_DB_USER", SOURCE_USER);
        environment.put("NHS_SOURCE_DB_PASSWORD", SOURCE_PASSWORD);
        environment.put("NHS_SOURCE_DB_SCHEMA", SOURCE_SCHEMA);
        environment.put("NHS_TARGET_JDBC_URL", TARGET_URL);
        environment.put("NHS_TARGET_DB_USER", SOURCE_USER);
        environment.put("NHS_TARGET_DB_PASSWORD", SOURCE_PASSWORD);
        environment.put("NHS_MIGRATION_REPORT_DIR", reportDirectory.toString());
        environment.put("NHS_MIGRATION_OPERATOR_ID", "1");
        return environment;
    }

    private static long runId(String runKey) throws SQLException {
        return scalar("SELECT id FROM agent_migration_run WHERE run_key=?", runKey);
    }

    private static void assertSecretsAbsent() throws IOException, SQLException {
        try (Stream<Path> paths = Files.walk(reportDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path);
                assertFalse(content.contains("DO-NOT-COPY"), path.toString());
                assertFalse(content.contains("old-secret"), path.toString());
                assertFalse(content.contains("nested-"), path.toString());
                assertFalse(content.contains("legacy-db-password"), path.toString());
                assertFalse(content.contains("request-token"), path.toString());
            }
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM agent_legacy_execution_archive WHERE payload_json::text ~ 'DO-NOT-COPY|old-secret|nested-|legacy-db-password|request-token'"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = NhsMigrationPostgresIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path resourcePath(String name) throws Exception {
        var resource = NhsMigrationPostgresIntegrationTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("missing test resource " + name);
        }
        return Path.of(resource.toURI());
    }

    private static String report(String runKey) throws IOException {
        Path markdown = reportDirectory.resolve(runKey + ".md");
        return Files.exists(markdown) ? Files.readString(markdown) : "migration report was not written";
    }

    private static Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, SOURCE_USER, SOURCE_PASSWORD);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(TARGET_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String scalarText(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(TARGET_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void updateSource(String sql) throws SQLException {
        try (Connection connection = connection(SOURCE_URL)) {
            execute(connection, sql);
        }
    }

    private static void updateTarget(String sql) throws SQLException {
        try (Connection connection = connection(TARGET_URL)) {
            execute(connection, sql);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static String withSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    }

    private static String quote(String identifier) {
        if (!identifier.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe test identifier");
        }
        return "\"" + identifier + "\"";
    }

    private static String environmentOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
