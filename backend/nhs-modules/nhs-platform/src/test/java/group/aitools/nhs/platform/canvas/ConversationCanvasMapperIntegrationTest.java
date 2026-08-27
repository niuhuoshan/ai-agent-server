package group.aitools.nhs.platform.canvas;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvas;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvasVersion;
import group.aitools.nhs.platform.canvas.mapper.ConversationCanvasMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class ConversationCanvasMapperIntegrationTest {

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
            "conversation-canvas-test", new JdbcTransactionFactory(), source
        ));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ConversationCanvasMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clearCanvasFacts() throws Exception {
        try (SqlSession session = sessions.openSession();
             Connection connection = session.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE agent_conversation_canvas_version, agent_conversation_canvas");
            connection.commit();
        }
    }

    @Test
    void persistsOwnerBoundVersionsRejectsStaleWritesAndKeepsHistoryImmutable() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        try (SqlSession session = sessions.openSession()) {
            ConversationCanvasMapper mapper = session.getMapper(ConversationCanvasMapper.class);
            assertEquals(1, mapper.insertCanvas(canvas(now)));
            assertEquals(1, mapper.insertVersion(version(601L, 1, "first", "created", now)));
            session.commit();

            assertEquals("first", mapper.selectOwnedCanvas(7L, 501L, 101L).getContent());
            assertNull(mapper.selectOwnedCanvas(7L, 501L, 999L));
            assertEquals(1, mapper.advanceVersion(
                7L, 501L, 101L, 1, 2, "report", "markdown", "{}",
                6L, "b".repeat(64), now.plusSeconds(1)
            ));
            assertEquals(1, mapper.insertVersion(version(
                602L, 2, "second", "updated", now.plusSeconds(1)
            )));
            assertEquals(0, mapper.advanceVersion(
                7L, 501L, 101L, 1, 2, "stale", "markdown", "{}",
                5L, "c".repeat(64), now.plusSeconds(2)
            ));
            session.commit();

            assertEquals(2, mapper.selectOwnedCanvas(7L, 501L, 101L).getCurrentVersionNo());
            assertEquals("second", mapper.selectOwnedCanvas(7L, 501L, 101L).getContent());
            assertEquals(2, mapper.selectOwnedVersions(7L, 501L, 101L, 20).size());
        }

        try (SqlSession session = sessions.openSession();
             Statement statement = session.getConnection().createStatement()) {
            SQLException immutable = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE agent_conversation_canvas_version SET content='mutated' WHERE id=601"
            ));
            assertEquals("P0001", immutable.getSQLState());
        }

        try (SqlSession session = sessions.openSession()) {
            ConversationCanvasMapper mapper = session.getMapper(ConversationCanvasMapper.class);
            assertEquals(1, mapper.softDelete(7L, 501L, 101L, 2));
            session.commit();
            assertNull(mapper.selectOwnedCanvas(7L, 501L, 101L));
        }
    }

    private AgentConversationCanvas canvas(LocalDateTime now) {
        AgentConversationCanvas value = new AgentConversationCanvas();
        value.setId(501L);
        value.setConversationId(7L);
        value.setOwnerId(101L);
        value.setTitle("report");
        value.setCanvasType("markdown");
        value.setCurrentVersionNo(1);
        value.setRevisionNo(1);
        value.setMetadataJson("{}");
        value.setContentSize(5L);
        value.setContentSha256("a".repeat(64));
        value.setCreateBy(101L);
        value.setCreateTime(now);
        value.setUpdateBy(101L);
        value.setUpdateTime(now);
        value.setDelFlag("0");
        return value;
    }

    private AgentConversationCanvasVersion version(
        Long id,
        int version,
        String content,
        String changeType,
        LocalDateTime now
    ) {
        AgentConversationCanvasVersion value = new AgentConversationCanvasVersion();
        value.setId(id);
        value.setCanvasId(501L);
        value.setVersionNo(version);
        value.setTitle("report");
        value.setCanvasType("markdown");
        value.setContent(content);
        value.setMetadataJson("{}");
        value.setContentSize((long) content.length());
        value.setContentSha256(version == 1 ? "a".repeat(64) : "b".repeat(64));
        value.setChangeType(changeType);
        value.setCreatedBy(101L);
        value.setCreatedAt(now);
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
