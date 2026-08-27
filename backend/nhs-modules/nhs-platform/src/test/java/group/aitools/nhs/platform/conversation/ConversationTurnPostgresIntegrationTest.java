package group.aitools.nhs.platform.conversation;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class ConversationTurnPostgresIntegrationTest {

    private static final long CONVERSATION_ID = 99003401L;
    private static DataSource dataSource;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ConversationTurnMapper mapper;

    @BeforeAll
    static void initialize() {
        String url = System.getenv("NHS_TEST_JDBC_URL");
        String user = environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
        String password = environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration/agent")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .executeInTransaction(false)
            .load()
            .migrate();
        dataSource = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
        Environment environment = new Environment(
            "conversation-turn-postgres", new JdbcTransactionFactory(), dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ConversationTurnMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws SQLException {
        clean();
        execute("""
            INSERT INTO agent_conversation (
                id, user_id, principal_type, title, visibility, status,
                session_key, create_by, del_flag
            ) VALUES (
                99003401, 101, 'human', 'Postgres turn test', 'private', 'active',
                'conversation-turn-postgres', 101, '0'
            )
            """);
        session = sessions.openSession(true);
        mapper = session.getMapper(ConversationTurnMapper.class);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (session != null) {
            session.close();
        }
        clean();
    }

    @Test
    void persistsOwnerScopedTurnAndAttachmentAndEnforcesOneActiveTurn() {
        assertNotNull(mapper.selectOwnedActiveConversation(CONVERSATION_ID, 101L));
        assertNull(mapper.selectOwnedActiveConversation(CONVERSATION_ID, 202L));

        AgentConversationTurn first = turn(99003411L, "a", "b", "c");
        assertEquals(1, mapper.insertTurn(first));
        assertEquals(first.getId(), mapper.selectTurnByKey(CONVERSATION_ID, first.getIdempotencyHash()).getId());
        assertEquals(0, mapper.insertTurn(turn(99003412L, "d", "e", "f")));

        AgentConversationAttachment attachment = new AgentConversationAttachment();
        attachment.setId(99003421L);
        attachment.setConversationId(CONVERSATION_ID);
        attachment.setUserId(101L);
        attachment.setOriginalName("facts.txt");
        attachment.setStorageType("local");
        attachment.setStorageRef("postgres-integration/facts.txt");
        attachment.setMimeType("text/plain");
        attachment.setSizeBytes(5L);
        attachment.setSha256("9".repeat(64));
        attachment.setStatus("ready");
        attachment.setCreatedAt(LocalDateTime.now());
        assertEquals(1, mapper.insertAttachment(attachment));
        assertEquals(1, mapper.bindAttachment(
            attachment.getId(), CONVERSATION_ID, 101L, first.getId()
        ));
        AgentConversationAttachment stored = mapper.selectOwnedAttachment(
            CONVERSATION_ID, attachment.getId(), 101L
        );
        assertEquals("bound", stored.getStatus());
        assertEquals(first.getId(), stored.getTurnId());
        assertNull(mapper.selectOwnedAttachment(CONVERSATION_ID, attachment.getId(), 202L));
    }

    private AgentConversationTurn turn(Long id, String key, String request, String trace) {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(id);
        turn.setConversationId(CONVERSATION_ID);
        turn.setUserId(101L);
        turn.setIdempotencyHash(key.repeat(64));
        turn.setRequestHash(request.repeat(64));
        turn.setTraceId(trace.repeat(64));
        turn.setAgentId(301L);
        turn.setAgentVersionId(401L);
        turn.setStatus("running");
        turn.setRuntimeSnapshotJson("{}");
        turn.setStartedAt(LocalDateTime.now());
        return turn;
    }

    private void clean() throws SQLException {
        execute("DELETE FROM agent_conversation_attachment WHERE conversation_id = " + CONVERSATION_ID);
        execute("DELETE FROM agent_conversation_turn WHERE conversation_id = " + CONVERSATION_ID);
        execute("DELETE FROM agent_conversation WHERE id = " + CONVERSATION_ID);
    }

    private static void execute(String sql) throws SQLException {
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
