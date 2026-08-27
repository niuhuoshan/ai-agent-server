package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class JdbcMetadataDiscoveryTest {

    @ParameterizedTest
    @CsvSource({
        "postgresql, analytics, public, analytics, public, public",
        "mysql, analytics, analytics, analytics, , analytics",
        "oracle, XEPDB1, reporting, , REPORTING, REPORTING",
        "sqlserver, analytics, dbo, analytics, dbo, dbo",
        "clickhouse, analytics, analytics, , analytics, analytics"
    })
    void buildsStrictMetadataScopes(
        String dbType,
        String database,
        String requested,
        String expectedCatalog,
        String expectedSchema,
        String expectedPhysicalSchema
    ) {
        var scope = JdbcMetadataDiscovery.scope(
            DataSourceType.require(dbType), source(dbType, database), requested, "\\"
        );

        assertEquals(emptyToNull(expectedCatalog), scope.catalog());
        assertEquals(emptyToNull(expectedSchema), scope.schemaPattern());
        assertEquals(expectedPhysicalSchema, scope.physicalSchema());
    }

    @Test
    void discoversTablesColumnsAndPrimaryKeysThroughDatabaseMetaData() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getTables(eq("analytics"), eq("public"), eq("%"), any(String[].class)))
            .thenReturn(rows(
                columns(
                    "TABLE_CAT", Types.VARCHAR,
                    "TABLE_SCHEM", Types.VARCHAR,
                    "TABLE_NAME", Types.VARCHAR,
                    "TABLE_TYPE", Types.VARCHAR
                ),
                row("analytics", "public", "orders", "TABLE")
            ));
        when(metadata.getPrimaryKeys("analytics", "public", "orders"))
            .thenReturn(rows(
                columns("COLUMN_NAME", Types.VARCHAR),
                row("id")
            ));
        when(metadata.getColumns("analytics", "public", "orders", "%"))
            .thenReturn(rows(
                columns(
                    "TABLE_CAT", Types.VARCHAR,
                    "TABLE_SCHEM", Types.VARCHAR,
                    "TABLE_NAME", Types.VARCHAR,
                    "COLUMN_NAME", Types.VARCHAR,
                    "ORDINAL_POSITION", Types.INTEGER,
                    "TYPE_NAME", Types.VARCHAR,
                    "DATA_TYPE", Types.INTEGER
                ),
                row("analytics", "public", "orders", "id", 1, "int8", Types.BIGINT),
                row("analytics", "public", "orders", "title", 2, "varchar", Types.VARCHAR)
            ));

        var discovered = new JdbcMetadataDiscovery().discover(
            connection, source("postgresql", "analytics"), List.of("public")
        );

        assertEquals(1, discovered.size());
        assertEquals("public", discovered.getFirst().schemaName());
        assertEquals("orders", discovered.getFirst().tableName());
        assertEquals(2, discovered.getFirst().columns().size());
        assertTrue(discovered.getFirst().columns().getFirst().primary());
        assertEquals("varchar", discovered.getFirst().columns().get(1).dataType());
    }

    @Test
    void discoversClickHouseDatabaseThroughItsJdbcV2Schema() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getTables(isNull(), eq("analytics"), eq("%"), any(String[].class)))
            .thenReturn(rows(
                columns(
                    "TABLE_CAT", Types.VARCHAR,
                    "TABLE_SCHEM", Types.VARCHAR,
                    "TABLE_NAME", Types.VARCHAR,
                    "TABLE_TYPE", Types.VARCHAR
                ),
                row(null, "analytics", "events", "TABLE")
            ));
        when(metadata.getPrimaryKeys(null, "analytics", "events"))
            .thenReturn(rows(columns("COLUMN_NAME", Types.VARCHAR)));
        when(metadata.getColumns(null, "analytics", "events", "%"))
            .thenReturn(rows(
                columns(
                    "TABLE_CAT", Types.VARCHAR,
                    "TABLE_SCHEM", Types.VARCHAR,
                    "TABLE_NAME", Types.VARCHAR,
                    "COLUMN_NAME", Types.VARCHAR,
                    "ORDINAL_POSITION", Types.INTEGER,
                    "TYPE_NAME", Types.VARCHAR,
                    "DATA_TYPE", Types.INTEGER
                ),
                row(null, "analytics", "events", "event_id", 1, "UInt64", Types.BIGINT)
            ));

        var discovered = new JdbcMetadataDiscovery().discover(
            connection, source("clickhouse", "analytics"), List.of("analytics")
        );

        assertEquals(1, discovered.size());
        assertEquals("analytics", discovered.getFirst().schemaName());
        assertEquals("UInt64", discovered.getFirst().columns().getFirst().dataType());
    }

    @Test
    void rejectsCatalogDiscoveryOutsideConfiguredDatabase() {
        assertThrows(ServiceException.class, () -> JdbcMetadataDiscovery.scope(
            DataSourceType.MYSQL,
            source("mysql", "analytics"),
            "other_database",
            "\\"
        ));
    }

    private AgentDataSource source(String dbType, String database) {
        AgentDataSource source = new AgentDataSource();
        source.setDbType(dbType);
        source.setDatabaseName(database);
        return source;
    }

    private CachedRowSet rows(Column[] columns, Object[]... rows) throws SQLException {
        CachedRowSet result = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.length);
        for (int index = 0; index < columns.length; index++) {
            metadata.setColumnName(index + 1, columns[index].name());
            metadata.setColumnLabel(index + 1, columns[index].name());
            metadata.setColumnType(index + 1, columns[index].type());
        }
        result.setMetaData(metadata);
        for (Object[] row : rows) {
            result.moveToInsertRow();
            for (int index = 0; index < row.length; index++) {
                if (row[index] == null) {
                    result.updateNull(index + 1);
                } else {
                    result.updateObject(index + 1, row[index]);
                }
            }
            result.insertRow();
            result.moveToCurrentRow();
        }
        result.beforeFirst();
        return result;
    }

    private Column[] columns(Object... values) {
        Column[] result = new Column[values.length / 2];
        for (int index = 0; index < values.length; index += 2) {
            result[index / 2] = new Column((String) values[index], (Integer) values[index + 1]);
        }
        return result;
    }

    private Object[] row(Object... values) {
        return values;
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record Column(String name, int type) {
    }
}
