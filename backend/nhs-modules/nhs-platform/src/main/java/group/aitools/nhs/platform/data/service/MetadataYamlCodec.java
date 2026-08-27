package group.aitools.nhs.platform.data.service;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict, deterministic codec for the platform metadata YAML contract. */
@Component
public class MetadataYamlCodec {

    public static final int DOCUMENT_VERSION = 1;
    public static final int MAX_TABLES = 500;
    public static final int MAX_COLUMNS = 10_000;
    public static final int MAX_METRICS = 2_000;
    public static final int MAX_RELATIONSHIPS = 5_000;

    static final int MAX_DOCUMENT_LENGTH = 2_000_000;
    static final int MAX_STRING_LENGTH = 100_000;
    static final int MAX_FIELD_NAME_LENGTH = 256;
    static final int MAX_NESTING_DEPTH = 64;
    static final int MAX_TOKEN_COUNT = 300_000;
    static final int MAX_YAML_ALIASES = 0;

    private static final Comparator<String> TEXT_ORDER = String.CASE_INSENSITIVE_ORDER
        .thenComparing(Comparator.naturalOrder());

    private final YAMLMapper yamlMapper;

    @Autowired
    public MetadataYamlCodec() {
        this(defaultMapper());
    }

    private static YAMLMapper defaultMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
            .maxDocumentLength(MAX_DOCUMENT_LENGTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .maxNameLength(MAX_FIELD_NAME_LENGTH)
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxTokenCount(MAX_TOKEN_COUNT)
            .maxNumberLength(100)
            .build();
        LoadSettings loadSettings = LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setAllowNonScalarKeys(false)
            .setMaxAliasesForCollections(MAX_YAML_ALIASES)
            .setCodePointLimit(MAX_DOCUMENT_LENGTH)
            .setParseComments(false)
            .build();
        YAMLFactory factory = YAMLFactory.builder()
            .streamReadConstraints(constraints)
            .loadSettings(loadSettings)
            .build();
        return YAMLMapper.builder(factory)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .disable(YAMLWriteFeature.SPLIT_LINES)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
            .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
            .build();
    }

