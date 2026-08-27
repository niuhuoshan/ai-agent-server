package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.service.DataMetadataPersistenceService.DiscoveredColumn;
import group.aitools.nhs.platform.data.service.DataMetadataPersistenceService.DiscoveredTable;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers table, column and primary-key metadata through the JDBC standard API. */
@Component
public class JdbcMetadataDiscovery {

    static final int MAX_TABLES = 500;
    static final int MAX_COLUMNS = 10000;
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    public List<DiscoveredTable> discover(
        Connection connection,
        AgentDataSource source,
        List<String> schemas
    ) throws SQLException {
        if (schemas == null || schemas.isEmpty()) {
            throw badRequest("数据集没有可同步的 Schema");
        }
        DataSourceType type = DataSourceType.require(source.getDbType());
        DatabaseMetaData metadata = connection.getMetaData();
        String escape = metadata.getSearchStringEscape();
        Map<String, TableReference> references = new LinkedHashMap<>();
        for (String requested : schemas) {
            MetadataScope scope = scope(type, source, requested, escape);
            collectTables(metadata, scope, references);
        }
        if (references.size() > MAX_TABLES) {
            throw badRequest("数据集表数超过一期上限 " + MAX_TABLES);
        }

        List<DiscoveredTable> result = new ArrayList<>(references.size());
        int columnCount = 0;
        for (TableReference table : references.values()) {
            Set<String> primaryKeys = primaryKeys(metadata, table);
            List<DiscoveredColumn> columns = columns(metadata, table, primaryKeys, escape);
            if (columns.isEmpty()) {
                throw new ServiceException(
                    "无法读取数据表字段：" + table.physicalSchema() + '.' + table.tableName(),
                    502
                );
            }
            columnCount += columns.size();
            if (columnCount > MAX_COLUMNS) {
                throw badRequest("数据集字段数超过一期上限 " + MAX_COLUMNS);
            }
            result.add(new DiscoveredTable(
                table.physicalSchema(), table.tableName(), table.tableType(), columns
            ));
        }
        result.sort(Comparator
            .comparing(DiscoveredTable::schemaName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DiscoveredTable::tableName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    static MetadataScope scope(
        DataSourceType type,
        AgentDataSource source,
        String requestedSchema,
        String escape
    ) {
        String requested = requestedSchema == null ? "" : requestedSchema.strip();
        if (!requested.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")) {
            throw badRequest("数据集 Schema 无效：" + requested);
        }
        String database = source.getDatabaseName() == null ? "" : source.getDatabaseName().strip();
        if (type.restrictToDatabase() && !requested.equalsIgnoreCase(database)) {
            throw badRequest(type.label() + " 元数据发现不能越过已配置数据库");
        }
        String canonical = type == DataSourceType.ORACLE
            ? requested.toUpperCase(Locale.ROOT)
            : type.restrictToDatabase() ? database : requested;
        String pattern = escapePattern(canonical, escape);
        return switch (type) {
            case MYSQL -> new MetadataScope(database, null, canonical, type);
            case CLICKHOUSE -> new MetadataScope(null, pattern, canonical, type);
            case ORACLE -> new MetadataScope(null, pattern, canonical, type);
            case POSTGRESQL, SQLSERVER -> new MetadataScope(database, pattern, canonical, type);
        };
    }

    private void collectTables(
        DatabaseMetaData metadata,
        MetadataScope scope,
        Map<String, TableReference> references
    ) throws SQLException {
        try (ResultSet result = metadata.getTables(
            scope.catalog(), scope.schemaPattern(), "%", TABLE_TYPES.clone()
        )) {
            while (result.next()) {
                String catalog = trimToNull(result.getString("TABLE_CAT"));
                String schema = trimToNull(result.getString("TABLE_SCHEM"));
                String tableName = trimToNull(result.getString("TABLE_NAME"));
                String tableType = trimToNull(result.getString("TABLE_TYPE"));
                if (tableName == null || !scope.matches(catalog, schema)) {
                    continue;
                }
                String physicalSchema = schema != null ? schema
                    : catalog != null ? catalog : scope.physicalSchema();
                String key = physicalSchema.toLowerCase(Locale.ROOT)
                    + '\u0000' + tableName.toLowerCase(Locale.ROOT);
                references.putIfAbsent(key, new TableReference(
                    catalog != null ? catalog : scope.catalog(),
                    schema != null ? schema : unescape(scope.schemaPattern(), metadata.getSearchStringEscape()),
                    physicalSchema,
                    tableName,
                    normalizeTableType(tableType)
                ));
                if (references.size() > MAX_TABLES) {
                    throw badRequest("数据集表数超过一期上限 " + MAX_TABLES);
                }
            }
        }
    }

    private Set<String> primaryKeys(
        DatabaseMetaData metadata,
        TableReference table
    ) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(
            table.catalog(), table.schema(), table.tableName()
        )) {
            while (keys.next()) {
                String column = trimToNull(keys.getString("COLUMN_NAME"));
                if (column != null) {
                    result.add(column.toLowerCase(Locale.ROOT));
                }
            }
        } catch (SQLFeatureNotSupportedException exception) {
            return Set.of();
        }
        return Set.copyOf(result);
    }

    private List<DiscoveredColumn> columns(
        DatabaseMetaData metadata,
        TableReference table,
        Set<String> primaryKeys,
        String escape
    ) throws SQLException {
        List<DiscoveredColumn> columns = new ArrayList<>();
        try (ResultSet result = metadata.getColumns(
            table.catalog(),
            table.schema(),
            escapePattern(table.tableName(), escape),
            "%"
        )) {
            while (result.next()) {
                String columnName = trimToNull(result.getString("COLUMN_NAME"));
                if (columnName == null || !table.matches(
                    trimToNull(result.getString("TABLE_CAT")),
                    trimToNull(result.getString("TABLE_SCHEM")),
                    trimToNull(result.getString("TABLE_NAME"))
                )) {
                    continue;
                }
                int ordinal = result.getInt("ORDINAL_POSITION");
                String typeName = trimToNull(result.getString("TYPE_NAME"));
                if (typeName == null) {
                    typeName = jdbcTypeName(result.getInt("DATA_TYPE"));
                }
                columns.add(new DiscoveredColumn(
                    columnName,
                    typeName,
                    ordinal,
                    primaryKeys.contains(columnName.toLowerCase(Locale.ROOT))
                ));
                if (columns.size() > MAX_COLUMNS) {
                    throw badRequest("单表字段数超过一期上限 " + MAX_COLUMNS);
                }
            }
        }
        columns.sort(Comparator.comparingInt(DiscoveredColumn::ordinalPosition));
        return List.copyOf(columns);
    }

    private static String jdbcTypeName(int jdbcType) {
        try {
            return JDBCType.valueOf(jdbcType).getName();
        } catch (IllegalArgumentException exception) {
            return "OTHER";
        }
    }

    private static String normalizeTableType(String value) {
        return value != null && value.toUpperCase(Locale.ROOT).contains("VIEW") ? "view" : "table";
    }

    private static String escapePattern(String value, String escape) {
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape)
            .replace("%", escape + "%")
            .replace("_", escape + "_");
    }

    private static String unescape(String value, String escape) {
        if (value == null || escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape + "_", "_")
            .replace(escape + "%", "%")
            .replace(escape + escape, escape);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    record MetadataScope(
        String catalog,
        String schemaPattern,
        String physicalSchema,
        DataSourceType type
    ) {
        boolean matches(String rowCatalog, String rowSchema) {
            if (type.catalogMetadata()) {
                return equalsIgnoreCase(physicalSchema, rowCatalog)
                    || (rowCatalog == null && equalsIgnoreCase(physicalSchema, rowSchema));
            }
            return equalsIgnoreCase(physicalSchema, rowSchema)
                && (rowCatalog == null || catalog == null || equalsIgnoreCase(catalog, rowCatalog));
        }
    }

    private record TableReference(
        String catalog,
        String schema,
        String physicalSchema,
        String tableName,
        String tableType
    ) {
        boolean matches(String rowCatalog, String rowSchema, String rowTable) {
            return equalsIgnoreCase(tableName, rowTable)
                && (catalog == null || rowCatalog == null || equalsIgnoreCase(catalog, rowCatalog))
                && (schema == null || rowSchema == null || equalsIgnoreCase(schema, rowSchema));
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
