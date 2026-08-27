package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class DataSourceEndpointPolicyTest {

    private final DataSourceEndpointPolicy policy = new DataSourceEndpointPolicy(true, true);

    @ParameterizedTest
    @CsvSource({
        "postgresql, postgresql://DB.EXAMPLE.COM, reporting, postgresql://db.example.com:5432, jdbc:postgresql://db.example.com:5432/reporting",
        "mysql, mysql://DB.EXAMPLE.COM, reporting, mysql://db.example.com:3306, jdbc:mysql://db.example.com:3306/reporting",
        "oracle, oracle://DB.EXAMPLE.COM, XEPDB1, oracle://db.example.com:1521, jdbc:oracle:thin:@//db.example.com:1521/XEPDB1",
        "sqlserver, sqlserver://DB.EXAMPLE.COM, reporting, sqlserver://db.example.com:1433, jdbc:sqlserver://db.example.com:1433",
        "clickhouse, clickhouse://DB.EXAMPLE.COM, analytics, clickhouse://db.example.com:8123, jdbc:clickhouse:http://db.example.com:8123/analytics"
    })
    void normalizesEachSupportedEndpoint(
        String dbType,
        String endpoint,
        String database,
        String expectedEndpoint,
        String expectedJdbcUrl
    ) {
        var target = policy.normalize(dbType, endpoint, database);

        assertEquals(expectedEndpoint, target.normalizedEndpoint());
        assertEquals(expectedJdbcUrl, target.jdbcUrl());
    }

    @ParameterizedTest
    @CsvSource({
        "mysql, postgresql://db.example.com, reporting",
        "postgresql, postgresql://u:p@db.example.com, reporting",
        "mysql, mysql://db.example.com/other, reporting",
        "oracle, oracle://db.example.com?ssl=false, XEPDB1",
        "sqlserver, sqlserver://localhost:1433, reporting",
        "clickhouse, clickhouse://db.example.com, 'analytics;readonly=0'"
    })
    void rejectsSchemeConfusionCredentialsPathsParametersAndLocalTargets(
        String dbType,
        String endpoint,
        String database
    ) {
        DataSourceEndpointPolicy publicPolicy = new DataSourceEndpointPolicy(true, false);

        assertThrows(ServiceException.class, () -> publicPolicy.normalize(dbType, endpoint, database));
    }
}