    MetadataYamlCodec(YAMLMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    public CatalogDocument parse(String content) {
        try {
            CatalogDocument parsed = yamlMapper.readValue(content, CatalogDocument.class);
            if (parsed == null) {
                throw new IllegalArgumentException("YAML 元数据不能为空");
            }
            return normalizeAndValidate(parsed);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("YAML 元数据无法解析：" + concise(exception.getMessage()), exception);
        }
    }

    public String write(CatalogDocument document) {
        CatalogDocument normalized = normalizeAndValidate(document);
        try {
            String yaml = yamlMapper.writeValueAsString(normalized);
            return yaml.endsWith("\n") ? yaml : yaml + '\n';
        } catch (JacksonException exception) {
            throw new IllegalStateException("YAML 元数据生成失败", exception);
        }
    }

    public CatalogDocument normalizeAndValidate(CatalogDocument document) {
        if (document.version() != DOCUMENT_VERSION) {
            throw new IllegalArgumentException("仅支持 version: 1 的元数据 YAML");
        }
        DatasetDocument dataset = document.dataset() == null ? null : normalizeDataset(document.dataset());
        List<TableDocument> tables = document.tables().stream().map(this::normalizeTable)
            .sorted(Comparator.comparing(MetadataYamlCodec::qualifiedName, TEXT_ORDER)).toList();
        List<MetricDocument> metrics = document.metrics().stream().map(this::normalizeMetric)
            .sorted(Comparator.comparing(MetricDocument::key, TEXT_ORDER)).toList();
        List<RelationshipDocument> relationships = document.relationships().stream()
            .map(this::normalizeRelationship)
            .sorted(Comparator.comparing(MetadataYamlCodec::relationshipKey, TEXT_ORDER)).toList();

        if (tables.size() > MAX_TABLES) {
            throw new IllegalArgumentException("元数据导入最多包含 " + MAX_TABLES + " 张表");
        }
        int columnCount = tables.stream().mapToInt(table -> table.columns().size()).sum();
        if (columnCount > MAX_COLUMNS) {
            throw new IllegalArgumentException("元数据导入最多包含 " + MAX_COLUMNS + " 个字段");
        }
        if (metrics.size() > MAX_METRICS) {
            throw new IllegalArgumentException("元数据导入最多包含 " + MAX_METRICS + " 个指标");
        }
        if (relationships.size() > MAX_RELATIONSHIPS) {
            throw new IllegalArgumentException("元数据导入最多包含 " + MAX_RELATIONSHIPS + " 条关系");
        }
        requireUnique(tables.stream().map(MetadataYamlCodec::qualifiedName).toList(), "数据表");
        requireUnique(metrics.stream().map(MetricDocument::key).toList(), "指标标识");
        requireUnique(relationships.stream().map(MetadataYamlCodec::relationshipKey).toList(), "数据关系");
        return new CatalogDocument(DOCUMENT_VERSION, dataset, tables, metrics, relationships);
    }

    public static String qualifiedName(TableDocument table) {
        return table.schema() + "." + table.name();
    }

    public static String relationshipKey(RelationshipDocument relationship) {
        return relationship.sourceTable() + "->" + relationship.targetTable()
            + "|" + relationship.joinType() + "|" + relationship.joinCondition();
    }

    private DatasetDocument normalizeDataset(DatasetDocument value) {
        return new DatasetDocument(
            required(value.key(), "dataset.key", 128),
            required(value.name(), "dataset.name", 255),
            optional(value.description(), 4_000)
        );
    }

    private TableDocument normalizeTable(TableDocument value) {
        String schema = required(value.schema(), "tables[].schema", 255);
        String name = required(value.name(), "tables[].name", 255);
        String displayName = defaulted(value.displayName(), name, 255);
        String type = defaultedLower(value.type(), "table", 24);
        String status = status(value.status(), "tables[].status");
        List<String> synonyms = strings(value.synonyms(), 100, 255, "tables[].synonyms");
        List<ColumnDocument> columns = value.columns().stream().map(this::normalizeColumn)
            .sorted(Comparator.comparing(ColumnDocument::name, TEXT_ORDER)).toList();
        requireUnique(columns.stream().map(ColumnDocument::name).toList(), "数据表 " + schema + "." + name + " 的字段");
        return new TableDocument(
            schema, name, displayName, optional(value.description(), 4_000), type,
            status, synonyms, columns
        );
    }

    private ColumnDocument normalizeColumn(ColumnDocument value) {
        String name = required(value.name(), "columns[].name", 255);
        return new ColumnDocument(
            name,
            required(value.type(), "columns[].type", 128),
            defaulted(value.displayName(), name, 255),
            optional(value.description(), 4_000),
            Boolean.TRUE.equals(value.primary()),
            Boolean.TRUE.equals(value.sensitive()),
            status(value.status(), "columns[].status"),
            strings(value.enums(), 1_000, 1_000, "columns[].enums"),
            strings(value.synonyms(), 100, 255, "columns[].synonyms")
        );
    }

    private MetricDocument normalizeMetric(MetricDocument value) {
        String key = required(value.key(), "metrics[].key", 128).toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("指标标识格式无效：" + key);
        }
        return new MetricDocument(
            key,
            required(value.name(), "metrics[].name", 255),
            optional(value.description(), 4_000),
            required(value.calculationLogic(), "metrics[].calculationLogic", 8_000),
            optional(value.unit(), 64),
            status(value.status(), "metrics[].status")
        );
    }

