package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示Sql工具模板Engine相关的领域对象。
 * Validates and renders governed SQL-tool templates without accepting raw SQL fragments. */
@Component
public class SqlToolTemplateEngine {

    private static final int MAX_SQL_BYTES = 64 * 1024;
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern DATASET_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Set<String> VALUE_TYPES = Set.of(
        "string", "number", "integer", "boolean", "array"
    );

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param parameterSchema {@code parameterSchema}参数
     * @param executionPolicy 执行策略参数
     * @return 处理结果
     */
    public Configuration validate(
        Map<String, Object> parameterSchema,
        Map<String, Object> executionPolicy
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> schema = parameterSchema == null ? Map.of() : parameterSchema;
        Map<String, Object> policy = executionPolicy == null ? Map.of() : executionPolicy;
        if (!"object".equals(schema.get("type"))) {
            throw badRequest("SQL 工具参数 Schema 根类型必须是 object");
        }
        if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw badRequest("SQL 工具参数 Schema 必须禁止未声明字段");
        }

        Map<String, Map<String, Object>> properties = properties(schema.get("properties"));
        Set<String> required = required(schema.get("required"));
        if (!required.equals(properties.keySet())) {
            throw badRequest("SQL 模板使用的参数必须全部声明为必填参数");
        }
        properties.forEach(this::validateProperty);

