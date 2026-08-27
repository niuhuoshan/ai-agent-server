package group.aitools.nhs.platform.data.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.service.ReadOnlySqlValidator.ValidatedSql;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RowPolicyRule;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies validated principal-bound row filters to a single SELECT AST. */
@Component
public class DatasetRowPolicySqlRewriter {

    private static final TypeReference<PolicyDocument> POLICY_TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final ReadOnlySqlValidator sqlValidator;

    public DatasetRowPolicySqlRewriter(JsonMapper jsonMapper, ReadOnlySqlValidator sqlValidator) {
        this.jsonMapper = jsonMapper;
        this.sqlValidator = sqlValidator;
    }

    public ValidatedSql apply(
        AgentDataDataset dataset,
        CurrentPrincipal principal,
        List<AgentDataTable> tables,
        List<AgentDataColumn> columns,
        ValidatedSql validated
    ) {
        if (!Boolean.TRUE.equals(dataset.getEnableRowPolicy())) {
            return validated;
        }
        PolicyDocument policy = policy(dataset.getRowPolicyJson());
        if (policy.rules().isEmpty()) {
            throw forbidden("数据集行级权限已启用但没有有效规则");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(validated.sql());
        } catch (JSQLParserException exception) {
            throw forbidden("行级权限无法解析已校验 SQL");
        }
        if (!(statement instanceof PlainSelect select)) {
            throw forbidden("启用行级权限的数据集暂不支持 UNION 或嵌套查询");
        }

        Map<Long, AgentDataTable> tableById = new HashMap<>();
        for (AgentDataTable table : tables) {
            tableById.put(table.getId(), table);
        }
        Map<Long, AgentDataColumn> columnById = new HashMap<>();
        for (AgentDataColumn column : columns) {
            columnById.put(column.getId(), column);
        }
        Map<Long, List<String>> referencedAliases = referencedAliases(select, tables);
        Expression guard = null;
        int appliedRules = 0;
        for (RowPolicyRule rule : policy.rules()) {
            AgentDataTable table = tableById.get(rule.tableId());
            AgentDataColumn column = columnById.get(rule.columnId());
            if (table == null || column == null || !column.getTableId().equals(table.getId())
                || !"active".equals(table.getStatus()) || !Boolean.TRUE.equals(table.getMetadataPresent())
                || !"active".equals(column.getStatus()) || !Boolean.TRUE.equals(column.getMetadataPresent())) {
                throw forbidden("行级权限引用了失效的表或字段");
            }
            List<String> aliases = referencedAliases.getOrDefault(rule.tableId(), List.of());
            for (String alias : aliases) {
                Expression condition = condition(rule, alias, column.getPhysicalName(), principal);
                guard = guard == null ? condition : new AndExpression(guard, condition);
                appliedRules++;
            }
        }
        if (appliedRules == 0) {
            return validated;
        }
        Expression current = select.getWhere();
        select.setWhere(current == null
            ? guard
            : new AndExpression(new ParenthesedExpressionList<>(current), guard));
        return sqlValidator.validate(statement.toString(), tables, columns);
    }

    private Map<Long, List<String>> referencedAliases(PlainSelect select, List<AgentDataTable> catalog) {
        Map<Long, List<String>> result = new HashMap<>();
        addTable(select.getFromItem(), catalog, result);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                addTable(join.getFromItem(), catalog, result);
            }
        }
        return result;
    }

    private void addTable(
        FromItem fromItem,
        List<AgentDataTable> catalog,
        Map<Long, List<String>> result
    ) {
        if (!(fromItem instanceof Table queryTable)) {
            throw forbidden("启用行级权限的数据集暂不支持派生表或嵌套查询");
        }
        String schema = unquote(queryTable.getUnquotedSchemaName());
        String name = unquote(queryTable.getUnquotedName());
        AgentDataTable matched = catalog.stream()
            .filter(table -> table.getPhysicalSchema().equalsIgnoreCase(schema)
                && table.getPhysicalName().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(() -> forbidden("行级权限无法匹配查询数据表"));
        String alias = queryTable.getAlias() == null
            ? matched.getPhysicalName() : unquote(queryTable.getAlias().getUnquotedName());
        result.computeIfAbsent(matched.getId(), ignored -> new ArrayList<>()).add(alias);
    }

    private Expression condition(RowPolicyRule rule, String alias, String columnName, CurrentPrincipal principal) {
        Column column = new Column(
            new Table(quoted(alias)),
            quoted(columnName)
        );
        Expression value = switch (rule.valueSource()) {
            case "principal_id" -> new LongValue(principal.id());
            case "principal_username" -> new StringValue(principal.username().replace("'", "''"));
            default -> throw forbidden("行级权限值来源无效");
        };
        return switch (rule.operator()) {
            case "eq" -> new EqualsTo(column, value);
            case "ne" -> new NotEqualsTo(column, value);
            default -> throw forbidden("行级权限操作符无效");
        };
    }

    private PolicyDocument policy(String value) {
        if (value == null || value.isBlank()) {
            return new PolicyDocument(List.of());
        }
        try {
            PolicyDocument document = jsonMapper.readValue(value, POLICY_TYPE);
            return document == null ? new PolicyDocument(List.of()) : document;
        } catch (RuntimeException exception) {
            throw forbidden("数据集行级权限配置损坏");
        }
    }

    private String quoted(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0) {
            throw forbidden("行级权限包含无效标识符");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("`") && result.endsWith("`")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("\"\"", "\"");
    }

    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    private record PolicyDocument(List<RowPolicyRule> rules) {
        private PolicyDocument {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
