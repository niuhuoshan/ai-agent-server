package group.aitools.nhs.platform.workflow;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowTemplateRow;
import group.aitools.nhs.platform.workflow.service.WorkflowGraphValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class WorkflowPostgresIntegrationTest {

    private static final long RUN_ID = System.currentTimeMillis() * 1000L + 301L;
    private static final long STEP_ID = RUN_ID + 1;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(url(), user(), password())
            .locations("classpath:db/migration/agent")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .validateOnMigrate(true)
            .executeInTransaction(false)
            .load()
            .migrate();
    }

    @AfterEach
    void finalizeFacts() throws SQLException {
        try (Connection connection = connection()) {
            try (PreparedStatement step = connection.prepareStatement("""
                UPDATE agent_run_step
                SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND status IN ('pending', 'running', 'waiting')
                """)) {
                step.setLong(1, RUN_ID);
                step.executeUpdate();
            }
            try (PreparedStatement run = connection.prepareStatement("""
                UPDATE agent_task_run
                SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('queued', 'preparing', 'running',
                                             'waiting_approval', 'waiting_input', 'blocked', 'paused')
                """)) {
                run.setLong(1, RUN_ID);
                run.executeUpdate();
            }
        }
    }

    @Test
    void seedsOnlyTwoFixedTemplatesAndPublishedContentCannotMutate() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT count(*), count(DISTINCT d.workflow_key)
                 FROM agent_workflow_definition d
                 JOIN agent_workflow_version v ON v.workflow_id = d.id
                 WHERE d.workflow_key IN ('supervisor_executor', 'delivery_team')
                   AND v.status = 'published'
                 """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
            assertEquals(2, result.getInt(2));
        }

        WorkflowGraphValidator validator = new WorkflowGraphValidator(JsonMapper.builder().build());
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT d.id, d.workflow_key, d.name, d.workflow_type, d.status,
                        v.id, v.version_no, v.graph_json::text,
                        v.runtime_policy_json::text, v.content_hash, v.status, v.published_at
                 FROM agent_workflow_definition d
                 JOIN agent_workflow_version v ON v.workflow_id = d.id
                 WHERE d.workflow_key IN ('supervisor_executor', 'delivery_team')
                 ORDER BY d.workflow_key
                 """);
             ResultSet result = statement.executeQuery()) {
            int validated = 0;
            while (result.next()) {
                WorkflowTemplateRow row = new WorkflowTemplateRow();
                row.setWorkflowId(result.getLong(1));
                row.setWorkflowKey(result.getString(2));
                row.setName(result.getString(3));
                row.setWorkflowType(result.getString(4));
                row.setWorkflowStatus(result.getString(5));
                row.setVersionId(result.getLong(6));
                row.setVersionNo(result.getInt(7));
                row.setGraphJson(result.getString(8));
                row.setRuntimePolicyJson(result.getString(9));
                row.setContentHash(result.getString(10));
                row.setVersionStatus(result.getString(11));
                row.setPublishedAt(result.getTimestamp(12).toLocalDateTime());
                validator.validate(row);
                validated++;
            }
            assertEquals(2, validated);
        }

        try (Connection connection = connection()) {
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE agent_workflow_version
                    SET graph_json = jsonb_set(graph_json, '{maxParallelism}', '99'::jsonb)
                    WHERE id = 900000000000029102
                    """)) {
                    statement.executeUpdate();
                }
            });
        }
    }

    @Test
    void concurrentReadyStepCanBeClaimedOnlyOnce() throws Exception {
        insertPendingStep();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> claims = List.of(
                executor.submit(() -> claim(ready, start)),
                executor.submit(() -> claim(ready, start))
            );
            ready.await();
            start.countDown();
            int total = claims.get(0).get() + claims.get(1).get();
            assertEquals(1, total);
        }
    }

    private void insertPendingStep() throws SQLException {
        try (Connection connection = connection()) {
            try (PreparedStatement run = connection.prepareStatement("""
                INSERT INTO agent_task_run (
                    id, task_id, task_version_id, workflow_version_id, trace_id,
                    status, attempt_no, authorization_snapshot_json, runtime_snapshot_json,
                    budget_snapshot_json, usage_json, created_by
                ) VALUES (?, ?, ?, 900000000000029102, ?, 'running', 1,
                          '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, 1)
                """)) {
                run.setLong(1, RUN_ID);
                run.setLong(2, RUN_ID);
                run.setLong(3, RUN_ID);
                run.setString(4, "workflow-pg-" + RUN_ID);
                run.executeUpdate();
            }
            try (PreparedStatement step = connection.prepareStatement("""
                INSERT INTO agent_run_step (
                    id, run_id, step_key, step_type, role_key, sequence_no, status,
                    agent_version_id, depends_on_json, runtime_template_json, created_at
                ) VALUES (?, ?, 'backend', 'agent', 'backend', 1, 'pending', 1,
                          '[]'::jsonb, '{}'::jsonb, CURRENT_TIMESTAMP)
                """)) {
                step.setLong(1, STEP_ID);
                step.setLong(2, RUN_ID);
                step.executeUpdate();
            }
        }
    }

    private int claim(CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            start.await();
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_run_step
                SET status = 'running', runtime_snapshot_json = '{}'::jsonb
                WHERE id = ? AND run_id = ? AND status = 'pending'
                """)) {
                statement.setLong(1, STEP_ID);
                statement.setLong(2, RUN_ID);
                updated = statement.executeUpdate();
            }
            connection.commit();
            return updated;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    private static String url() {
        return System.getenv("NHS_TEST_JDBC_URL");
    }

    private static String user() {
        return environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
    }

    private static String password() {
        return environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
