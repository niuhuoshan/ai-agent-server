package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReadOnlyJdbcConnectionFactoryTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
        "postgresql, postgresql://127.0.0.1, app, jdbc:postgresql://127.0.0.1:5432/app, readOnlyMode, always",
        "mysql, mysql://127.0.0.1, app, jdbc:mysql://127.0.0.1:3306/app, sslMode, DISABLED",
        "oracle, oracle://127.0.0.1, XEPDB1, jdbc:oracle:thin:@//127.0.0.1:1521/XEPDB1, oracle.net.CONNECT_TIMEOUT, 5000",
        "sqlserver, sqlserver://127.0.0.1, app, jdbc:sqlserver://127.0.0.1:1433, applicationIntent, ReadOnly",
        "clickhouse, clickhouse://127.0.0.1, app, jdbc:clickhouse:http://127.0.0.1:8123/app, clickhouse_setting_readonly, 1"
    })
    void buildsDriverSpecificReadOnlyConnectionPlans(
        String dbType,
        String endpoint,
        String database,
        String expectedUrl,
        String property,
        String expectedProperty
    ) {
        var plan = factory(getClass().getClassLoader()).connectionPlan(
            source(dbType, endpoint, database, "{\"sslMode\":\"disable\"}")
        );

        assertEquals(expectedUrl, plan.jdbcUrl());
        assertEquals("report_reader", plan.properties().getProperty("user"));
        assertEquals(expectedProperty, plan.properties().getProperty(property));
    }

    @Test
    void buildsTlsUrlsWithoutPuttingCredentialsInTheUrl() {
        var oracle = factory(getClass().getClassLoader()).connectionPlan(source(
            "oracle", "oracle://127.0.0.1", "XEPDB1", "{\"sslMode\":\"verify-full\"}"
        ));
        var clickhouse = factory(getClass().getClassLoader()).connectionPlan(source(
            "clickhouse", "clickhouse://127.0.0.1", "analytics", "{\"sslMode\":\"verify-full\"}"
        ));

        assertEquals("jdbc:oracle:thin:@tcps://127.0.0.1:1521/XEPDB1", oracle.jdbcUrl());
        assertEquals("true", oracle.properties().getProperty("oracle.net.ssl_server_dn_match"));
        assertEquals("jdbc:clickhouse:https://127.0.0.1:8123/analytics", clickhouse.jdbcUrl());
        assertEquals("5000", clickhouse.properties().getProperty("connection_timeout"));
    }

    @Test
    void failsExplicitlyWhenDriverIsUnavailableOrStoredConfigIsInvalid() {
        ReadOnlyJdbcConnectionFactory missingDriver = factory(new ClassLoader(null) {
        });

        ServiceException unavailable = assertThrows(ServiceException.class, () -> missingDriver.connectionPlan(
            source("postgresql", "postgresql://127.0.0.1", "app", "{\"sslMode\":\"disable\"}")
        ));
        assertTrue(unavailable.getMessage().contains("JDBC 驱动"));

        assertThrows(ServiceException.class, () -> factory(getClass().getClassLoader()).connectionPlan(
            source("mysql", "mysql://127.0.0.1", "app", "{\"unknown\":true}")
        ));
        assertThrows(ServiceException.class, () -> factory(getClass().getClassLoader()).connectionPlan(
            source("mysql", "mysql://127.0.0.1", "app", "{")
        ));
    }

    @Test
    void enforcesOracleReadOnlyTransactionAtConnectionOpen() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        factory(getClass().getClassLoader()).configureConnection(connection, DataSourceType.ORACLE);

        verify(connection).setReadOnly(true);
        verify(connection).setAutoCommit(false);
        verify(statement).execute("SET TRANSACTION READ ONLY");
        verify(statement).close();
    }

    @Test
    void leavesClickHouseInAutoCommitWhileApplyingServerReadOnlyHint() throws Exception {
        Connection connection = mock(Connection.class);

        factory(getClass().getClassLoader()).configureConnection(connection, DataSourceType.CLICKHOUSE);

        verify(connection).setReadOnly(true);
        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    private ReadOnlyJdbcConnectionFactory factory(ClassLoader loader) {
        DataCredentialResolver credentials = ignored -> new DataCredential("report_reader", "secret");
        return new ReadOnlyJdbcConnectionFactory(
            credentials,
            new DataSourceEndpointPolicy(true, true),
            JSON_MAPPER,
            loader
        );
    }

    private AgentDataSource source(
        String dbType,
        String endpoint,
        String database,
        String configJson
    ) {
        AgentDataSource source = new AgentDataSource();
        source.setDbType(dbType);
        source.setEndpointUrl(endpoint);
        source.setDatabaseName(database);
        source.setCredentialRef("env:REPORTING_DB");
        source.setConfigJson(configJson);
        source.setConnectionTimeoutMs(5000);
        source.setStatementTimeoutMs(15000);
        return source;
    }
}
