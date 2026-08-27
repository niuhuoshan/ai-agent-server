package group.aitools.nhs.platform.openapi;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.openapi.mapper.MachineApiMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
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
class MachineIngressPostgresIntegrationTest {

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
            "machine-ingress", new JdbcTransactionFactory(), dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MachineApiMapper.class);
        configuration.addMapper(EmbedChatMapper.class);
        configuration.addMapper(AgentConversationMapper.class);
        configuration.addMapper(TaskControlMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clean() throws SQLException {
        execute("""
            TRUNCATE TABLE agent_embed_turn, agent_embed_session,
                agent_conversation_message, agent_execution_event,
                agent_conversation, agent_api_call, agent_api_rate_bucket
            """);
        execute("DELETE FROM agent_task WHERE id IN (99002601, 99002602)");
    }

    @Test
    void fixedWindowRateCounterRejectsRequestBeyondLimit() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime window = now.withSecond(0).withNano(0);
        try (SqlSession session = sessions.openSession(true)) {
            MachineApiMapper mapper = session.getMapper(MachineApiMapper.class);
            assertEquals(1, mapper.consumeRate(10L, window, 2, now));
            assertEquals(2, mapper.consumeRate(10L, window, 2, now));
            assertNull(mapper.consumeRate(10L, window, 2, now));
        }
    }

    @Test
    void humanConversationLookupCannotReadCollidingServiceAccountConversation() {
        AgentConversation machine = new AgentConversation();
        machine.setId(100L);
        machine.setUserId(20L);
        machine.setAgentVersionId(40L);
        machine.setPrincipalType("service_account");
        machine.setTitle("Embed");
        machine.setSessionKey("embed-session");
        machine.setCreateBy(20L);
        machine.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        try (SqlSession session = sessions.openSession(true)) {
            assertEquals(1, session.getMapper(EmbedChatMapper.class).insertConversation(machine));
            assertNull(session.getMapper(AgentConversationMapper.class)
                .selectOwnedConversation(100L, 20L));
        }
    }

    @Test
    void apiAuditSchemaContainsNoCredentialOrRequestBodyColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                 SELECT string_agg(column_name, ',')
                 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'agent_api_call'
                 """)) {
            result.next();
            String columns = result.getString(1);
            assertNotNull(columns);
            assertEquals(false, columns.contains("secret"));
            assertEquals(false, columns.contains("body"));
            assertEquals(false, columns.contains("payload"));
        }
    }

    @Test
    void typedTaskOwnerCannotCrossHumanAndServiceAccountIdentity() throws SQLException {
        execute("""
            INSERT INTO agent_task (
                id, task_key, title, objective, owner_id, owner_principal_type, create_by
            ) VALUES
                (99002601, 'typed-machine-owner', 'Machine task', 'test', 42, 'service_account', 42),
                (99002602, 'typed-human-owner', 'Human task', 'test', 42, 'human', 42)
            """);
        try (SqlSession session = sessions.openSession(true)) {
            TaskControlMapper mapper = session.getMapper(TaskControlMapper.class);
            assertEquals(java.util.List.of("OWNER"), mapper.selectRelations(99002601L, 42L, "service_account"));
            assertEquals(java.util.List.of(), mapper.selectRelations(99002601L, 42L, "human"));
            assertEquals(java.util.List.of("OWNER"), mapper.selectRelations(99002602L, 42L, "human"));
            assertEquals(java.util.List.of(), mapper.selectRelations(99002602L, 42L, "service_account"));
        }
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
