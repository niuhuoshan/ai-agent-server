package group.aitools.nhs.platform.search;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.search.domain.SearchInvocation;
import group.aitools.nhs.platform.search.domain.SearchProviderState;
import group.aitools.nhs.platform.search.mapper.SearchProviderMapper;
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
class SearchProviderMapperIntegrationTest {

    private static final long CONNECTOR_ID = 9_600_001L;
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
            "web-search-test", new JdbcTransactionFactory(), source
        ));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SearchProviderMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void resetFacts() throws Exception {
        try (SqlSession session = sessions.openSession();
             Connection connection = session.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM agent_search_invocation WHERE connector_id = " + CONNECTOR_ID);
            statement.execute("DELETE FROM agent_search_provider_state WHERE connector_id = " + CONNECTOR_ID);
            statement.execute("DELETE FROM agent_connector WHERE id = " + CONNECTOR_ID);
            statement.execute("""
                INSERT INTO agent_connector (
                    id, connector_key, name, provider_type, scope_type, owner_id,
                    endpoint_url, credential_ref, config_json, status, revision_no,
                    create_by, create_time, del_flag, extra_json
                ) VALUES (
                    9600001, 'search-it', 'Search integration', 'search', 'global', NULL,
                    'https://search.example/api', 'env:SEARCH_KEY',
                    '{"engine":"custom"}'::jsonb, 'active', 1,
                    1, CURRENT_TIMESTAMP, '0', '{}'::jsonb
                )
                """);
            connection.commit();
        }
    }

    @Test
    void persistsRateAuditAndMovesCircuitThroughOpenHalfOpenAndClosed() {
        LocalDateTime now = LocalDateTime.now();
        try (SqlSession session = sessions.openSession()) {
            SearchProviderMapper mapper = session.getMapper(SearchProviderMapper.class);
            assertEquals(1, mapper.selectVisibleActiveProviders(99L).size());
            assertEquals(1, mapper.markFailure(
                CONNECTOR_ID, 2, 100, "first", now, now.plusSeconds(60)
            ));
            SearchProviderState first = mapper.selectState(CONNECTOR_ID);
            assertEquals("closed", first.getCircuitState());
            assertEquals(1, first.getConsecutiveFailures());

            assertEquals(1, mapper.markFailure(
                CONNECTOR_ID, 2, 120, "second", now.plusSeconds(1), now.plusSeconds(61)
            ));
            SearchProviderState opened = mapper.selectState(CONNECTOR_ID);
            assertEquals("open", opened.getCircuitState());
            assertEquals(0, mapper.acquireHalfOpenProbe(CONNECTOR_ID, now.plusSeconds(30)));
            assertEquals(1, mapper.acquireHalfOpenProbe(CONNECTOR_ID, now.plusSeconds(62)));
            assertEquals("half_open", mapper.selectState(CONNECTOR_ID).getCircuitState());

            assertEquals(1, mapper.markSuccess(CONNECTOR_ID, 80, now.plusSeconds(63)));
            SearchProviderState recovered = mapper.selectState(CONNECTOR_ID);
            assertEquals("closed", recovered.getCircuitState());
            assertEquals(0, recovered.getConsecutiveFailures());
            assertEquals(3L, recovered.getTotalRequests());
            assertEquals(2L, recovered.getTotalFailures());

            SearchInvocation invocation = new SearchInvocation();
            invocation.setId(9_700_001L);
            invocation.setConnectorId(CONNECTOR_ID);
            invocation.setActorId(9L);
            invocation.setQuerySha256("a".repeat(64));
            invocation.setResultCount(2);
            invocation.setStatus("succeeded");
            invocation.setLatencyMs(80);
            invocation.setOccurredAt(now.plusSeconds(63));
            assertEquals(1, mapper.insertInvocation(invocation));
            assertEquals(1, mapper.countRecentInvocations(CONNECTOR_ID, now));
            session.commit();
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
