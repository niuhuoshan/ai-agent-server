package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 表示连接器配置相关的领域对象。
 * Strict allow-list validation for connector settings and remote tool schemas. */
@Component
public class ConnectorConfigurationValidator {

    public static final int MAX_TOOLS = 500;
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Pattern CREDENTIAL_REF = Pattern.compile("env:[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");
    private static final Pattern PARAMETER = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Set<String> PROVIDERS = Set.of("api", "mcp", "search");
    private static final Set<String> SCOPES = Set.of("global", "personal");
    // Status is the administrator's enable switch. Connectivity is derived from
    // last_check_at/last_error and must never be supplied by the client.
    private static final Set<String> STATUSES = Set.of("active", "disabled");
    private static final Set<String> MCP_KEYS = Set.of(
        "transport", "authType", "authHeader", "connectTimeoutMs", "requestTimeoutMs", "namespace"
    );
    private static final Set<String> HTTP_KEYS = Set.of(
        "authType", "authHeader", "connectTimeoutMs", "requestTimeoutMs"
    );
    private static final Set<String> SEARCH_KEYS = Set.of(
        "authType", "authHeader", "connectTimeoutMs", "requestTimeoutMs",
        "engine", "requestMethod", "queryParam", "countParam", "maxResults",
        "rateLimitPerMinute", "failureThreshold", "cooldownSeconds"
    );
    private static final Set<String> BLOCKED_HEADERS = Set.of(
        "host", "cookie", "set-cookie", "content-length", "connection", "transfer-encoding",
        "proxy-authorization", "proxy-authenticate", "forwarded", "x-forwarded-for"
    );

    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ConnectorConfigurationValidator} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ConnectorConfigurationValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理连接器Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String connectorKey(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) {
            throw badRequest("连接器标识格式无效");
        }
        return normalized;
    }

    /**
     * 处理提供方Type并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String providerType(String value) {
        return requiredEnum(value, PROVIDERS, "连接器类型");
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String status(String value) {
        return requiredEnum(value, STATUSES, "连接器状态");
    }

    /**
     * 处理范围并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String scope(String value) {
        return requiredEnum(value, SCOPES, "连接器范围");
    }

    /**
     * 处理凭据Ref并返回对应结果。
     *
     * @param value {@code value}参数
     * @param config {@code config}参数
     * @return 处理结果
     */
    public String credentialRef(String value, Map<String, Object> config) {
        String authType = String.valueOf(config.getOrDefault("authType", "none"));
        if ("none".equals(authType)) {
            if (value != null && !value.isBlank()) {
                throw badRequest("无鉴权连接器不能配置凭证引用");
            }
            return null;
        }
        String normalized = value == null ? "" : value.strip();
        if (!CREDENTIAL_REF.matcher(normalized).matches()) {
            throw badRequest("连接器凭证必须使用 env:NAME 引用");
        }
        return normalized;
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @param providerType 业务类型
     * @param source 数据源参数
     * @return 处理结果
     */
    public Map<String, Object> config(String providerType, Map<String, Object> source) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> value = source == null ? Map.of() : source;
        Set<String> allowed = switch (providerType) {
            case "mcp" -> MCP_KEYS;
            case "search" -> SEARCH_KEYS;
            default -> HTTP_KEYS;
        };
        if (!allowed.containsAll(value.keySet())) {
            var unsupported = new java.util.TreeSet<>(value.keySet());
            unsupported.removeAll(allowed);
            throw badRequest("连接器配置包含不支持字段：" + unsupported);
        }
        Map<String, Object> normalized = new TreeMap<>();
        String authType = text(value.getOrDefault("authType", "none"), "authType", 16).toLowerCase(Locale.ROOT);
        if (!Set.of("none", "bearer", "header").contains(authType)) {
            throw badRequest("连接器 authType 无效");
        }
        normalized.put("authType", authType);
        if ("header".equals(authType)) {
            String header = text(value.get("authHeader"), "authHeader", 64);
            if (!HEADER.matcher(header).matches() || BLOCKED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                throw badRequest("连接器鉴权 Header 无效");
            }
            normalized.put("authHeader", header);
        } else if (value.containsKey("authHeader")) {
            throw badRequest("只有 header 鉴权可以配置 authHeader");
        }
        normalized.put("connectTimeoutMs", integer(
            value.getOrDefault("connectTimeoutMs", 5000), 250, 30000, "connectTimeoutMs"
        ));
        normalized.put("requestTimeoutMs", integer(
            value.getOrDefault("requestTimeoutMs", 15000), 1000, 120000, "requestTimeoutMs"
        ));
        if ("mcp".equals(providerType)) {
            String transport = text(
                value.getOrDefault("transport", "streamable_http"), "transport", 32
            ).toLowerCase(Locale.ROOT).replace('-', '_');
            if (!Set.of("streamable_http", "sse").contains(transport)) {
                throw badRequest("MCP transport 仅支持 streamable_http 或 sse");
            }
            normalized.put("transport", transport);
            String namespace = text(value.getOrDefault("namespace", "default"), "namespace", 128)
                .toLowerCase(Locale.ROOT);
            if (!NAMESPACE.matcher(namespace).matches()) {
                throw badRequest("MCP namespace 格式无效");
            }
            normalized.put("namespace", namespace);
        }
        if ("search".equals(providerType)) {
            String engine = text(
                value.getOrDefault("engine", "custom"), "engine", 32
            ).toLowerCase(Locale.ROOT);
            if (!Set.of("brave", "bing", "serper", "searxng", "custom").contains(engine)) {
                throw badRequest("搜索引擎仅支持 brave、bing、serper、searxng 或 custom");
            }
            String requestMethod = text(
                value.getOrDefault("requestMethod", "serper".equals(engine) ? "POST" : "GET"),
                "requestMethod", 8
            ).toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST").contains(requestMethod)) {
                throw badRequest("搜索请求方法仅支持 GET 或 POST");
            }
            normalized.put("engine", engine);
            normalized.put("requestMethod", requestMethod);
            String queryParam = text(
                value.getOrDefault("queryParam", "serper".equals(engine) ? "q" : "q"),
                "queryParam", 64
            );
            String countParam = text(
                value.getOrDefault("countParam", "serper".equals(engine) ? "num" : "count"),
                "countParam", 64
            );
            if (!PARAMETER.matcher(queryParam).matches() || !PARAMETER.matcher(countParam).matches()
                || queryParam.equals(countParam)) {
                throw badRequest("搜索请求参数名无效或重复");
            }
            normalized.put("queryParam", queryParam);
            normalized.put("countParam", countParam);
            normalized.put("maxResults", integer(
                value.getOrDefault("maxResults", 10), 1, 20, "maxResults"
            ));
            normalized.put("rateLimitPerMinute", integer(
                value.getOrDefault("rateLimitPerMinute", 60), 1, 10000,
                "rateLimitPerMinute"
            ));
            normalized.put("failureThreshold", integer(
                value.getOrDefault("failureThreshold", 3), 1, 20, "failureThreshold"
            ));
            normalized.put("cooldownSeconds", integer(
                value.getOrDefault("cooldownSeconds", 60), 5, 3600, "cooldownSeconds"
            ));
        }
        bounded(normalized, "连接器配置");
        return Map.copyOf(normalized);
    }

    /**
     * 将输入数据转换为{@code olSchema}。
     *
     * @param schema {@code schema}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    public Map<String, Object> toolSchema(Map<String, Object> schema, String label) {
        if (schema == null) {
            throw badRequest(label + "不能为空");
        }
        Object normalized = normalize(schema, 0, label);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) normalized;
        Object type = result.get("type");
        if (type != null && !"object".equals(type)) {
            throw badRequest(label + "根类型必须是 object");
        }
        if (result.containsKey("$ref")) {
            throw badRequest(label + "不允许根级 $ref");
        }
        bounded(result, label);
        return Map.copyOf(result);
    }

    /**
     * 处理{@code optionalSchema}并返回对应结果。
     *
     * @param schema {@code schema}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    public Map<String, Object> optionalSchema(Map<String, Object> schema, String label) {
        return schema == null ? Map.of() : toolSchema(schema, label);
    }

    /**
     * 处理文档并返回对应结果。
     *
     * @param source 数据源参数
     * @param allowedKeys {@code allowedKeys}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    public Map<String, Object> document(
        Map<String, Object> source,
        Set<String> allowedKeys,
        String label
    ) {
        Map<String, Object> value = source == null ? Map.of() : source;
        if (!allowedKeys.containsAll(value.keySet())) {
            var unsupported = new java.util.TreeSet<>(value.keySet());
            unsupported.removeAll(allowedKeys);
            throw badRequest(label + "包含不支持字段：" + unsupported);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) normalize(value, 0, label);
        bounded(normalized, label);
        return Map.copyOf(normalized);
    }

    /**
     * 处理{@code boundedJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    public String boundedJson(Object value, String label) {
        bounded(value, label);
        return jsonMapper.writeValueAsString(value);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Object normalize(Object value, int depth, String label) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (depth > 12) {
            throw badRequest(label + "嵌套层级超过 12");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String text) {
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw badRequest(label + "包含超过 64KB 的单个文本");
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 256) {
                throw badRequest(label + "对象字段过多");
            }
            Map<String, Object> result = new TreeMap<>();
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.isBlank() || key.length() > 128) {
                    throw badRequest(label + "包含无效字段名");
                }
                if ("$ref".equals(key)) {
                    throw badRequest(label + "暂不接受 $ref，避免远端引用和解析歧义");
                }
                result.put(key, normalize(entry.getValue(), depth + 1, label));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 512) {
                throw badRequest(label + "数组元素过多");
            }
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(normalize(item, depth + 1, label));
            }
            return List.copyOf(result);
        }
        throw badRequest(label + "包含不支持的值类型");
    }

    /**
     * 处理{@code bounded}相关逻辑。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     */
    private void bounded(Object value, String label) {
        byte[] bytes = jsonMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw badRequest(label + "超过 64KB 限制");
        }
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int integer(Object value, int minimum, int maximum, String label) {
        if (!(value instanceof Number number)) {
            throw badRequest(label + "必须是整数");
        }
        long normalized = number.longValue();
        if (number.doubleValue() != normalized || normalized < minimum || normalized > maximum) {
            throw badRequest(label + "超出允许范围");
        }
        return Math.toIntExact(normalized);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String text(Object value, String label, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.strip().length() > maxLength) {
            throw badRequest(label + "无效");
        }
        return text.strip();
    }

    /**
     * 处理{@code endpointPath}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String endpointPath(Object value, String label) {
        String path = text(value, label, 255);
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('?') >= 0
            || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0) {
            throw badRequest(label + "必须是安全的绝对路径");
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw badRequest(label + "包含非法路径段");
            }
        }
        return path;
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
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
}
