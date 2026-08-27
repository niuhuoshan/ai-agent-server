package group.aitools.nhs.platform.connector;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import group.aitools.nhs.platform.connector.mapper.McpRuntimeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class McpRuntimeMapperIntegrationTest {

    private static SqlSessionFactory sessions;

    @BeforeAll
    static void configureMyBatis() {
        var source = new UnpooledDataSource(
            "org.postgresql.Driver",
            System.getenv("NHS_TEST_JDBC_URL"),
            environmentOrDefault("NHS_TEST_DB_USER", "agent_server"),
            environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server")
        );
        Configuration configuration = new Configuration(new Environment(
            "mcp-runtime-test", new JdbcTransactionFactory(), source
        ));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(McpRuntimeMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clearFacts() throws Exception {
        try (SqlSession session = sessions.openSession();
             Connection connection = session.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE agent_mcp_usage_detail, agent_mcp_runtime_mount, agent_mcp_runtime_health");
            connection.commit();
        }
    }

    @Test
    void persistsAndProjectsHealthMountLifecycleAndUsage() {
        LocalDateTime now = LocalDateTime.now();
        try (SqlSession session = sessions.openSession()) {
            McpRuntimeMapper mapper = session.getMapper(McpRuntimeMapper.class);
            McpRuntimeHealth health = health(now);
            assertEquals(1, mapper.upsertHealth(health));

            McpRuntimeMount mount = mount(now);
            assertEquals(1, mapper.insertMount(mount));
            assertEquals(1, mapper.markMountMounted(mount.getId(), 0, now));

            McpUsageDetail usage = usage(now);
            assertEquals(1, mapper.insertUsage(usage));
            assertEquals(1, mapper.markMountUsed(mount.getId(), 0, now, null));
            session.commit();

            assertEquals("healthy", mapper.selectHealth(71L).getHealthStatus());
            assertEquals(1L, mapper.selectHealth(71L).getActiveMountCount());
            assertEquals(1, mapper.selectMounts(71L, 20).size());
            assertEquals(1L, mapper.selectMounts(71L, 20).getFirst().getInvocationCount());
            assertEquals("success", mapper.selectUsage(71L, 50).getFirst().getStatus());
        }
    }

    private McpRuntimeHealth health(LocalDateTime now) {
        McpRuntimeHealth value = new McpRuntimeHealth();
        value.setConnectorId(71L);
        value.setHealthStatus("healthy");
        value.setCircuitState("closed");
        value.setConsecutiveFailures(0);
        value.setTotalConnections(1L);
        value.setTotalReconnections(0L);
        value.setTotalInvocations(1L);
        value.setTotalSuccesses(1L);
        value.setTotalFailures(0L);
        value.setLastSuccessAt(now);
        value.setLastLatencyMs(25L);
        value.setUpdatedAt(now);
        value.setRevisionNo(1L);
        return value;
    }

    private McpRuntimeMount mount(LocalDateTime now) {
        McpRuntimeMount value = new McpRuntimeMount();
        value.setId(801L);
        value.setConnectorId(71L);
        value.setConnectorRevision(2L);
        value.setScopeType("session");
        value.setScopeKey("session:hash");
        value.setUserId(9L);
        value.setConversationId(44L);
        value.setSessionId("session-1");
        value.setExecutionId("execution-1");
        value.setTraceId("trace-1");
        value.setStatus("mounting");
        value.setConnectionAttempts(0);
        value.setReconnectCount(0);
        value.setInvocationCount(0L);
        value.setFailureCount(0L);
        value.setOpenedAt(now);
        return value;
    }

    private McpUsageDetail usage(LocalDateTime now) {
        McpUsageDetail value = new McpUsageDetail();
        value.setId(901L);
        value.setMountId(801L);
        value.setConnectorId(71L);
        value.setConnectorRevision(2L);
        value.setToolId(501L);
        value.setExternalToolName("search");
        value.setUserId(9L);
        value.setConversationId(44L);
        value.setSessionId("session-1");
        value.setExecutionId("execution-1");
        value.setTraceId("trace-1");
        value.setStatus("success");
        value.setAttemptCount(1);
        value.setLatencyMs(25L);
        value.setRequestBytes(16L);
        value.setResponseBytes(32L);
        value.setStartedAt(now);
        value.setCompletedAt(now);
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
