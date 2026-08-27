package group.aitools.nhs.platform.data.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FunctionAllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** AST-based single-statement SELECT validator against synchronized dataset metadata. */
@Component
public class ReadOnlySqlValidator {

    private static final int MAX_SQL_BYTES = 64 * 1024;
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
        "information_schema", "pg_catalog", "pg_toast"
    );
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
        "abs", "avg", "ceil", "ceiling", "coalesce", "concat", "count",
        "date_part", "date_trunc", "extract", "floor", "greatest", "least",
        "length", "lower", "max", "min", "nullif", "round", "substring",
        "sum", "to_char", "trim", "upper"
    );

    public ValidatedSql validate(
        String sql,
        List<AgentDataTable> catalogTables,
        List<AgentDataColumn> catalogColumns
    ) {
        String candidate = sql == null ? "" : sql.strip();
        if (candidate.isEmpty() || candidate.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw badRequest("候选 SQL 为空或超过 64KB 限制");
        }

        Statement statement;
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(candidate);
            if (statements.size() != 1) {
                throw badRequest("只允许提交一条 SQL 语句");
            }
            statement = statements.get(0);
        } catch (JSQLParserException exception) {
            throw badRequest("候选 SQL 无法解析");
        }
        if (!(statement instanceof Select select)) {
            throw badRequest("一期只允许 SELECT 查询");
        }
        Map<String, Set<String>> cteColumns = collectCteColumns(select);

        SqlCollector collector = new SqlCollector();
        collector.getTables(statement);
        if (collector.lockingOrInto) {
            throw badRequest("SELECT INTO、FOR UPDATE 和锁定查询不允许执行");
        }
        if (collector.wildcard) {
            throw badRequest("查询必须显式列出字段，不能使用通配符");
        }
        if (collector.tables.isEmpty()) {
            throw badRequest("查询必须引用数据集中的表");
        }
        for (List<String> functionName : collector.functions) {
            if (functionName.size() != 1
                || !SAFE_FUNCTIONS.contains(unquote(functionName.get(0)).toLowerCase(Locale.ROOT))) {
                throw badRequest("候选 SQL 使用了未获准的函数");
            }
        }

        Catalog catalog = new Catalog(catalogTables, catalogColumns);
        Map<String, TablePolicy> aliases = new HashMap<>();
        Map<String, Set<String>> cteAliases = new HashMap<>(cteColumns);
        LinkedHashSet<TablePolicy> referencedTables = new LinkedHashSet<>();
        for (Table table : collector.tables) {
            if (table.getDatabaseName() != null || table.getCatalogName() != null) {
                throw badRequest("候选 SQL 不允许跨数据库引用");
            }
            String schema = unquote(table.getUnquotedSchemaName());
            String name = unquote(table.getUnquotedName());
            if ((schema == null || schema.isBlank()) && name != null) {
                Set<String> outputs = cteColumns.get(name.toLowerCase(Locale.ROOT));
                if (outputs != null) {
                    if (table.getAlias() != null) {
                        cteAliases.put(
                            unquote(table.getAlias().getUnquotedName()).toLowerCase(Locale.ROOT),
                            outputs
                        );
                    }
                    continue;
                }
            }
            if (schema == null || schema.isBlank()) {
                throw badRequest("所有数据表必须使用 Schema 限定名");
            }
            if (SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))
                || schema.toLowerCase(Locale.ROOT).startsWith("pg_")) {
                throw badRequest("候选 SQL 不允许访问系统 Schema");
            }
            TablePolicy policy = catalog.requireTable(schema, name);
            referencedTables.add(policy);
            aliases.put(name.toLowerCase(Locale.ROOT), policy);
            aliases.put((schema + "." + name).toLowerCase(Locale.ROOT), policy);
            if (table.getAlias() != null) {
                aliases.put(unquote(table.getAlias().getUnquotedName()).toLowerCase(Locale.ROOT), policy);
            }
        }

        LinkedHashSet<String> referencedColumns = new LinkedHashSet<>();
        for (Column column : collector.columns) {
            String name = unquote(column.getUnquotedColumnName());
            if (name == null || name.isBlank()) {
                throw badRequest("候选 SQL 包含无效字段");
            }
            String qualifier = unquote(column.getUnquotedTableName());
            if (qualifier != null && !qualifier.isBlank()) {
                TablePolicy table = aliases.get(qualifier.toLowerCase(Locale.ROOT));
                if (table == null) {
                    Set<String> outputs = cteAliases.get(qualifier.toLowerCase(Locale.ROOT));
                    if (outputs == null || !outputs.contains(name.toLowerCase(Locale.ROOT))) {
                        throw badRequest("字段引用了数据集之外的表、CTE 或别名：" + qualifier);
                    }
                    continue;
                }
                table.requireColumn(name);
                referencedColumns.add(table.qualifiedName() + "." + name);
            } else {
                List<TablePolicy> matches = referencedTables.stream()
                    .filter(table -> table.hasColumn(name))
                    .toList();
                if (matches.isEmpty()) {
                    boolean cteOutput = cteColumns.values().stream()
                        .anyMatch(outputs -> outputs.contains(name.toLowerCase(Locale.ROOT)));
                    if (!cteOutput) {
                        throw badRequest("字段不在已同步的数据集元数据或CTE输出中：" + name);
                    }
                    continue;
                }
                for (TablePolicy table : matches) {
                    table.requireColumn(name);
                }
                referencedColumns.add(name);
            }
        }

        String normalizedSql = statement.toString();
        List<String> tableNames = referencedTables.stream()
            .map(TablePolicy::qualifiedName).sorted().toList();
        List<String> columnNames = referencedColumns.stream().sorted().toList();
        return new ValidatedSql(
            normalizedSql, ContentHashing.sha256(normalizedSql), tableNames, columnNames
        );
    }

    private Map<String, Set<String>> collectCteColumns(Select select) {
        if (select.getWithItemsList() == null || select.getWithItemsList().isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (WithItem<?> item : select.getWithItemsList()) {
            String name = unquote(item.getUnquotedAliasName());
            if (name == null || name.isBlank()) {
                throw badRequest("CTE必须声明有效名称");
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (result.containsKey(normalizedName)) {
                throw badRequest("CTE名称不能重复：" + name);
            }
            if (item.getSelect() == null || item.getSelect().getSelect() == null
                || item.getSelect().getSelect().getPlainSelect() == null) {
                throw badRequest("CTE只允许单个只读SELECT查询");
            }

            LinkedHashSet<String> columns = new LinkedHashSet<>();
            if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                for (SelectItem<?> output : item.getWithItemList()) {
                    addCteOutput(columns, output);
                }
            } else {
                for (SelectItem<?> output : item.getSelect().getSelect().getPlainSelect().getSelectItems()) {
                    addCteOutput(columns, output);
                }
            }
            if (columns.isEmpty()) {
                throw badRequest("CTE必须显式声明可识别的输出字段");
            }
            result.put(normalizedName, Set.copyOf(columns));
        }
        return Map.copyOf(result);
    }

    private void addCteOutput(Set<String> target, SelectItem<?> output) {
        String name = unquote(output.getUnquotedAliasName());
        if ((name == null || name.isBlank()) && output.getExpression() instanceof Column column) {
            name = unquote(column.getUnquotedColumnName());
        }
        if (name == null || name.isBlank()) {
            throw badRequest("CTE计算字段必须声明别名");
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!target.add(normalized)) {
            throw badRequest("CTE输出字段不能重复：" + name);
        }
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("`") && result.endsWith("`")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("\"\"", "\"");
    }

    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    public record ValidatedSql(
        String sql,
        String sqlHash,
        List<String> tables,
        List<String> columns
    ) {

        public ValidatedSql {
            tables = List.copyOf(tables);
            columns = List.copyOf(columns);
        }
    }

    private final class Catalog {
        private final Map<String, TablePolicy> tables;

        private Catalog(List<AgentDataTable> sourceTables, List<AgentDataColumn> sourceColumns) {
            Map<Long, Map<String, ColumnPolicy>> columns = new HashMap<>();
            for (AgentDataColumn column : sourceColumns) {
                columns.computeIfAbsent(column.getTableId(), ignored -> new LinkedHashMap<>())
                    .put(column.getPhysicalName().toLowerCase(Locale.ROOT), new ColumnPolicy(
                        column.getPhysicalName(), Boolean.TRUE.equals(column.getIsSensitive()),
                        "active".equals(column.getStatus()) && Boolean.TRUE.equals(column.getMetadataPresent())
                    ));
            }
            Map<String, TablePolicy> result = new HashMap<>();
            for (AgentDataTable table : sourceTables) {
                TablePolicy policy = new TablePolicy(
                    table.getPhysicalSchema(), table.getPhysicalName(),
                    "active".equals(table.getStatus()) && Boolean.TRUE.equals(table.getMetadataPresent()),
                    columns.getOrDefault(table.getId(), Map.of())
                );
                result.put(policy.qualifiedName().toLowerCase(Locale.ROOT), policy);
            }
            this.tables = Map.copyOf(result);
        }

        private TablePolicy requireTable(String schema, String table) {
            TablePolicy policy = tables.get((schema + "." + table).toLowerCase(Locale.ROOT));
            if (policy == null || !policy.active()) {
                throw badRequest("数据表不属于当前活动数据集：" + schema + "." + table);
            }
            return policy;
        }
    }

    private record TablePolicy(
        String schema,
        String table,
        boolean active,
        Map<String, ColumnPolicy> columns
    ) {

        private String qualifiedName() {
            return schema + "." + table;
        }

        private boolean hasColumn(String name) {
            return columns.containsKey(name.toLowerCase(Locale.ROOT));
        }

        private void requireColumn(String name) {
            ColumnPolicy column = columns.get(name.toLowerCase(Locale.ROOT));
            if (column == null || !column.active()) {
                throw badRequest("字段不属于当前活动数据集：" + qualifiedName() + "." + name);
            }
            if (column.sensitive()) {
                throw badRequest("敏感字段不能通过默认数据查询返回：" + qualifiedName() + "." + name);
            }
        }
    }

    private record ColumnPolicy(String name, boolean sensitive, boolean active) {
    }

    private static final class SqlCollector extends TablesNamesFinder<Void> {
        private final List<Table> tables = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private final List<List<String>> functions = new ArrayList<>();
        private boolean wildcard;
        private boolean lockingOrInto;

        @Override
        public <S> Void visit(Table table, S context) {
            tables.add(table);
            return super.visit(table, context);
        }

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            functions.add(List.copyOf(function.getMultipartName()));
            if (function.isAllColumns()) {
                return null;
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(AllColumns allColumns, S context) {
            wildcard = true;
            return super.visit(allColumns, context);
        }

        @Override
        public <S> Void visit(AllTableColumns allTableColumns, S context) {
            wildcard = true;
            return super.visit(allTableColumns, context);
        }

        @Override
        public <S> Void visit(FunctionAllColumns functionAllColumns, S context) {
            functions.add(List.copyOf(functionAllColumns.getFunction().getMultipartName()));
            return null;
        }

        @Override
        public <S> Void visit(PlainSelect select, S context) {
            if (select.getSelectItems() != null && select.getSelectItems().stream()
                .anyMatch(item -> item.getExpression() instanceof AllColumns)) {
                wildcard = true;
            }
            if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty())
                || select.getIntoTempTable() != null || select.getForClause() != null
                || select.getForMode() != null || select.getForUpdateTable() != null) {
                lockingOrInto = true;
            }
            return super.visit(select, context);
        }
    }
}
