package group.aitools.nhs.platform.data.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.CatalogDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.ColumnDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.RelationshipDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.TableDocument;

/** Deterministic CREATE TABLE parser used by metadata import previews. */
@Component
public class MetadataDdlParser {

    private final MetadataYamlCodec codec;

    public MetadataDdlParser(MetadataYamlCodec codec) {
        this.codec = codec;
    }

    public CatalogDocument parse(String content, String defaultSchema) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(content);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("DDL 无法解析：" + concise(exception.getMessage()), exception);
        }
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("DDL 至少需要一条 CREATE TABLE 语句");
        }
        List<TableDocument> tables = new ArrayList<>();
        List<RelationshipDocument> relationships = new ArrayList<>();
        for (Statement statement : statements) {
            if (!(statement instanceof CreateTable createTable)) {
                throw new IllegalArgumentException("DDL 导入只允许 CREATE TABLE 语句");
            }
            TableDocument parsedTable = table(createTable, defaultSchema);
            tables.add(parsedTable);
            relationships.addAll(foreignKeys(createTable, parsedTable));
        }
        return codec.normalizeAndValidate(new CatalogDocument(1, null, tables, List.of(), relationships));
    }

    private List<RelationshipDocument> foreignKeys(CreateTable createTable, TableDocument source) {
        List<RelationshipDocument> result = new ArrayList<>();
        if (createTable.getIndexes() == null) {
            return result;
        }
        for (Index index : createTable.getIndexes()) {
            if (!(index instanceof ForeignKeyIndex foreignKey)) {
                continue;
            }
            List<String> localColumns = foreignKey.getColumnsNames();
            List<String> referencedColumns = foreignKey.getReferencedColumnNames();
            if (foreignKey.getTable() == null || localColumns == null || referencedColumns == null
                || localColumns.isEmpty() || localColumns.size() != referencedColumns.size()) {
                throw new IllegalArgumentException("DDL 外键必须包含数量一致的本地字段和引用字段");
            }
            String targetSchema = optionalIdentifier(foreignKey.getTable().getUnquotedSchemaName());
            if (targetSchema == null || targetSchema.isBlank()) {
                targetSchema = source.schema();
            }
            String targetName = identifier(foreignKey.getTable().getUnquotedName());
            List<String> conditions = new ArrayList<>();
            for (int i = 0; i < localColumns.size(); i++) {
                String local = identifier(localColumns.get(i));
                String referenced = identifier(referencedColumns.get(i));
                conditions.add(quoteIdentifier(source.name()) + "." + quoteIdentifier(local)
                    + " = " + quoteIdentifier(targetName) + "." + quoteIdentifier(referenced));
            }
            String constraintName = foreignKey.getName();
            String description = constraintName == null || constraintName.isBlank()
                ? "DDL 外键约束" : "DDL 外键约束：" + constraintName;
            result.add(new RelationshipDocument(
                source.schema() + "." + source.name(),
                targetSchema + "." + targetName,
                "left",
                String.join(" AND ", conditions),
                description,
                "active"
            ));
        }
        return result;
    }

    private TableDocument table(CreateTable createTable, String defaultSchema) {
        if (createTable.getSelect() != null || createTable.getLikeTable() != null) {
            throw new IllegalArgumentException("DDL 导入不支持 CREATE TABLE AS 或 LIKE");
        }
        if (createTable.getTable() == null) {
            throw new IllegalArgumentException("CREATE TABLE 缺少表名");
        }
        String schema = optionalIdentifier(createTable.getTable().getUnquotedSchemaName());
        if (schema == null || schema.isBlank()) {
            schema = defaultSchema == null || defaultSchema.isBlank() ? "public" : defaultSchema.strip();
        }
        String name = identifier(createTable.getTable().getUnquotedName());
        List<ColumnDefinition> definitions = createTable.getColumnDefinitions();
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("CREATE TABLE " + schema + "." + name + " 至少需要一个字段");
        }
        Set<String> tablePrimaryKeys = primaryKeys(createTable.getIndexes());
        List<ColumnDocument> columns = new ArrayList<>();
        for (ColumnDefinition definition : definitions) {
            if (definition.getColDataType() == null) {
                throw new IllegalArgumentException("DDL 字段缺少数据类型：" + definition.getColumnName());
            }
            String columnName = identifier(definition.getColumnName());
            String specs = definition.getColumnSpecs() == null
                ? "" : String.join(" ", definition.getColumnSpecs()).toUpperCase(Locale.ROOT);
            boolean primary = specs.matches(".*\\bPRIMARY\\s+KEY\\b.*")
                || tablePrimaryKeys.contains(columnName.toLowerCase(Locale.ROOT));
            columns.add(new ColumnDocument(
                columnName,
                definition.getColDataType().toString(),
                columnName,
                inlineComment(definition.getColumnSpecs()),
                primary,
                false,
                "active",
                List.of(),
                List.of()
            ));
        }
        return new TableDocument(
            schema, name, name, null, "table", "active", List.of(), columns
        );
    }

    private Set<String> primaryKeys(List<Index> indexes) {
        Set<String> result = new HashSet<>();
        if (indexes == null) {
            return result;
        }
        for (Index index : indexes) {
            String type = index.getType() == null ? "" : index.getType().toUpperCase(Locale.ROOT);
            if (!type.contains("PRIMARY")) {
                continue;
            }
            for (String column : index.getColumnsNames()) {
                result.add(identifier(column).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private String inlineComment(List<String> specs) {
        if (specs == null) {
            return null;
        }
        for (int index = 0; index + 1 < specs.size(); index++) {
            if ("COMMENT".equalsIgnoreCase(specs.get(index))) {
                return stringLiteral(specs.get(index + 1));
            }
        }
        return null;
    }

    private String stringLiteral(String value) {
        if (value == null || value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) {
            return null;
        }
        return value.substring(1, value.length() - 1).replace("''", "'");
    }

    private String identifier(String value) {
        String unquoted = optionalIdentifier(value);
        if (unquoted == null || unquoted.isBlank()) {
            throw new IllegalArgumentException("DDL 标识符不能为空");
        }
        return unquoted;
    }

    private String optionalIdentifier(String value) {
        String unquoted = MultiPartName.unquote(value);
        return unquoted == null ? null
            : unquoted.replace("\"\"", "\"").replace("``", "`").strip();
    }

    private String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String concise(String value) {
        if (value == null || value.isBlank()) {
            return "格式错误";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }
}
