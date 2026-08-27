package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.service.MetadataProfileModelGateway.Analysis;
import group.aitools.nhs.platform.data.service.MetadataProfileModelGateway.ColumnSemantic;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnProfileView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SampleRowView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SampleValueView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads a bounded sample and asks the configured model for grounded business semantics. */
@Component
public class DataTableProfiler {

    static final int MAX_SAMPLE_ROWS = 3;
    static final int MAX_SAMPLE_COLUMNS = 32;
    static final int MAX_SAMPLE_FIELD_CHARS = 150;
    static final int MAX_SAMPLE_BINARY_BYTES = 96;
    static final int MAX_SAMPLE_JSON_BYTES = 8 * 1024;
    static final int MAX_PROFILE_COLUMNS = 1000;
    static final int MAX_DDL_CHARS = 60_000;

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
        "(?i).*(password|passwd|secret|token|api[_-]?key|authorization|credential|private[_-]?key"
            + "|access[_-]?key|refresh[_-]?token|email|e[_-]?mail|phone|mobile|id[_-]?card|ssn"
            + "|bank[_-]?card|card[_-]?no|address).*"
    );
    private static final Pattern TEMPORARY_NAME = Pattern.compile(
        "(?i)(^|[._])(tmp|temp|temporary)([._]|$)|([._])(tmp|temp)$"
    );
    private static final Pattern BACKUP_NAME = Pattern.compile(
        "(?i)(^|[._])(bak|backup|archive|old)([._]|$)|(bak|backup|old)$"
    );
    private static final Pattern STAGING_NAME = Pattern.compile(
        "(?i)(^|[._])(stg|stage|staging|work)([._]|$)"
    );

    private final ReadOnlyJdbcConnectionFactory connectionFactory;
    private final MetadataProfileModelGateway modelGateway;
    private final JsonMapper jsonMapper;

    public DataTableProfiler(
        ReadOnlyJdbcConnectionFactory connectionFactory,
        MetadataProfileModelGateway modelGateway,
        JsonMapper jsonMapper
    ) {
        this.connectionFactory = connectionFactory;
        this.modelGateway = modelGateway;
        this.jsonMapper = jsonMapper;
    }

    public ProfileResult profile(
        AgentDataSource source,
        AgentDataTable table,
        List<AgentDataColumn> rawColumns,
        String expectedSourceHash
    ) throws Exception {
        List<AgentDataColumn> columns = activeColumns(rawColumns);
        if (columns.isEmpty()) {
            throw new ServiceException("数据表没有活动字段，无法生成画像", 422);
        }
        if (columns.size() > MAX_PROFILE_COLUMNS) {
            throw new ServiceException("数据表字段超过1000个画像上限，请先缩小数据集范围", 422);
        }
        String currentHash = structureHash(table, columns);
        if (!currentHash.equals(expectedSourceHash)) {
            throw new ServiceException("表结构在画像排队后已变化，请重新创建画像任务", 409);
        }
        String ddl = ddl(table, columns);
        Sample sample = sample(source, table, columns);
        String sampleJson = jsonMapper.writeValueAsString(sample.rows());
        if (sampleJson.getBytes(StandardCharsets.UTF_8).length > MAX_SAMPLE_JSON_BYTES) {
            throw new IllegalStateException("画像样例超过8KB安全上限");
        }
        Analysis analysis = modelGateway.analyze(
            qualifiedName(table), ddl, sampleJson, columns
        );
        Classification classification = classification(table, analysis, sample.rows().isEmpty());
        Map<String, ColumnSemantic> semanticByName = new LinkedHashMap<>();
        analysis.columns().forEach(item -> semanticByName.put(item.physicalName(), item));
        List<ColumnProfileView> columnProfiles = columns.stream()
            .map(column -> columnProfile(column, semanticByName.get(column.getPhysicalName()), sample.rows()))
            .toList();
        List<String> tags = tags(analysis.tags(), table, classification.value());
        Map<String, Object> profileFacts = new LinkedHashMap<>();
        profileFacts.put("algorithm", "llm-grounded-v1");
        profileFacts.put("aiGenerated", true);
        profileFacts.put("modelId", analysis.modelId());
        profileFacts.put("modelName", analysis.modelName());
        profileFacts.put("providerType", analysis.providerType());
        profileFacts.put("sampleRowLimit", MAX_SAMPLE_ROWS);
        profileFacts.put("sampleColumnLimit", MAX_SAMPLE_COLUMNS);
        profileFacts.put("sampleFieldCharacterLimit", MAX_SAMPLE_FIELD_CHARS);
        profileFacts.put("sampleJsonByteLimit", MAX_SAMPLE_JSON_BYTES);
        profileFacts.put("rowCountMethod", "not_counted");
        return new ProfileResult(
            currentHash,
            table.getTableType(),
            analysis.tableTerm(),
            analysis.tableDescription(),
            ddl,
            null,
            columnProfiles,
            sample.rows(),
            sample.redacted(),
            BigDecimal.valueOf(classification.confidence()),
            classification.reason(),
            tags,
            classification.value(),
            classification.autoIgnored(),
            classification.autoIgnored() ? "auto_ignore" : "auto_include",
            jsonMapper.writeValueAsString(profileFacts)
        );
    }

    public static String structureHash(AgentDataTable table, List<AgentDataColumn> columns) {
        StringBuilder value = new StringBuilder();
        append(value, table.getId());
        append(value, table.getPhysicalSchema());
        append(value, table.getPhysicalName());
        append(value, table.getTableType());
        columns.stream()
            .sorted(Comparator.comparing(AgentDataColumn::getId))
            .forEach(column -> {
                append(value, column.getId());
                append(value, column.getPhysicalName());
                append(value, column.getDataType());
                append(value, column.getIsPrimary());
                append(value, column.getIsSensitive());
                append(value, column.getStatus());
                append(value, column.getMetadataPresent());
            });
        return ContentHashing.sha256(value.toString());
    }

    private static void append(StringBuilder target, Object value) {
        target.append(value == null ? "" : value).append('\0');
    }

    private List<AgentDataColumn> activeColumns(List<AgentDataColumn> columns) {
        return columns == null ? List.of() : columns.stream()
            .filter(column -> "active".equals(column.getStatus()))
            .filter(column -> Boolean.TRUE.equals(column.getMetadataPresent()))
            .sorted(Comparator.comparing(AgentDataColumn::getId))
            .toList();
    }

    private Sample sample(
        AgentDataSource source,
        AgentDataTable table,
        List<AgentDataColumn> columns
    ) throws Exception {
        List<AgentDataColumn> sampledColumns = columns.stream().limit(MAX_SAMPLE_COLUMNS).toList();
        List<SampleRowView> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.open(source);
             Statement controls = connection.createStatement();
             Statement statement = connection.createStatement()) {
            connectionFactory.prepareQuerySession(controls, source);
            statement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            statement.setFetchSize(MAX_SAMPLE_ROWS);
            statement.setMaxRows(MAX_SAMPLE_ROWS);
            try {
                statement.setMaxFieldSize(MAX_SAMPLE_FIELD_CHARS * 4);
            } catch (java.sql.SQLException ignored) {
                // Some drivers do not implement this hint; the per-cell bounds below still apply.
            }
            String query = sampleSql(
                DataSourceType.require(source.getDbType()), connection, table, sampledColumns
            );
            try (ResultSet result = statement.executeQuery(query)) {
                int rowNo = 0;
                while (rowNo < MAX_SAMPLE_ROWS && result.next()) {
                    List<SampleValueView> values = new ArrayList<>(sampledColumns.size());
                    for (int index = 0; index < sampledColumns.size(); index++) {
                        AgentDataColumn column = sampledColumns.get(index);
                        SampleCell cell = sampleCell(result.getObject(index + 1), sensitive(column));
                        values.add(new SampleValueView(
                            column.getId(), column.getPhysicalName(), column.getDisplayName(),
                            cell.value(), cell.valueType(), cell.redacted(), cell.truncated()
                        ));
                    }
                    SampleRowView row = new SampleRowView(++rowNo, values);
                    List<SampleRowView> candidate = new ArrayList<>(rows);
                    candidate.add(row);
                    if (jsonMapper.writeValueAsBytes(candidate).length > MAX_SAMPLE_JSON_BYTES) {
                        break;
                    }
                    rows.add(row);
                }
            }
            connectionFactory.rollback(connection, source);
        }
        boolean redacted = rows.stream().flatMap(row -> row.values().stream())
            .anyMatch(SampleValueView::redacted);
        return new Sample(List.copyOf(rows), redacted);
    }

    private String sampleSql(
        DataSourceType type,
        Connection connection,
        AgentDataTable table,
        List<AgentDataColumn> columns
    ) throws Exception {
        String rawQuote = connection.getMetaData().getIdentifierQuoteString();
        final String quote = rawQuote == null || rawQuote.isBlank() ? "\"" : rawQuote;
        String selected = columns.stream()
            .map(column -> identifier(column.getPhysicalName(), quote))
            .collect(java.util.stream.Collectors.joining(", "));
        String from = qualifiedIdentifier(table, quote);
        return switch (type) {
            case SQLSERVER -> "SELECT TOP (3) " + selected + " FROM " + from;
            case ORACLE -> "SELECT " + selected + " FROM " + from + " FETCH FIRST 3 ROWS ONLY";
            case POSTGRESQL, MYSQL, CLICKHOUSE -> "SELECT " + selected + " FROM " + from + " LIMIT 3";
        };
    }

    private String qualifiedIdentifier(AgentDataTable table, String quote) {
        String schema = table.getPhysicalSchema();
        String name = identifier(table.getPhysicalName(), quote);
        return schema == null || schema.isBlank()
            ? name : identifier(schema, quote) + "." + name;
    }

    private String identifier(String value, String quote) {
        if (value == null || value.isBlank() || value.length() > 255 || value.indexOf('\0') >= 0) {
            throw new ServiceException("元数据包含无效物理标识符", 422);
        }
        return quote + value.replace(quote, quote + quote) + quote;
    }

    private SampleCell sampleCell(Object raw, boolean redact) {
        if (raw == null) {
            return new SampleCell(null, "null", false, false);
        }
        if (redact) {
            return new SampleCell("[REDACTED]", valueType(raw), true, false);
        }
        if (raw instanceof byte[] bytes) {
            boolean truncated = bytes.length > MAX_SAMPLE_BINARY_BYTES;
            byte[] bounded = truncated ? Arrays.copyOf(bytes, MAX_SAMPLE_BINARY_BYTES) : bytes;
            String encoded = Base64.getEncoder().encodeToString(bounded);
            return new SampleCell(encoded, "binary", false, truncated);
        }
        String value = raw instanceof TemporalAccessor || raw instanceof java.util.Date || raw instanceof UUID
            ? raw.toString() : String.valueOf(raw);
        return boundedCell(value, valueType(raw));
    }

    private SampleCell boundedCell(String value, String type) {
        if (value.length() <= MAX_SAMPLE_FIELD_CHARS) {
            return new SampleCell(value, type, false, false);
        }
        return new SampleCell(value.substring(0, MAX_SAMPLE_FIELD_CHARS) + "...", type, false, true);
    }

    private String valueType(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof byte[]) {
            return "binary";
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date) {
            return "temporal";
        }
        return "string";
    }

    private boolean sensitive(AgentDataColumn column) {
        return Boolean.TRUE.equals(column.getIsSensitive())
            || SENSITIVE_NAME.matcher(column.getPhysicalName()).matches();
    }

    private ColumnProfileView columnProfile(
        AgentDataColumn column,
        ColumnSemantic semantic,
        List<SampleRowView> rows
    ) {
        if (semantic == null) {
            throw new IllegalStateException("模型字段画像缺失：" + column.getPhysicalName());
        }
        int nonNull = 0;
        Set<String> distinct = new LinkedHashSet<>();
        List<String> examples = new ArrayList<>();
        for (SampleRowView row : rows) {
            SampleValueView value = row.values().stream()
                .filter(item -> column.getId().equals(item.columnId()))
                .findFirst().orElse(null);
            if (value == null || value.value() == null) {
                continue;
            }
            nonNull++;
            if (!value.redacted()) {
                distinct.add(value.value());
                if (!examples.contains(value.value())) {
                    examples.add(value.value());
                }
            }
        }
        return new ColumnProfileView(
            column.getId(), column.getPhysicalName(), column.getDisplayName(),
            semantic.term(), semantic.description(), column.getDataType(),
            Boolean.TRUE.equals(column.getIsPrimary()), sensitive(column),
            nonNull, distinct.size(), examples.stream().limit(3).toList()
        );
    }

    private Classification classification(
        AgentDataTable table,
        Analysis analysis,
        boolean emptySample
    ) {
        String qualified = qualifiedName(table);
        String value = analysis.temporaryClassification();
        String safetyReason = null;
        if (systemSchema(table.getPhysicalSchema())) {
            value = "system";
            safetyReason = "系统Schema安全规则";
        } else if (TEMPORARY_NAME.matcher(qualified).find()) {
            value = "temporary";
            safetyReason = "临时表命名安全规则";
        } else if (BACKUP_NAME.matcher(qualified).find()) {
            value = "backup";
            safetyReason = "备份表命名安全规则";
        } else if (STAGING_NAME.matcher(qualified).find() && "business".equals(value)) {
            value = "staging";
            safetyReason = "暂存表命名安全规则";
        }
        int confidence = analysis.confidenceScore();
        List<String> reasons = new ArrayList<>();
        reasons.add(analysis.confidenceReason());
        if (emptySample) {
            confidence = Math.max(0, confidence - 30);
            reasons.add("样例数据为空，降低30分");
        }
        if (safetyReason != null) {
            confidence = Math.min(confidence, switch (value) {
                case "system" -> 20;
                case "temporary", "backup" -> 50;
                default -> confidence;
            });
            reasons.add(safetyReason);
        }
        boolean ignored = Set.of("temporary", "backup", "system").contains(value);
        String reason = String.join("；", reasons);
        return new Classification(
            value, confidence, ignored,
            reason.length() <= 1000 ? reason : reason.substring(0, 1000)
        );
    }

    private boolean systemSchema(String schema) {
        String value = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        return Set.of("information_schema", "pg_catalog", "sys", "mysql", "performance_schema")
            .contains(value);
    }

    private List<String> tags(List<String> modelTags, AgentDataTable table, String classification) {
        LinkedHashSet<String> result = new LinkedHashSet<>(modelTags);
        if (!"business".equals(classification)) {
            result.add(switch (classification) {
                case "temporary" -> "临时表";
                case "backup" -> "备份表";
                case "staging" -> "暂存表";
                case "system" -> "系统表";
                default -> classification;
            });
        }
        if (table.getTableType() != null && table.getTableType().toLowerCase(Locale.ROOT).contains("view")) {
            result.add("视图");
        }
        return result.stream().limit(10).toList();
    }

    private String ddl(AgentDataTable table, List<AgentDataColumn> columns) {
        StringBuilder result = new StringBuilder("CREATE TABLE ")
            .append(qualifiedName(table)).append(" (\n");
        for (int index = 0; index < columns.size(); index++) {
            AgentDataColumn column = columns.get(index);
            result.append("  ").append(column.getPhysicalName()).append(' ')
                .append(column.getDataType());
            if (Boolean.TRUE.equals(column.getIsPrimary())) {
                result.append(" PRIMARY KEY");
            }
            if (index + 1 < columns.size()) {
                result.append(',');
            }
            result.append('\n');
        }
        result.append(");");
        if (result.length() > MAX_DDL_CHARS) {
            return result.substring(0, MAX_DDL_CHARS - 20) + "\n-- [truncated]";
        }
        return result.toString();
    }

    private String qualifiedName(AgentDataTable table) {
        String schema = table.getPhysicalSchema();
        return schema == null || schema.isBlank()
            ? table.getPhysicalName() : schema + "." + table.getPhysicalName();
    }

    private int seconds(Integer milliseconds) {
        return Math.max(1, ((milliseconds == null ? 15_000 : milliseconds) + 999) / 1000);
    }

    private record Sample(List<SampleRowView> rows, boolean redacted) {
    }

    private record SampleCell(String value, String valueType, boolean redacted, boolean truncated) {
    }

    private record Classification(
        String value,
        int confidence,
        boolean autoIgnored,
        String reason
    ) {
    }

    public record ProfileResult(
        String sourceHash,
        String tableType,
        String term,
        String description,
        String ddl,
        Long rowCountEstimate,
        List<ColumnProfileView> columns,
        List<SampleRowView> samples,
        boolean sampleRedacted,
        BigDecimal confidenceScore,
        String confidenceReason,
        List<String> tags,
        String temporaryClassification,
        boolean ignored,
        String ignoreDecision,
        String profileJson
    ) {
        public ProfileResult {
            columns = List.copyOf(columns);
            samples = List.copyOf(samples);
            tags = List.copyOf(tags);
        }
    }
}
