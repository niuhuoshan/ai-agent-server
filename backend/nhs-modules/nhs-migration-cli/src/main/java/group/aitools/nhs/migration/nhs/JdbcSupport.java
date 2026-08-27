package group.aitools.nhs.migration.nhs;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 表示{@code Jdbc}相关的领域对象。
 */
final class JdbcSupport {

    /**
     * 创建 {@code JdbcSupport} 实例并初始化所需依赖。
     */
    private JdbcSupport() {
    }

    /**
     * 处理{@code tableExists}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param schema {@code schema}参数
     * @param table {@code table}参数
     * @return 判断结果，{@code true} 表示条件成立
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet result = metadata.getTables(connection.getCatalog(), schema, candidate, new String[]{"TABLE"})) {
                if (result.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 处理{@code columns}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param schema {@code schema}参数
     * @param table {@code table}参数
     * @return 符合条件的数据集合
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static Set<String> columns(Connection connection, String schema, String table) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Set<String> result = new TreeSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getColumns(connection.getCatalog(), schema, table, "%")) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        if (result.isEmpty() && schema != null) {
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
                while (rows.next()) {
                    result.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 处理{@code count}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param qualifiedTable {@code qualifiedTable}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static long count(Connection connection, String qualifiedTable) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + qualifiedTable)) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * 处理{@code rows}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param qualifiedTable {@code qualifiedTable}参数
     * @param orderColumn {@code orderColumn}参数
     * @return 符合条件的数据集合
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static List<Map<String, Object>> rows(
        Connection connection,
        String qualifiedTable,
        String orderColumn
    ) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String order = orderColumn == null ? "" : " ORDER BY " + quoteIdentifier(orderColumn);
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(500);
            try (ResultSet result = statement.executeQuery("SELECT * FROM " + qualifiedTable + order)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), result.getObject(index));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /**
     * 更新{@code update}。
     *
     * @param connection {@code connection}参数
     * @param sql {@code sql}参数
     * @param parameters {@code parameters}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    /**
     * 处理{@code scalar}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param sql {@code sql}参数
     * @param type 业务类型
     * @param parameters {@code parameters}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static <T> T scalar(Connection connection, String sql, Class<T> type, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return result.getObject(1, type);
            }
        }
    }

    /**
     * 处理{@code row}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param sql {@code sql}参数
     * @param parameters {@code parameters}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static Map<String, Object> row(Connection connection, String sql, Object... parameters) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Map.of();
                }
                ResultSetMetaData metadata = result.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), result.getObject(index));
                }
                // Target rows legitimately contain nullable columns; Map.copyOf rejects null values.
                return row;
            }
        }
    }

    /**
     * 处理{@code bind}相关逻辑。
     *
     * @param statement {@code statement}参数
     * @param parameters {@code parameters}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            if (value instanceof Instant instant) {
                statement.setTimestamp(index + 1, Timestamp.from(instant));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    /**
     * 处理{@code qualified}并返回对应结果。
     *
     * @param schema {@code schema}参数
     * @param table {@code table}参数
     * @return 处理结果
     */
    static String qualified(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /**
     * 处理{@code quoteIdentifier}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    static String quoteIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("unsafe SQL identifier: " + value);
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
