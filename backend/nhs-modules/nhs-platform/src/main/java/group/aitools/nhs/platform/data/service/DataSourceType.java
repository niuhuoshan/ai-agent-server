package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Supported JDBC engines and their connection-level safety defaults. */
enum DataSourceType {

    POSTGRESQL(
        "postgresql", "PostgreSQL", "postgresql", 5432, "org.postgresql.Driver",
        "prefer", Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full"),
        false, false, true
    ),
    MYSQL(
        "mysql", "MySQL", "mysql", 3306, "com.mysql.cj.jdbc.Driver",
        "prefer", Set.of("disable", "prefer", "require", "verify-ca", "verify-full"),
        true, true, true
    ),
    ORACLE(
        "oracle", "Oracle", "oracle", 1521, "oracle.jdbc.OracleDriver",
        "disable", Set.of("disable", "require", "verify-ca", "verify-full"),
        false, false, true
    ),
    SQLSERVER(
        "sqlserver", "SQL Server", "sqlserver", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "verify-full", Set.of("disable", "require", "verify-full"),
        false, false, true
    ),
    CLICKHOUSE(
        "clickhouse", "ClickHouse", "clickhouse", 8123, "com.clickhouse.jdbc.Driver",
        "disable", Set.of("disable", "verify-full"),
        false, true, false
    );

    private static final Set<String> CONFIG_KEYS = Set.of("sslMode");

    private final String id;
    private final String label;
    private final String endpointScheme;
    private final int defaultPort;
    private final String driverClass;
    private final String defaultSslMode;
    private final Set<String> sslModes;
    private final boolean catalogMetadata;
    private final boolean restrictToDatabase;
    private final boolean transactions;

    DataSourceType(
        String id,
        String label,
        String endpointScheme,
        int defaultPort,
        String driverClass,
        String defaultSslMode,
        Set<String> sslModes,
        boolean catalogMetadata,
        boolean restrictToDatabase,
        boolean transactions
    ) {
        this.id = id;
        this.label = label;
        this.endpointScheme = endpointScheme;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
        this.defaultSslMode = defaultSslMode;
        this.sslModes = sslModes;
        this.catalogMetadata = catalogMetadata;
        this.restrictToDatabase = restrictToDatabase;
        this.transactions = transactions;
    }

    static DataSourceType require(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
            .filter(item -> item.id.equals(normalized))
            .findFirst()
            .orElseThrow(() -> badRequest(
                "不支持的数据源类型，仅支持 " + String.join("、", supportedIds())
            ));
    }

    static List<String> supportedIds() {
        return Arrays.stream(values()).map(DataSourceType::id).toList();
    }

    Map<String, Object> normalizeConfig(Map<String, Object> value) {
        Map<String, Object> source = value == null ? Map.of() : value;
        List<String> unknown = source.keySet().stream()
            .filter(key -> !CONFIG_KEYS.contains(key))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw badRequest("数据源配置包含不支持的字段：" + unknown);
        }
        Object rawMode = source.getOrDefault("sslMode", defaultSslMode);
        if (!(rawMode instanceof String mode) || !sslModes.contains(mode)) {
            throw badRequest(label + " sslMode 无效，可选值为 " + sslModes.stream().sorted().toList());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sslMode", mode);
        return Map.copyOf(normalized);
    }

