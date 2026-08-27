package group.aitools.nhs.platform.operations.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class LogStorageMaintenancePostgresIntegrationTest {

    private static final long OLD_AUDIT_ID = 9_990_001L;
    private static final long FRESH_AUDIT_ID = 9_990_002L;
    private static final long OLD_EVENT_ID = 9_990_003L;
    private static final long FRESH_EVENT_ID = 9_990_004L;

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrateAndConnect() {
        String url = System.getenv("NHS_TEST_JDBC_URL");
        String user = environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
        String password = environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration/agent")
            .baselineOnMigrate(true).load().migrate();
        DataSource dataSource = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void inspectsRealPgCatalogAndMicroBatchDeletesOnlyExpiredRows() {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusDays(30);
            insertAudit(OLD_AUDIT_ID, now.minusDays(60));
            insertAudit(FRESH_AUDIT_ID, now.minusDays(2));
            insertEvent(OLD_EVENT_ID, "maintenance-old", now.minusDays(60));
            insertEvent(FRESH_EVENT_ID, "maintenance-fresh", now.minusDays(2));
            LogStorageMaintenanceRepository repository = new LogStorageMaintenanceRepository(jdbc);

            var snapshot = repository.inspect(cutoff);

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.tables()).hasSize(2).allSatisfy(table -> {
                assertThat(table.partitioned()).isFalse();
                assertThat(table.partitions()).hasSize(1);
                assertThat(table.expiredRows()).isGreaterThanOrEqualTo(1);
            });

            var outcome = repository.maintain(cutoff);

            assertThat(outcome).isNotNull();
            assertThat(outcome.deletedRows()).isGreaterThanOrEqualTo(2);
            assertThat(outcome.droppedPartitions()).isEmpty();
            assertThat(outcome.remainingExpiredRows()).isFalse();
            assertThat(count("agent_audit_event", OLD_AUDIT_ID)).isZero();
            assertThat(count("agent_audit_event", FRESH_AUDIT_ID)).isOne();
            assertThat(count("agent_execution_event", OLD_EVENT_ID)).isZero();
            assertThat(count("agent_execution_event", FRESH_EVENT_ID)).isOne();
            status.setRollbackOnly();
        });
    }

    private void insertAudit(long id, LocalDateTime createdAt) {
        jdbc.update("""
            INSERT INTO agent_audit_event (id, actor_type, action, decision, created_at)
            VALUES (?, 'system', 'maintenance_test', 'success', ?)
            """, id, Timestamp.valueOf(createdAt));
    }

    private void insertEvent(long id, String eventId, LocalDateTime createdAt) {
        jdbc.update("""
            INSERT INTO agent_execution_event (
                id, event_id, trace_id, cursor, event_type, occurred_at, created_at
            ) VALUES (?, ?, ?, ?, 'maintenance_test', ?, ?)
            """, id, eventId, "trace-" + eventId, id,
            Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    }

    private long count(String table, long id) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
        return value == null ? 0 : value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
