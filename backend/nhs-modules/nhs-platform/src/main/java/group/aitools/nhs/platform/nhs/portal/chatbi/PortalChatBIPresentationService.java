package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责门户对话BIPresentation相关的业务编排与领域规则处理。
 * Validates persisted ChatBI presentation state and materializes bounded chart/pivot data. */
@Service
public class PortalChatBIPresentationService {

    private static final Set<String> CHART_TYPES = Set.of("none", "bar", "line", "pie");
    private static final Set<String> AGGREGATIONS = Set.of("sum", "avg", "min", "max", "count");
    private static final int MAX_CHART_CATEGORIES = 100;
    private static final int MAX_PIVOT_ROWS = 200;
    private static final int MAX_PIVOT_COLUMNS = 50;

    private final JsonMapper jsonMapper;

    public PortalChatBIPresentationService(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code defaults}并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    public Presentation defaults(List<String> columns, List<List<Object>> rows) {
        List<String> numeric = numericColumns(columns, rows);
        String dimension = columns.stream().filter(column -> !numeric.contains(column)).findFirst().orElse(null);
        if (dimension == null && columns.size() > 1 && !numeric.isEmpty()) {
            dimension = columns.stream().filter(column -> !column.equals(numeric.get(0))).findFirst().orElse(null);
        }
        String selectedDimension = dimension;
        List<String> measures = numeric.stream()
            .filter(column -> !column.equals(selectedDimension)).limit(2).toList();
        ChartConfig chart = measures.isEmpty()
            ? new ChartConfig("none", dimension, List.of(), "sum")
            : new ChartConfig(rows.size() > 30 ? "line" : "bar", dimension, measures, "sum");
        String pivotRow = dimension == null && !columns.isEmpty() ? columns.get(0) : dimension;
        String pivotValue = numeric.stream().filter(column -> !column.equals(pivotRow)).findFirst().orElse(null);
        PivotConfig pivot = new PivotConfig(
            pivotRow == null ? List.of() : List.of(pivotRow), null, pivotValue,
            pivotValue == null ? "count" : "sum"
        );
        return materialize(columns, rows, chart, pivot);
    }

    /**
     * 处理{@code materialize}并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @param chart {@code chart}参数
     * @param pivot {@code pivot}参数
     * @return 处理结果
     */
    public Presentation materialize(
        List<String> columns,
        List<List<Object>> rows,
        ChartConfig chart,
        PivotConfig pivot
    ) {
        requireColumns(columns);
        ChartConfig normalizedChart = normalizeChart(columns, chart);
        PivotConfig normalizedPivot = normalizePivot(columns, pivot);
        return new Presentation(
            normalizedChart,
            normalizedPivot,
            chartData(columns, rows, normalizedChart),
            pivotData(columns, rows, normalizedPivot)
        );
    }

    /**
     * 处理{@code parseChart}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    public ChartConfig parseChart(String json, List<String> columns, List<List<Object>> rows) {
        JsonNode root = object(json);
        if (root == null || root.isEmpty()) {
            return defaults(columns, rows).chart();
        }
        return normalizeChart(columns, new ChartConfig(
            text(root.get("type")), nullableText(root.get("dimension")),
            strings(root.get("measures"), 4), text(root.get("aggregation"))
        ));
    }

    /**
     * 处理{@code parsePivot}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    public PivotConfig parsePivot(String json, List<String> columns, List<List<Object>> rows) {
        JsonNode root = object(json);
        if (root == null || root.isEmpty()) {
            return defaults(columns, rows).pivot();
        }
        return normalizePivot(columns, new PivotConfig(
            strings(root.get("row_dimensions"), 3), nullableText(root.get("column_dimension")),
            nullableText(root.get("value_column")), text(root.get("aggregation"))
        ));
    }

    /**
     * 处理{@code chartJson}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    public String chartJson(ChartConfig config) {
        return jsonMapper.writeValueAsString(chartView(config));
    }

    /**
     * 处理{@code pivotJson}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    public String pivotJson(PivotConfig config) {
        return jsonMapper.writeValueAsString(pivotView(config));
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param presentation {@code presentation}参数
     * @return 处理结果
     */
    public Map<String, Object> view(Presentation presentation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("chart", chartView(presentation.chart()));
        value.put("pivot", pivotView(presentation.pivot()));
        value.put("chart_data", presentation.chartData());
        value.put("pivot_data", presentation.pivotData());
        return value;
    }