    String jdbcUrl(
        DataSourceEndpointPolicy.DataConnectionTarget target,
        Map<String, Object> config
    ) {
        String host = target.renderedHost();
        String database = target.database();
        String sslMode = String.valueOf(config.getOrDefault("sslMode", defaultSslMode));
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ':' + target.port() + '/' + database;
            case MYSQL -> "jdbc:mysql://" + host + ':' + target.port() + '/' + database;
            case ORACLE -> "jdbc:oracle:thin:@" + ("disable".equals(sslMode) ? "//" : "tcps://")
                + host + ':' + target.port() + '/' + database;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ':' + target.port();
            case CLICKHOUSE -> "jdbc:clickhouse:"
                + ("disable".equals(sslMode) ? "http" : "https")
                + "://" + host + ':' + target.port() + '/' + database;
        };
    }

    Properties connectionProperties(
        DataSourceEndpointPolicy.DataConnectionTarget target,
        Map<String, Object> config,
        DataCredential credential,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        Properties properties = new Properties();
        properties.setProperty("user", credential.username());
        properties.setProperty("password", credential.password());
        String sslMode = String.valueOf(config.getOrDefault("sslMode", defaultSslMode));
        switch (this) {
            case POSTGRESQL -> postgresProperties(
                properties, sslMode, connectionTimeoutMs, statementTimeoutMs
            );
            case MYSQL -> mysqlProperties(
                properties, sslMode, connectionTimeoutMs, statementTimeoutMs
            );
            case ORACLE -> oracleProperties(
                properties, sslMode, connectionTimeoutMs, statementTimeoutMs
            );
            case SQLSERVER -> sqlServerProperties(
                properties, target.database(), sslMode, connectionTimeoutMs, statementTimeoutMs
            );
            case CLICKHOUSE -> clickHouseProperties(
                properties, connectionTimeoutMs, statementTimeoutMs
            );
        }
        return properties;
    }

    String validationQuery() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    String endpointScheme() {
        return endpointScheme;
    }

    int defaultPort() {
        return defaultPort;
    }

    String driverClass() {
        return driverClass;
    }

    String defaultSslMode() {
        return defaultSslMode;
    }

    boolean catalogMetadata() {
        return catalogMetadata;
    }

    boolean restrictToDatabase() {
        return restrictToDatabase;
    }

    boolean transactions() {
        return transactions;
    }

    private void postgresProperties(
        Properties properties,
        String sslMode,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        properties.setProperty("connectTimeout", seconds(connectionTimeoutMs));
        properties.setProperty("socketTimeout", seconds(statementTimeoutMs));
        properties.setProperty("ApplicationName", "nhs-readonly");
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("readOnly", "true");
        properties.setProperty("readOnlyMode", "always");
    }

    private void mysqlProperties(
        Properties properties,
        String sslMode,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        properties.setProperty("connectTimeout", String.valueOf(connectionTimeoutMs));
        properties.setProperty("socketTimeout", String.valueOf(statementTimeoutMs));
        properties.setProperty("connectionAttributes", "program_name:nhs-readonly");
        properties.setProperty("readOnlyPropagatesToServer", "true");
        properties.setProperty("sslMode", switch (sslMode) {
            case "disable" -> "DISABLED";
            case "prefer" -> "PREFERRED";
            case "require" -> "REQUIRED";
            case "verify-ca" -> "VERIFY_CA";
            case "verify-full" -> "VERIFY_IDENTITY";
            default -> throw badRequest("MySQL sslMode 无效");
        });
    }

    private void oracleProperties(
        Properties properties,
        String sslMode,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectionTimeoutMs));
        properties.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", String.valueOf(connectionTimeoutMs));
        properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(statementTimeoutMs));
        properties.setProperty("v$session.program", "nhs-readonly");
        if (!"disable".equals(sslMode)) {
            properties.setProperty(
                "oracle.net.ssl_server_dn_match",
                String.valueOf("verify-full".equals(sslMode))
            );
        }
    }

    private void sqlServerProperties(
        Properties properties,
        String database,
        String sslMode,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        properties.setProperty("databaseName", database);
        properties.setProperty("loginTimeout", seconds(connectionTimeoutMs));
        properties.setProperty("socketTimeout", String.valueOf(statementTimeoutMs));
        properties.setProperty("queryTimeout", seconds(statementTimeoutMs));
        properties.setProperty("applicationName", "nhs-readonly");
        // SQL Server treats ApplicationIntent as routing; the env credential must still be SELECT-only.
        properties.setProperty("applicationIntent", "ReadOnly");
        properties.setProperty("encrypt", String.valueOf(!"disable".equals(sslMode)));
        properties.setProperty("trustServerCertificate", String.valueOf("require".equals(sslMode)));
    }

    private void clickHouseProperties(
        Properties properties,
        int connectionTimeoutMs,
        int statementTimeoutMs
    ) {
        properties.setProperty("connection_timeout", String.valueOf(connectionTimeoutMs));
        properties.setProperty("socket_timeout", String.valueOf(statementTimeoutMs));
        properties.setProperty("clickhouse_setting_readonly", "1");
    }

    private String seconds(int milliseconds) {
        return String.valueOf(Math.max(1, (milliseconds + 999) / 1000));
    }

    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
