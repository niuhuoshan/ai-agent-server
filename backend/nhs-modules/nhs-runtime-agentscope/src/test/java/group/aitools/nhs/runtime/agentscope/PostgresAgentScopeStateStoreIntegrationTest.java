package group.aitools.nhs.runtime.agentscope;

import io.agentscope.core.state.State;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class PostgresAgentScopeStateStoreIntegrationTest {

    private static final String USER = "runtime-test-user";
    private static final String OTHER_USER = "runtime-other-user";
    private static final String SESSION = "runtime-state-session";

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("NHS_TEST_JDBC_URL"));
        postgres.setUser(environmentOrDefault("NHS_TEST_DB_USER", "agent_server"));
        postgres.setPassword(environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server"));
        dataSource = postgres;
        deleteState(USER);
        deleteState(OTHER_USER);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteState(USER);
        deleteState(OTHER_USER);
    }

    @Test
    void persistsRestoresIsolatesAndDeletesAgentState() {
        PostgresAgentStateStore first = new PostgresAgentStateStore(
            dataSource, "agentscope", "agentscope_sessions", false
        );
        first.save(USER, SESSION, "checkpoint", new TestState("waiting-approval", 1));
        first.save(USER, SESSION, "events", List.of(
            new TestState("first", 1),
            new TestState("second", 2)
        ));

        PostgresAgentStateStore afterRestart = new PostgresAgentStateStore(
            dataSource, "agentscope", "agentscope_sessions", false
        );

        assertEquals(
            new TestState("waiting-approval", 1),
            afterRestart.get(USER, SESSION, "checkpoint", TestState.class).orElseThrow()
        );
        assertEquals(
            List.of(new TestState("first", 1), new TestState("second", 2)),
            afterRestart.getList(USER, SESSION, "events", TestState.class)
        );
        assertTrue(afterRestart.exists(USER, SESSION));
        assertFalse(afterRestart.exists(OTHER_USER, SESSION));
        assertTrue(afterRestart.listSessionIds(USER).contains(SESSION));
        assertFalse(afterRestart.listSessionIds(OTHER_USER).contains(SESSION));

        afterRestart.delete(USER, SESSION);
        assertFalse(afterRestart.exists(USER, SESSION));
    }

    private void deleteState(String user) throws Exception {
        String sessionId = user + ":" + SESSION;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM agentscope.agentscope_sessions WHERE session_id = ?"
             )) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record TestState(String status, int sequence) implements State {
    }
}
