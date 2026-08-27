package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class DataSourceConfigurationValidatorTest {

    private final DataSourceConfigurationValidator validator = new DataSourceConfigurationValidator();

    @Test
    void acceptsOnlySupportedTypesAndEnvCredentials() {
        assertEquals("postgresql", validator.dbType("postgresql"));
        assertEquals("clickhouse", validator.dbType("clickhouse"));
        assertEquals("env:REPORTING_DB", validator.credentialReference(" env:REPORTING_DB "));

        assertThrows(ServiceException.class, () -> validator.dbType("sqlite"));
        assertThrows(ServiceException.class, () -> validator.credentialReference("plain-password"));
    }

    @Test
    void validatesSslModesPerEngineAndRejectsUnknownConfig() {
        assertEquals("prefer", validator.config("postgresql", Map.of()).get("sslMode"));
        assertEquals("verify-full", validator.config("sqlserver", Map.of()).get("sslMode"));
        assertEquals(
            "verify-ca",
            validator.config("mysql", Map.of("sslMode", "verify-ca")).get("sslMode")
        );

        assertThrows(ServiceException.class, () -> validator.config(
            "sqlserver", Map.of("sslMode", "prefer")
        ));
        assertThrows(ServiceException.class, () -> validator.config(
            "oracle", Map.of("walletPath", "/tmp/wallet")
        ));
        assertThrows(ServiceException.class, () -> validator.config(
            "clickhouse", Map.of("sslMode", "require")
        ));
    }

    @Test
    void scopesCatalogEnginesAndNormalizesOracleSchema() {
        assertEquals(
            List.of("analytics"),
            validator.schemas("mysql", "analytics", List.of("analytics"))
        );
        assertEquals(
            List.of("REPORTING"),
            validator.schemas("oracle", "XEPDB1", List.of("reporting"))
        );

        assertThrows(ServiceException.class, () -> validator.schemas(
            "mysql", "analytics", List.of("other_database")
        ));
        assertThrows(ServiceException.class, () -> validator.schemas(
            "clickhouse", "analytics", List.of("system")
        ));
        assertThrows(ServiceException.class, () -> validator.schemas(
            "postgresql", "analytics", List.of("pg_catalog")
        ));
    }
}