    /**
     * 处理{@code chartView}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    private Map<String, Object> chartView(ChartConfig config) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", config.type());
        value.put("dimension", config.dimension());
        value.put("measures", config.measures());
        value.put("aggregation", config.aggregation());
        return value;
    }

    /**
     * 处理{@code pivotView}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    private Map<String, Object> pivotView(PivotConfig config) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("row_dimensions", config.rowDimensions());
        value.put("column_dimension", config.columnDimension());
        value.put("value_column", config.valueColumn());
        value.put("aggregation", config.aggregation());
        return value;
    }

    /**
     * 处理{@code normalizeChart}并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param source 数据源参数
     * @return 处理结果
     */
    private ChartConfig normalizeChart(List<String> columns, ChartConfig source) {
        String type = normalize(source == null ? null : source.type(), "bar");
        if (!CHART_TYPES.contains(type)) {
            throw badRequest("图表类型只支持 none、bar、line 或 pie");
        }
        String aggregation = normalize(source == null ? null : source.aggregation(), "sum");
        requireAggregation(aggregation);
        String dimension = nullable(source == null ? null : source.dimension());
        requireColumn(columns, dimension, "图表维度");
        List<String> measures = distinct(source == null ? List.of() : source.measures(), 4, "图表指标");
        measures.forEach(measure -> requireColumn(columns, measure, "图表指标"));
        if (!"none".equals(type) && measures.isEmpty() && !"count".equals(aggregation)) {
            throw badRequest("非计数图表至少需要一个指标字段");
        }
        if ("pie".equals(type) && measures.size() > 1) {
            throw badRequest("饼图只能配置一个指标字段");
        }
        return new ChartConfig(type, dimension, measures, aggregation);
    }

    /**
     * 处理{@code normalizePivot}并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param source 数据源参数
     * @return 处理结果
     */
    private PivotConfig normalizePivot(List<String> columns, PivotConfig source) {
        String aggregation = normalize(source == null ? null : source.aggregation(), "count");
        requireAggregation(aggregation);
        List<String> rows = distinct(
            source == null ? List.of() : source.rowDimensions(), 3, "透视行维度"
        );
        if (rows.isEmpty()) {
            throw badRequest("透视表至少需要一个行维度");
        }
        rows.forEach(row -> requireColumn(columns, row, "透视行维度"));
        String column = nullable(source == null ? null : source.columnDimension());
        requireColumn(columns, column, "透视列维度");
        if (column != null && rows.contains(column)) {
            throw badRequest("透视列维度不能同时作为行维度");
        }
        String value = nullable(source == null ? null : source.valueColumn());
        requireColumn(columns, value, "透视值字段");
        if (!"count".equals(aggregation) && value == null) {
            throw badRequest("非计数透视表必须配置值字段");
        }
        return new PivotConfig(rows, column, value, aggregation);
    }