    private RelationshipDocument normalizeRelationship(RelationshipDocument value) {
        String joinType = defaultedLower(value.joinType(), "left", 16);
        if (!Set.of("inner", "left", "right", "full").contains(joinType)) {
            throw new IllegalArgumentException("关系 joinType 仅支持 inner、left、right 或 full");
        }
        return new RelationshipDocument(
            qualifiedReference(value.sourceTable(), "relationships[].sourceTable"),
            qualifiedReference(value.targetTable(), "relationships[].targetTable"),
            joinType,
            required(value.joinCondition(), "relationships[].joinCondition", 4_000),
            optional(value.description(), 4_000),
            status(value.status(), "relationships[].status")
        );
    }

    private String qualifiedReference(String value, String field) {
        String result = required(value, field, 511);
        int separator = result.lastIndexOf('.');
        if (separator <= 0 || separator == result.length() - 1) {
            throw new IllegalArgumentException(field + " 必须使用 schema.table 格式");
        }
        return result;
    }

    private String status(String value, String field) {
        String status = defaultedLower(value, "active", 16);
        if (!Set.of("active", "inactive").contains(status)) {
            throw new IllegalArgumentException(field + " 仅支持 active 或 inactive");
        }
        return status;
    }

    private List<String> strings(List<String> values, int maxCount, int maxLength, String field) {
        if (values.size() > maxCount) {
            throw new IllegalArgumentException(field + " 数量不能超过 " + maxCount);
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String item = required(value, field, maxLength);
            if (unique.add(item.toLowerCase(Locale.ROOT))) {
                result.add(item);
            }
        }
        result.sort(TEXT_ORDER);
        return List.copyOf(result);
    }

    private void requireUnique(List<String> values, String label) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (!unique.add(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(label + "不能重复：" + value);
            }
        }
    }

    private String required(String value, String field, int maxLength) {
        String result = optional(value, maxLength);
        if (result == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return result;
    }

    private String defaulted(String value, String fallback, int maxLength) {
        String result = optional(value, maxLength);
        return result == null ? fallback : result;
    }

    private String defaultedLower(String value, String fallback, int maxLength) {
        return defaulted(value, fallback, maxLength).toLowerCase(Locale.ROOT);
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.strip();
        if (result.length() > maxLength) {
            throw new IllegalArgumentException("元数据文本长度不能超过 " + maxLength);
        }
        return result;
    }

    private String concise(String value) {
        if (value == null || value.isBlank()) {
            return "格式错误";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }

    public record CatalogDocument(
        Integer version,
        DatasetDocument dataset,
        List<TableDocument> tables,
        List<MetricDocument> metrics,
        List<RelationshipDocument> relationships
    ) {
        public CatalogDocument {
            version = version == null ? 0 : version;
            tables = tables == null ? List.of() : List.copyOf(tables);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            relationships = relationships == null ? List.of() : List.copyOf(relationships);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的 YAML 顶层字段：" + field);
        }
    }

    public record DatasetDocument(String key, String name, String description) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的数据集 YAML 字段：" + field);
        }
    }

    public record TableDocument(
        String schema,
        String name,
        String displayName,
        String description,
        String type,
        String status,
        List<String> synonyms,
        List<ColumnDocument> columns
    ) {
        public TableDocument {
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的数据表 YAML 字段：" + field);
        }
    }

    public record ColumnDocument(
        String name,
        String type,
        String displayName,
        String description,
        Boolean primary,
        Boolean sensitive,
        String status,
        List<String> enums,
        List<String> synonyms
    ) {
        public ColumnDocument {
            primary = Boolean.TRUE.equals(primary);
            sensitive = Boolean.TRUE.equals(sensitive);
            enums = enums == null ? List.of() : List.copyOf(enums);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的数据列 YAML 字段：" + field);
        }
    }

    public record MetricDocument(
        String key,
        String name,
        String description,
        String calculationLogic,
        String unit,
        String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的指标 YAML 字段：" + field);
        }
    }

    public record RelationshipDocument(
        String sourceTable,
        String targetTable,
        String joinType,
        String joinCondition,
        String description,
        String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的关系 YAML 字段：" + field);
        }
    }
}
