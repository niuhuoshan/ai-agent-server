package group.aitools.nhs.platform.notification;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.audit.mapper.AgentAuditQueryMapper;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.mapper.AgentNotificationMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class AuditNotificationPostgresIntegrationTest {

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
            "audit-notification", new JdbcTransactionFactory(), dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentNotificationMapper.class);
        configuration.addMapper(AgentAuditQueryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clean() throws SQLException {
        execute("TRUNCATE TABLE agent_notification, agent_audit_event");
    }

    @Test
    void notificationEventKeyIsIdempotentPerHumanRecipient() {
        try (SqlSession session = sessions.openSession(true)) {
            AgentNotificationMapper mapper = session.getMapper(AgentNotificationMapper.class);
            assertEquals(1, mapper.insertNotification(notification(100L, 10L, "run:900")));
            assertEquals(0, mapper.insertNotification(notification(101L, 10L, "run:900")));
            assertEquals(1, mapper.insertNotification(notification(102L, 11L, "run:900")));
            assertEquals(1, mapper.selectInbox(10L, "run", true, null, 20).size());
            assertEquals(1, mapper.selectInbox(11L, "run", true, null, 20).size());
        }
    }

    @Test
    void markingReadCannotCrossUserBoundary() {
        try (SqlSession session = sessions.openSession(true)) {
            AgentNotificationMapper mapper = session.getMapper(AgentNotificationMapper.class);
            mapper.insertNotification(notification(200L, 20L, "approval:1"));
            assertEquals(0, mapper.markRead(200L, 21L, LocalDateTime.now()));
            assertEquals(1, mapper.countUnread(20L));
            assertEquals(1, mapper.markRead(200L, 20L, LocalDateTime.now()));
            assertEquals(0, mapper.countUnread(20L));
            assertNull(mapper.selectOwned(200L, 21L));
            assertNotNull(mapper.selectOwned(200L, 20L));
        }
    }

    @Test
    void auditSearchFiltersIdentifiersWithoutSelectingMetadataPayload() throws SQLException {
        execute("""
            INSERT INTO agent_audit_event (
                id, actor_type, actor_id, action, resource_type, resource_id,
                task_id, run_id, decision, metadata_json, created_at
            ) VALUES
                (300, 'service_account', 42, 'invoke', 'tool', 7, 8, 9,
                 'success', '{"secret":"must-not-leave-database"}'::jsonb, CURRENT_TIMESTAMP),
                (301, 'user', 42, 'view', 'task', 8, 8, NULL,
                 'allow', '{}'::jsonb, CURRENT_TIMESTAMP)
            """);
        try (SqlSession session = sessions.openSession(true)) {
            var result = session.getMapper(AgentAuditQueryMapper.class).search(
                "service_account", 42L, "invoke", "tool", 7L, 8L, 9L,
                "success", LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(1), null, 20
            );
            assertEquals(1, result.size());
            assertEquals(300L, result.getFirst().getId());
            List<String> fields = java.util.Arrays.stream(result.getFirst().getClass().getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
            assertEquals(false, fields.contains("metadataJson"));
        }
    }

    private AgentNotification notification(Long id, Long userId, String eventKey) {
        AgentNotification value = new AgentNotification();
        value.setId(id);
        value.setUserId(userId);
        value.setEventKey(eventKey);
        value.setCategory(eventKey.startsWith("run:") ? "run" : "approval");
        value.setLevel("info");
        value.setTitle("通知");
        value.setCreatedAt(LocalDateTime.now());
        return value;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