    /**
     * 处理chart数据并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @param config {@code config}参数
     * @return 处理结果
     */
    private Map<String, Object> chartData(
        List<String> columns,
        List<List<Object>> rows,
        ChartConfig config
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> value = new LinkedHashMap<>();
        if ("none".equals(config.type())) {
            value.put("categories", List.of());
            value.put("series", List.of());
            value.put("truncated", false);
            return value;
        }
        int dimensionIndex = config.dimension() == null ? -1 : columns.indexOf(config.dimension());
        List<Integer> measureIndexes = config.measures().stream().map(columns::indexOf).toList();
        Map<String, List<Aggregate>> groups = new LinkedHashMap<>();
        Map<String, Object> categoryValues = new LinkedHashMap<>();
        boolean truncated = false;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Object> row = rows.get(rowIndex);
            String category = dimensionIndex < 0 ? String.valueOf(rowIndex + 1) : label(cell(row, dimensionIndex));
            if (!groups.containsKey(category) && groups.size() >= MAX_CHART_CATEGORIES) {
                truncated = true;
                continue;
            }
            List<Aggregate> aggregates = groups.computeIfAbsent(
                category, ignored -> aggregates(config.measures().isEmpty() ? 1 : config.measures().size())
            );
            categoryValues.putIfAbsent(category, dimensionIndex < 0 ? rowIndex + 1 : cell(row, dimensionIndex));
            if (config.measures().isEmpty()) {
                aggregates.get(0).add(null, "count", "记录数");
            } else {
                for (int index = 0; index < measureIndexes.size(); index++) {
                    aggregates.get(index).add(
                        cell(row, measureIndexes.get(index)), config.aggregation(), config.measures().get(index)
                    );
                }
            }
        }
        List<String> categories = List.copyOf(groups.keySet());
        List<Map<String, Object>> series = new ArrayList<>();
        int seriesCount = config.measures().isEmpty() ? 1 : config.measures().size();
        for (int index = 0; index < seriesCount; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", config.measures().isEmpty() ? "记录数" : config.measures().get(index));
            int aggregateIndex = index;
            item.put("values", groups.values().stream()
                .map(aggregates -> aggregates.get(aggregateIndex).result(config.aggregation()))
                .toList());
            series.add(item);
        }
        value.put("categories", categories);
        value.put("category_values", categories.stream().map(categoryValues::get).toList());
        value.put("series", List.copyOf(series));
        value.put("truncated", truncated);
        return value;
    }

    /**
     * 处理pivot数据并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @param config {@code config}参数
     * @return 处理结果
     */
    private Map<String, Object> pivotData(
        List<String> columns,
        List<List<Object>> rows,
        PivotConfig config
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<Integer> rowIndexes = config.rowDimensions().stream().map(columns::indexOf).toList();
        int columnIndex = config.columnDimension() == null ? -1 : columns.indexOf(config.columnDimension());
        int valueIndex = config.valueColumn() == null ? -1 : columns.indexOf(config.valueColumn());
        Map<RowKey, Map<String, Aggregate>> groups = new LinkedHashMap<>();
        LinkedHashSet<String> columnKeys = new LinkedHashSet<>();
        boolean truncated = false;
        for (List<Object> row : rows) {
            RowKey key = new RowKey(rowIndexes.stream().map(index -> label(cell(row, index))).toList());
            if (!groups.containsKey(key) && groups.size() >= MAX_PIVOT_ROWS) {
                truncated = true;
                continue;
            }
            String columnKey = columnIndex < 0 ? "值" : label(cell(row, columnIndex));
            if (!columnKeys.contains(columnKey) && columnKeys.size() >= MAX_PIVOT_COLUMNS) {
                truncated = true;
                continue;
            }
            columnKeys.add(columnKey);
            Aggregate aggregate = groups.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(columnKey, ignored -> new Aggregate());
            aggregate.add(
                valueIndex < 0 ? null : cell(row, valueIndex), config.aggregation(),
                config.valueColumn() == null ? "记录数" : config.valueColumn()
            );
        }
        List<String> outputColumns = new ArrayList<>(config.rowDimensions());
        outputColumns.addAll(columnKeys);
        List<List<Object>> outputRows = new ArrayList<>();
        for (Map.Entry<RowKey, Map<String, Aggregate>> entry : groups.entrySet()) {
            List<Object> output = new ArrayList<>(entry.getKey().values());
            for (String column : columnKeys) {
                Aggregate aggregate = entry.getValue().get(column);
                output.add(aggregate == null ? null : aggregate.result(config.aggregation()));
            }
            outputRows.add(Collections.unmodifiableList(output));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("columns", List.copyOf(outputColumns));
        value.put("rows", List.copyOf(outputRows));
        value.put("row_count", outputRows.size());
        value.put("column_count", outputColumns.size());
        value.put("truncated", truncated);
        return value;
    }

    /**
     * 处理{@code numericColumns}并返回对应结果。
     *
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @return 符合条件的数据集合
     */
    private List<String> numericColumns(List<String> columns, List<List<Object>> rows) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> result = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            boolean seen = false;
            boolean numeric = true;
            for (List<Object> row : rows.stream().limit(200).toList()) {
                Object value = cell(row, index);
                if (value == null) {
                    continue;
                }
                seen = true;
                if (number(value) == null) {
                    numeric = false;
                    break;
                }
            }
            if (seen && numeric) {
                result.add(columns.get(index));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code aggregates}并返回对应结果。
     *
     * @param count {@code count}参数
     * @return 符合条件的数据集合
     */
    private List<Aggregate> aggregates(int count) {
        List<Aggregate> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new Aggregate());
        }
        return values;
    }

    /**
     * 处理{@code cell}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param index {@code index}参数
     * @return 处理结果
     */
    private Object cell(List<Object> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : null;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof String) {
            try {
                return new BigDecimal(String.valueOf(value).strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 处理{@code label}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String label(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "(空)";
        }
        String text = String.valueOf(value);
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    /**
     * 校验{@code Columns}，并在条件不满足时终止处理。
     *
     * @param columns {@code columns}参数
     */
    private void requireColumns(List<String> columns) {
        if (columns == null || columns.isEmpty() || columns.size() > 200) {
            throw new ServiceException("ChatBI 结果没有可展示的字段", HttpStatus.CONFLICT);
        }
        if (new LinkedHashSet<>(columns).size() != columns.size()) {
            throw new ServiceException("ChatBI 结果包含重复字段名，无法配置图表", HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验{@code Column}，并在条件不满足时终止处理。
     *
     * @param columns {@code columns}参数
     * @param column {@code column}参数
     * @param label {@code label}参数
     */
    private void requireColumn(List<String> columns, String column, String label) {
        if (column != null && !columns.contains(column)) {
            throw badRequest(label + "不在当前结果字段中：" + column);
        }
    }

    /**
     * 校验{@code Aggregation}，并在条件不满足时终止处理。
     *
     * @param aggregation {@code aggregation}参数
     */
    private void requireAggregation(String aggregation) {
        if (!AGGREGATIONS.contains(aggregation)) {
            throw badRequest("聚合方式只支持 sum、avg、min、max 或 count");
        }
    }

    /**
     * 处理{@code distinct}并返回对应结果。
     *
     * @param source 数据源参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<String> distinct(List<String> source, int max, String label) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : source == null ? List.<String>of() : source) {
            String normalized = nullable(value);
            if (normalized != null) {
                if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
                    throw badRequest(label + "字段名无效");
                }
                values.add(normalized);
            }
        }
        if (values.size() > max) {
            throw badRequest(label + "最多配置 " + max + " 个字段");
        }
        return List.copyOf(values);
    }

    /**
     * 处理{@code object}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private JsonNode object(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode value = jsonMapper.readTree(json);
        if (value == null || !value.isObject()) {
            throw new ServiceException("ChatBI 展示配置快照损坏", HttpStatus.ERROR);
        }
        return value;
    }

    /**
     * 处理{@code strings}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 符合条件的数据集合
     */
    private List<String> strings(JsonNode value, int max) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || result.size() >= max + 1) {
                throw new ServiceException("ChatBI 展示配置字段格式无效", HttpStatus.ERROR);
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /**
     * 处理{@code nullableText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : text(value);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String normalize(String value, String fallback) {
        String normalized = nullable(value);
        return normalized == null ? fallback : normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code nullable}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 封装{@code Chart}相关的不可变数据。
     */
    public record ChartConfig(String type, String dimension, List<String> measures, String aggregation) {
    }

    /**
     * 封装{@code Pivot}相关的不可变数据。
     */
    public record PivotConfig(
        List<String> rowDimensions,
        String columnDimension,
        String valueColumn,
        String aggregation
    ) {
    }

    /**
     * 封装{@code Presentation}相关的不可变数据。
     */
    public record Presentation(
        ChartConfig chart,
        PivotConfig pivot,
        Map<String, Object> chartData,
        Map<String, Object> pivotData
    ) {
    }

    /**
     * 封装{@code RowKey}相关的不可变数据。
     */
    private record RowKey(List<String> values) {
    }

    /**
     * 表示{@code Aggregate}相关的领域对象。
     */
    private final class Aggregate {
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;
        private long count;

        /**
         * 创建并保存{@code add}。
         *
         * @param value {@code value}参数
         * @param aggregation {@code aggregation}参数
         * @param field {@code field}参数
         */
        void add(Object value, String aggregation, String field) {
            if ("count".equals(aggregation)) {
                count++;
                return;
            }
            if (value == null) {
                return;
            }
            BigDecimal numeric = number(value);
            if (numeric == null) {
                throw badRequest("字段“" + field + "”包含非数值，不能使用 " + aggregation + " 聚合");
            }
            sum = sum.add(numeric);
            min = min == null || numeric.compareTo(min) < 0 ? numeric : min;
            max = max == null || numeric.compareTo(max) > 0 ? numeric : max;
            count++;
        }

        /**
         * 处理结果并返回对应结果。
         *
         * @param aggregation {@code aggregation}参数
         * @return 处理结果
         */
        Object result(String aggregation) {
            return switch (aggregation) {
                case "count" -> count;
                case "avg" -> count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
                case "min" -> min;
                case "max" -> max;
                default -> count == 0 ? null : sum.stripTrailingZeros();
            };
        }
    }
}
