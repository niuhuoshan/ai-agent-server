package group.aitools.nhs.platform.sandbox;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class SandboxPostgresIntegrationTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void initialize() {
        dataSource = new UnpooledDataSource(
            "org.postgresql.Driver",
            System.getenv("NHS_TEST_JDBC_URL"),
            environmentOrDefault("NHS_TEST_DB_USER", "agent_server"),
            environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server")
        );
        Environment environment = new Environment(
            "sandbox-postgres", new JdbcTransactionFactory(), dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SandboxRunnerMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clean() throws SQLException {
        execute("TRUNCATE TABLE agent_sandbox_job_output, agent_sandbox_nonce, agent_sandbox_job, agent_sandbox_runner");
    }

    @Test
    void nonceAndJobTokensCannotBeReplayedOrUsedByAnotherRunner() {
        LocalDateTime now = utcNow();
        try (SqlSession session = sessions.openSession(true)) {
            SandboxRunnerMapper mapper = session.getMapper(SandboxRunnerMapper.class);
            register(mapper, 1L, "runner-a", now);
            register(mapper, 2L, "runner-b", now);
            assertEquals(1, mapper.insertNonce(
                10L, 1L, ContentHashing.sha256("nonce-a"), now, now.plusMinutes(2), now
            ));
            assertEquals(0, mapper.insertNonce(
                11L, 1L, ContentHashing.sha256("nonce-a"), now, now.plusMinutes(2), now
            ));
            insertJob(mapper, 100L, now);
            String tokenHash = ContentHashing.sha256("job-token-a");
            SandboxJobRow claimed = mapper.claimJob(
                1L, tokenHash, now, now.plusMinutes(2)
            );
            assertNotNull(claimed);
            assertEquals(1, mapper.startJob(100L, 1L, tokenHash, now));
            assertEquals(0, mapper.completeJob(
                100L, 2L, tokenHash, "succeeded", 0, "bad", "", "[]", "{}",
                null, null, now
            ));
            assertEquals(1, mapper.completeJob(
                100L, 1L, tokenHash, "succeeded", 0, "ok", "", "[]", "{}",
                null, null, now
            ));
            assertEquals(0, mapper.completeJob(
                100L, 1L, tokenHash, "succeeded", 0, "replay", "", "[]", "{}",
                null, null, now
            ));
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverButDisabledRunnerCannotClaim() throws SQLException {
        LocalDateTime now = utcNow();
        try (SqlSession session = sessions.openSession(true)) {
            SandboxRunnerMapper mapper = session.getMapper(SandboxRunnerMapper.class);
            register(mapper, 1L, "runner-a", now);
            register(mapper, 2L, "runner-b", now);
            insertJob(mapper, 100L, now);
            SandboxJobRow first = mapper.claimJob(
                1L, ContentHashing.sha256("token-a"), now, now.minusSeconds(1)
            );
            assertNotNull(first);
            SandboxJobRow takeover = mapper.claimJob(
                2L, ContentHashing.sha256("token-b"), now, now.plusMinutes(2)
            );
            assertNotNull(takeover);
            assertEquals(2L, takeover.getAssignedRunnerId());
            insertJob(mapper, 101L, now);
        }
        execute("UPDATE agent_sandbox_runner SET status = 'disabled' WHERE id = 1");
        try (SqlSession session = sessions.openSession(true)) {
            SandboxJobRow disabledClaim = session.getMapper(SandboxRunnerMapper.class).claimJob(
                1L, ContentHashing.sha256("token-c"), now, now.plusMinutes(2)
            );
            assertNull(disabledClaim);
        }
    }

    @Test
    void chatCodeOutputIsOrderedIdempotentAndOwnerCancellationRevokesLease() {
        LocalDateTime now = utcNow();
        try (SqlSession session = sessions.openSession(true)) {
            SandboxRunnerMapper mapper = session.getMapper(SandboxRunnerMapper.class);
            assertEquals(1, mapper.upsertRunner(
                1L, "runner-a", "Runner", ContentHashing.sha256("runner-secret"),
                "[\"code\"]", 2, "test", now
            ));
            assertEquals(1, mapper.heartbeat(
                1L, "[\"code\"]", 2, 0, "test", now, now.plusMinutes(5)
            ));
            assertEquals(1, mapper.insertChatCodeJob(
                200L, 101L, 301L, ContentHashing.sha256("chat-trace"),
                ContentHashing.sha256("chat-request"), "code", "python", "print('ok')",
                "[\"__chat_code__\"]", ".", 60, 512, 1000, 128, 102400, 10, now
            ));
            String tokenHash = ContentHashing.sha256("chat-token");
            SandboxJobRow claimed = mapper.claimJob(
                1L, tokenHash, now, now.plusMinutes(2)
            );
            assertNotNull(claimed);
            assertEquals("chat_code", claimed.getSourceType());
            assertEquals(101L, claimed.getOwnerUserId());
            assertEquals(1, mapper.startJob(200L, 1L, tokenHash, now));
            Long sequence = mapper.reserveOutputSequence(
                200L, 1L, tokenHash, 0L, 5, now
            );
            assertEquals(1L, sequence);
            assertEquals(1, mapper.insertOutput(
                300L, 200L, 1, sequence, 0L, "stdout", "hello", 5, now
            ));
            assertNull(mapper.reserveOutputSequence(
                200L, 1L, tokenHash, 0L, 5, now
            ));
            assertEquals(1, mapper.selectOutputs(200L, 0L, 10).size());
            assertEquals(1, mapper.selectOwnedChatJobs(101L, 301L, 50).size());
            assertEquals(0, mapper.selectOwnedChatJobs(999L, 301L, 50).size());
            assertEquals(0, mapper.cancelOwnedChatJob(200L, 999L, 301L, now));
            assertEquals(1, mapper.cancelOwnedChatJob(200L, 101L, 301L, now));
            assertEquals(0, mapper.renewJob(
                200L, 1L, tokenHash, now, now.plusMinutes(2)
            ));
        }
    }

    private void register(
        SandboxRunnerMapper mapper,
        Long id,
        String key,
        LocalDateTime now
    ) {
        assertEquals(1, mapper.upsertRunner(
            id, key, "Runner", ContentHashing.sha256("runner-secret-" + id),
            "[\"python-3.11\"]", 2, "test", now
        ));
        assertEquals(1, mapper.heartbeat(
            id, "[\"python-3.11\"]", 2, 0, "test", now, now.plusMinutes(5)
        ));
    }

    private void insertJob(SandboxRunnerMapper mapper, Long id, LocalDateTime now) {
        assertEquals(1, mapper.insertJob(
            id, 10L, 20L, 30L, 40L, null, null, null,
            ContentHashing.sha256("trace-" + id), ContentHashing.sha256("request-" + id),
            "python-3.11", "[\"python\",\"-V\"]", ".", "read_write", "none", "[]",
            300, 512, 1000, 128, 1048576, 0, now
        ));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
