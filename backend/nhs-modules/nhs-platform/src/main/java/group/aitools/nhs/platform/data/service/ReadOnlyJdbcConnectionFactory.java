package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/** Opens short-lived, engine-specific JDBC connections with read-only defaults. */
@Component
public class ReadOnlyJdbcConnectionFactory {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DataCredentialResolver credentialResolver;
    private final DataSourceEndpointPolicy endpointPolicy;
    private final JsonMapper jsonMapper;
    private final ClassLoader driverClassLoader;

    @Autowired
    public ReadOnlyJdbcConnectionFactory(
        DataCredentialResolver credentialResolver,
        DataSourceEndpointPolicy endpointPolicy,
        JsonMapper jsonMapper
    ) {
        this(
            credentialResolver,
            endpointPolicy,
            jsonMapper,
            ReadOnlyJdbcConnectionFactory.class.getClassLoader()
        );
    }

    ReadOnlyJdbcConnectionFactory(
        DataCredentialResolver credentialResolver,
        DataSourceEndpointPolicy endpointPolicy,
        JsonMapper jsonMapper,
        ClassLoader driverClassLoader
    ) {
        this.credentialResolver = credentialResolver;
        this.endpointPolicy = endpointPolicy;
        this.jsonMapper = jsonMapper;
        this.driverClassLoader = driverClassLoader;
    }

    public Connection open(AgentDataSource source) throws SQLException {
        JdbcConnectionPlan plan = connectionPlan(source);
        Connection connection = DriverManager.getConnection(plan.jdbcUrl(), plan.properties());
        try {
            configureConnection(connection, plan.type());
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    public String validationQuery(AgentDataSource source) {
        return DataSourceType.require(source.getDbType()).validationQuery();
    }

    public void rollback(Connection connection, AgentDataSource source) throws SQLException {
        if (DataSourceType.require(source.getDbType()).transactions()) {
            connection.rollback();
        }
    }

    public void prepareQuerySession(Statement controls, AgentDataSource source) throws SQLException {
        DataSourceType type = DataSourceType.require(source.getDbType());
        if (type == DataSourceType.POSTGRESQL) {
            controls.execute("SET LOCAL search_path TO pg_catalog");
            controls.execute("SET LOCAL statement_timeout = " + source.getStatementTimeoutMs());
        } else if (type == DataSourceType.SQLSERVER) {
            controls.execute("SET LOCK_TIMEOUT " + source.getStatementTimeoutMs());
        }
    }

    void configureConnection(Connection connection, DataSourceType type) throws SQLException {
        connection.setReadOnly(true);
        if (type.transactions()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        if (type == DataSourceType.ORACLE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
            }
        }
    }

    JdbcConnectionPlan connectionPlan(AgentDataSource source) {
        if (source == null) {
            throw new ServiceException("数据源配置不能为空", 400);
        }
        DataSourceType type = DataSourceType.require(source.getDbType());
        var target = endpointPolicy.normalize(
            type.id(), source.getEndpointUrl(), source.getDatabaseName()
        );
        endpointPolicy.validateNetworkTarget(target);
        Map<String, Object> rawConfig = parseConfig(source.getConfigJson());
        Map<String, Object> config = type.normalizeConfig(rawConfig);
        int connectionTimeout = bounded(
            source.getConnectionTimeoutMs(), 1000, 30000, "连接超时"
        );
        int statementTimeout = bounded(
            source.getStatementTimeoutMs(), 1000, 120000, "查询超时"
        );
        requireDriver(type);
        DataCredential credential = resolveCredential(source.getCredentialRef());
        return new JdbcConnectionPlan(
            type,
            type.jdbcUrl(target, config),
            type.connectionProperties(
                target, config, credential, connectionTimeout, statementTimeout
            )
        );
    }

    private void requireDriver(DataSourceType type) {
        try {
            Class.forName(type.driverClass(), true, driverClassLoader);
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new ServiceException(type.label() + " JDBC 驱动未安装或无法加载", 502);
        }
    }

    private Map<String, Object> parseConfig(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, MAP_TYPE);
        } catch (RuntimeException exception) {
            throw new ServiceException("数据源配置不是有效 JSON", 400);
        }
    }

    private DataCredential resolveCredential(String reference) {
        try {
            return credentialResolver.resolve(reference);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "数据源凭证配置无效" : exception.getMessage();
            throw new ServiceException(message, 502);
        }
    }

    private int bounded(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) {
            throw new ServiceException(label + "配置无效", 400);
        }
        return value;
    }

    record JdbcConnectionPlan(
        DataSourceType type,
        String jdbcUrl,
        Properties properties
    ) {
    }
}
