package group.aitools.nhs.platform.automation;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class AutomationPostgresIntegrationTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sessions;
    private ExecutorService executor;

    @BeforeAll
    static void initialize() {
        dataSource = new UnpooledDataSource(
            "org.postgresql.Driver",
            System.getenv("NHS_TEST_JDBC_URL"),
            environmentOrDefault("NHS_TEST_DB_USER", "agent_server"),
            environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server")
        );
        Environment environment = new Environment(
            "automation-postgres", new JdbcTransactionFactory(), dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AutomationMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clean() throws SQLException {
        executor = Executors.newFixedThreadPool(2);
        execute("""
            TRUNCATE TABLE agent_webhook_nonce, agent_job_queue,
                agent_automation_fire, agent_automation_trigger,
                iam_service_account_grant
            """);
    }

    @AfterEach
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentCronClaimsExposeDueTriggerToOnlyOneDispatcher() throws Exception {
        insertTrigger();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        LocalDateTime now = utcNow();
        Future<Integer> first = executor.submit(() -> {
            try (SqlSession session = sessions.openSession(false)) {
                List<?> claimed = session.getMapper(AutomationMapper.class).lockDueTriggers(now, 1);
                firstClaimed.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                session.commit();
                return claimed.size();
            }
        });
        Future<Integer> second = executor.submit(() -> {
            assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));
            try (SqlSession session = sessions.openSession(false)) {
                int count = session.getMapper(AutomationMapper.class).lockDueTriggers(now, 1).size();
                session.commit();
                releaseFirst.countDown();
                return count;
            }
        });

        assertEquals(1, first.get(10, TimeUnit.SECONDS));
        assertEquals(0, second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentDuplicateFireCreatesOneFactAndOneWinner() throws Exception {
        insertTrigger();
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> insertFire(100L, start));
        Future<Integer> second = executor.submit(() -> insertFire(101L, start));
        start.countDown();

        int winners = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);

        assertEquals(1, winners);
        assertEquals(1, scalar("SELECT count(*) FROM agent_automation_fire"));
    }

    @Test
    void queueClaimUsesLeaseTokenAndRejectsStaleCompletion() throws Exception {
        insertTrigger();
        try (SqlSession session = sessions.openSession(true)) {
            AutomationMapper mapper = session.getMapper(AutomationMapper.class);
            assertEquals(1, mapper.insertFire(fire(100L)));
            assertEquals(1, mapper.insertFireJob(
                200L, 100L, "automation-fire:100", "{\"input\":\"report\"}", 3, utcNow()
            ));
            assertEquals(1, mapper.bindFireJob(100L, 200L));
        }
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<AutomationJobRow> first = executor.submit(() -> {
            try (SqlSession session = sessions.openSession(false)) {
                AutomationJobRow job = session.getMapper(AutomationMapper.class).claimJob(
                    "worker-a", "lease-a", utcNow(), utcNow().plusMinutes(2)
                );
                firstClaimed.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                session.commit();
                return job;
            }
        });
        Future<AutomationJobRow> second = executor.submit(() -> {
            assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));
            try (SqlSession session = sessions.openSession(false)) {
                AutomationJobRow job = session.getMapper(AutomationMapper.class).claimJob(
                    "worker-b", "lease-b", utcNow(), utcNow().plusMinutes(2)
                );
                session.commit();
                releaseFirst.countDown();
                return job;
            }
        });

        AutomationJobRow claimed = first.get(10, TimeUnit.SECONDS);
        assertNotNull(claimed);
        assertNull(second.get(10, TimeUnit.SECONDS));
        execute("""
            UPDATE agent_job_queue
            SET worker_id = 'worker-b', lease_token = 'lease-b',
                lease_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes'
            WHERE id = 200
            """);
        try (SqlSession session = sessions.openSession(true)) {
            int stale = session.getMapper(AutomationMapper.class).completeJob(
                200L, "worker-a", "lease-a", utcNow()
            );
            assertEquals(0, stale);
        }
    }

    private int insertFire(Long id, CountDownLatch start) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try (SqlSession session = sessions.openSession(false)) {
            int inserted = session.getMapper(AutomationMapper.class).insertFire(fire(id));
            session.commit();
            return inserted;
        }
    }

    private AutomationFire fire(Long id) {
        AutomationFire fire = new AutomationFire();
        fire.setId(id);
        fire.setTriggerId(1L);
        fire.setTriggerRevisionNo(1L);
        fire.setServiceAccountId(20L);
        fire.setSourceType("cron");
        fire.setFireKey("cron:same-key");
        fire.setPayloadHash("a".repeat(64));
        fire.setPayloadJson("{\"input\":\"report\"}");
        fire.setScheduledAt(utcNow());
        fire.setStatus("queued");
        fire.setAcceptedAt(utcNow());
        return fire;
    }

    private void insertTrigger() throws SQLException {
        execute("""
            INSERT INTO agent_automation_trigger (
                id, trigger_key, name, trigger_type, task_id, task_version_id,
                task_revision_no, service_account_id, cron_expr, timezone,
                status, next_run_at, revision_no, misfire_policy,
                max_catchup_count, max_attempts, config_json, del_flag
            ) VALUES (
                1, 'cron-test', 'Cron test', 'cron', 10, 11, 1, 20,
                '* * * * * *', 'UTC', 'active',
                (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '1 minute',
                1, 'fire_once', 1, 3, '{}'::jsonb, '0'
            )
            """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