        Long datasetId = datasetId(policy.get("datasetId"));
        String sqlTemplate = requiredText(policy.get("sqlTemplate"), "SQL 模板", MAX_SQL_BYTES);
        String queryPurpose = requiredText(policy.get("queryPurpose"), "查询用途", 1000);
        if (!Boolean.TRUE.equals(policy.get("readOnly"))) {
            throw badRequest("SQL 工具必须声明为只读执行");
        }
        Map<String, Object> examples = new LinkedHashMap<>();
        properties.forEach((name, property) -> examples.put(name, sampleValue(property)));
        Rendered rendered = render(sqlTemplate, properties, examples);
        if (!rendered.parameters().equals(properties.keySet())) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(properties.keySet());
            missing.removeAll(rendered.parameters());
            throw badRequest("SQL 工具存在未用于模板的参数：" + missing);
        }
        return new Configuration(
            datasetId, sqlTemplate, queryPurpose, Map.copyOf(properties), rendered.sql()
        );
    }

    /**
     * 处理{@code render}并返回对应结果。
     *
     * @param configuration 配置参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public String render(Configuration configuration, Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        if (!input.keySet().equals(configuration.properties().keySet())) {
            throw badRequest("SQL 工具参数与模板声明不一致");
        }
        return render(configuration.sqlTemplate(), configuration.properties(), input).sql();
    }

    /**
     * 处理{@code render}并返回对应结果。
     *
     * @param template 模板参数
     * @param properties {@code properties}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Rendered render(
        String template,
        Map<String, Map<String, Object>> properties,
        Map<String, Object> arguments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        StringBuilder sql = new StringBuilder(template.length() + 64);
        LinkedHashSet<String> used = new LinkedHashSet<>();
        State state = State.NORMAL;
        String dollarDelimiter = null;
        int blockDepth = 0;
        int index = 0;
        while (index < template.length()) {
            if (template.startsWith("{{", index)) {
                if (state != State.NORMAL) {
                    throw badRequest("SQL 参数占位符不能写在字符串、标识符或注释中");
                }
                int end = template.indexOf("}}", index + 2);
                if (end < 0) {
                    throw badRequest("SQL 参数占位符没有闭合");
                }
                String name = template.substring(index + 2, end).strip();
                if (!PARAMETER_NAME.matcher(name).matches() || !properties.containsKey(name)) {
                    throw badRequest("SQL 模板引用了未声明参数：" + name);
                }
                if (!arguments.containsKey(name)) {
                    throw badRequest("SQL 工具参数缺失：" + name);
                }
                sql.append(literal(arguments.get(name), properties.get(name), name));
                used.add(name);
                index = end + 2;
                continue;
            }
            if (template.startsWith("}}", index)) {
                throw badRequest("SQL 参数占位符格式无效");
            }

            char current = template.charAt(index);
            if (state == State.NORMAL) {
                if (template.startsWith("--", index)) {
                    sql.append("--");
                    state = State.LINE_COMMENT;
                    index += 2;
                    continue;
                }
                if (template.startsWith("/*", index)) {
                    sql.append("/*");
                    state = State.BLOCK_COMMENT;
                    blockDepth = 1;
                    index += 2;
                    continue;
                }
                String delimiter = dollarDelimiter(template, index);
                if (delimiter != null) {
                    sql.append(delimiter);
                    state = State.DOLLAR_QUOTE;
                    dollarDelimiter = delimiter;
                    index += delimiter.length();
                    continue;
                }
                if (current == '\'') {
                    state = State.SINGLE_QUOTE;
                } else if (current == '"') {
                    state = State.DOUBLE_QUOTE;
                } else if (current == '`') {
                    state = State.BACKTICK;
                }
                sql.append(current);
                index++;
                continue;
            }
            if (state == State.LINE_COMMENT) {
                sql.append(current);
                index++;
                if (current == '\n' || current == '\r') {
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.BLOCK_COMMENT) {
                if (template.startsWith("/*", index)) {
                    sql.append("/*");
                    blockDepth++;
                    index += 2;
                } else if (template.startsWith("*/", index)) {
                    sql.append("*/");
                    blockDepth--;
                    index += 2;
                    if (blockDepth == 0) {
                        state = State.NORMAL;
                    }
                } else {
                    sql.append(current);
                    index++;
                }
                continue;
            }
            if (state == State.DOLLAR_QUOTE) {
                if (template.startsWith(dollarDelimiter, index)) {
                    sql.append(dollarDelimiter);
                    index += dollarDelimiter.length();
                    state = State.NORMAL;
                    dollarDelimiter = null;
                } else {
                    sql.append(current);
                    index++;
                }
                continue;
            }

            sql.append(current);
            index++;
            char quote = state == State.SINGLE_QUOTE ? '\''
                : state == State.DOUBLE_QUOTE ? '"' : '`';
            if (current == quote) {
                if (index < template.length() && template.charAt(index) == quote) {
                    sql.append(quote);
                    index++;
                } else {
                    state = State.NORMAL;
                }
            }
        }
        if (sql.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw badRequest("渲染后的 SQL 超过 64KB 限制");
        }
        return new Rendered(sql.toString(), Set.copyOf(used));
    }

    /**
     * 处理{@code properties}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Map<String, Object>> properties(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.size() > 128) {
            throw badRequest("SQL 工具参数 Schema properties 无效");
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!PARAMETER_NAME.matcher(name).matches() || !(entry.getValue() instanceof Map<?, ?> property)) {
                throw badRequest("SQL 工具参数定义无效：" + name);
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            property.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            result.put(name, Map.copyOf(normalized));
        }
        return Map.copyOf(result);
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private Set<String> required(Object value) {
        if (!(value instanceof List<?> list)) {
            throw badRequest("SQL 工具参数 Schema required 无效");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String name) || !PARAMETER_NAME.matcher(name).matches()
                || !result.add(name)) {
                throw badRequest("SQL 工具必填参数定义无效");
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 校验{@code Property}，并在条件不满足时终止处理。
     *
     * @param name 名称
     * @param property {@code property}参数
     */
    private void validateProperty(String name, Map<String, Object> property) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Object rawType = property.get("type");
        if (!(rawType instanceof String type) || !VALUE_TYPES.contains(type)) {
            throw badRequest("SQL 工具参数类型不受支持：" + name);
        }
        if ("array".equals(type)) {
            if (!(property.get("items") instanceof Map<?, ?> rawItems)) {
                throw badRequest("SQL 工具列表参数缺少 items：" + name);
            }
            Object itemType = rawItems.get("type");
            if (!(itemType instanceof String text)
                || !Set.of("string", "number", "integer", "boolean").contains(text)) {
                throw badRequest("SQL 工具列表参数 items 类型不受支持：" + name);
            }
        }
    }

    /**
     * 处理{@code sampleValue}并返回对应结果。
     *
     * @param property {@code property}参数
     * @return 处理结果
     */
    private Object sampleValue(Map<String, Object> property) {
        if (property.get("enum") instanceof List<?> values && !values.isEmpty()) {
            return values.getFirst();
        }
        return switch (String.valueOf(property.get("type"))) {
            case "string" -> "sample";
            case "number" -> BigDecimal.ONE;
            case "integer" -> 1L;
            case "boolean" -> true;
            case "array" -> {
                Map<String, Object> items = stringMap((Map<?, ?>) property.get("items"));
                yield List.of(sampleValue(items));
            }
            default -> throw badRequest("SQL 工具参数类型不受支持");
        };
    }

    /**
     * 处理{@code literal}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param property {@code property}参数
     * @param name 名称
     * @return 处理结果
     */
    private String literal(Object value, Map<String, Object> property, String name) {
        if (value == null) {
            throw badRequest("SQL 工具参数不能为 null：" + name);
        }
        String type = String.valueOf(property.get("type"));
        return switch (type) {
            case "string" -> quotedString(value, name);
            case "number" -> decimal(value, false, name);
            case "integer" -> decimal(value, true, name);
            case "boolean" -> {
                if (!(value instanceof Boolean bool)) {
                    throw badRequest("SQL 工具参数类型不匹配：" + name);
                }
                yield bool ? "TRUE" : "FALSE";
            }
            case "array" -> arrayLiteral(value, property, name);
            default -> throw badRequest("SQL 工具参数类型不受支持：" + name);
        };
    }

    /**
     * 处理{@code arrayLiteral}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param property {@code property}参数
     * @param name 名称
     * @return 处理结果
     */
    private String arrayLiteral(Object value, Map<String, Object> property, String name) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw badRequest("SQL 工具列表参数不能为空：" + name);
        }
        Map<String, Object> items = stringMap((Map<?, ?>) property.get("items"));
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(literal(item, items, name));
        }
        return String.join(", ", values);
    }

    /**
     * 处理{@code quotedString}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @return 处理结果
     */
    private String quotedString(Object value, String name) {
        if (!(value instanceof String text) || text.indexOf('\0') >= 0) {
            throw badRequest("SQL 工具参数类型不匹配：" + name);
        }
        if (text.indexOf('\\') >= 0) {
            throw badRequest("SQL 文本参数不能包含跨数据库不安全的反斜杠：" + name);
        }
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * 处理{@code decimal}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param integer {@code integer}参数
     * @param name 名称
     * @return 处理结果
     */
    private String decimal(Object value, boolean integer, String name) {
        if (!(value instanceof Number number)) {
            throw badRequest("SQL 工具参数类型不匹配：" + name);
        }
        try {
            BigDecimal decimal = number instanceof BigDecimal valueDecimal ? valueDecimal
                : number instanceof BigInteger valueInteger ? new BigDecimal(valueInteger)
                : new BigDecimal(number.toString());
            if (integer) {
                decimal = new BigDecimal(decimal.toBigIntegerExact());
            }
            return decimal.stripTrailingZeros().toPlainString();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw badRequest("SQL 工具数值参数无效：" + name);
        }
    }

    /**
     * 处理数据集Id并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long datasetId(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String text;
        if (value instanceof String raw) {
            text = raw.strip();
        } else if (value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            text = value.toString();
        } else {
            throw badRequest("SQL 工具必须绑定有效数据集");
        }
        if (!DATASET_ID.matcher(text).matches()) {
            throw badRequest("SQL 工具必须绑定有效数据集");
        }
        try {
            long id = Long.parseLong(text);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw badRequest("SQL 工具必须绑定有效数据集");
        }
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximumBytes {@code maximumBytes}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label, int maximumBytes) {
        if (!(value instanceof String text)) {
            throw badRequest(label + "不能为空");
        }
        String normalized = text.strip();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0
            || normalized.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    /**
     * 处理{@code dollarDelimiter}并返回对应结果。
     *
     * @param sql {@code sql}参数
     * @param index {@code index}参数
     * @return 处理结果
     */
    private String dollarDelimiter(String sql, int index) {
        if (sql.charAt(index) != '$') {
            return null;
        }
        int end = sql.indexOf('$', index + 1);
        if (end < 0 || end - index > 65) {
            return null;
        }
        String tag = sql.substring(index + 1, end);
        if (!tag.isEmpty() && !PARAMETER_NAME.matcher(tag).matches()) {
            return null;
        }
        return sql.substring(index, end + 1);
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
     * 封装配置相关的不可变数据。
     */
    public record Configuration(
        Long datasetId,
        String sqlTemplate,
        String queryPurpose,
        Map<String, Map<String, Object>> properties,
        String validationSql
    ) {

        /**
         * 创建 {@code Configuration} 实例并初始化所需依赖。
         *
         * @param datasetId 资源标识
         * @param sqlTemplate sql模板参数
         * @param queryPurpose 查询Purpose参数
         * @param properties {@code properties}参数
         * @param validationSql {@code validationSql}参数
         */
        public Configuration {
            properties = Map.copyOf(properties);
        }
    }

    /**
     * 封装{@code Rendered}相关的不可变数据。
     */
    private record Rendered(String sql, Set<String> parameters) {
    }

    /**
     * 定义{@code State}相关的可选值。
     */
    private enum State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        DOLLAR_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
