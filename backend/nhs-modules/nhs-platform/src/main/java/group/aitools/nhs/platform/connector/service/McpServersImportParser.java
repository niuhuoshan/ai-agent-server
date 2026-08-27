package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.connector.web.McpImportEntryView;
import group.aitools.nhs.platform.connector.web.McpServersImportPreviewView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表示McpServers导入Parser相关的领域对象。
 * Parses exported mcpServers documents without carrying inline credentials into persistence or responses. */
@Component
public class McpServersImportParser {

    private static final int MAX_SERVERS = 50;
    private static final Pattern ENV_REF = Pattern.compile(
        "^(?:env:([A-Z][A-Z0-9_]{0,127})|\\$\\{([A-Z][A-Z0-9_]{0,127})}|\\$([A-Z][A-Z0-9_]{0,127}))$"
    );
    private static final Set<String> INLINE_SECRET_KEYS = Set.of(
        "token", "secret", "password", "apikey", "api_key", "access_token", "authorization"
    );

    private final ConnectorConfigurationValidator validator;
    private final ConnectorEndpointPolicy endpointPolicy;

    /**
     * 创建 {@code McpServersImportParser} 实例并初始化所需依赖。
     *
     * @param validator {@code validator}参数
     * @param endpointPolicy endpoint策略参数
     */
    public McpServersImportParser(
        ConnectorConfigurationValidator validator,
        ConnectorEndpointPolicy endpointPolicy
    ) {
        this.validator = validator;
        this.endpointPolicy = endpointPolicy;
    }

    /**
     * 处理{@code preview}并返回对应结果。
     *
     * @param document 文档参数
     * @return 处理结果
     */
    public McpServersImportPreviewView preview(Map<String, Object> document) {
        validator.boundedJson(document, "MCP 导入配置");
        Map<String, Object> servers = serverMap(document);
        if (servers.isEmpty()) {
            throw badRequest("未识别到 mcpServers 配置");
        }
        if (servers.size() > MAX_SERVERS) {
            throw badRequest("单次最多解析 50 个 MCP 服务");
        }
        List<McpImportEntryView> entries = new ArrayList<>();
        for (Map.Entry<String, Object> source : servers.entrySet()) {
            String sourceKey = sourceKey(source.getKey());
            entries.add(parse(sourceKey, source.getValue()));
        }
        return new McpServersImportPreviewView(List.copyOf(entries));
    }

    /**
     * 校验{@code Importable}，并在条件不满足时终止处理。
     *
     * @param document 文档参数
     * @param sourceKey 数据源Key参数
     * @return 处理结果
     */
    public McpImportEntryView requireImportable(Map<String, Object> document, String sourceKey) {
        McpServersImportPreviewView preview = preview(document);
        McpImportEntryView entry = preview.entries().stream()
            .filter(value -> value.sourceKey().equals(sourceKey))
            .findFirst()
            .orElseThrow(() -> badRequest("导入配置中不存在指定 MCP 服务"));
        if (!entry.importable()) {
            throw badRequest("MCP 服务无法安全导入：" + String.join("；", entry.diagnostics()));
        }
        return entry;
    }

    /**
     * 处理连接器Config并返回对应结果。
     *
     * @param entry {@code entry}参数
     * @return 处理结果
     */
    public Map<String, Object> connectorConfig(McpImportEntryView entry) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("transport", entry.transport());
        config.put("authType", entry.authType());
        if (entry.authHeader() != null) {
            config.put("authHeader", entry.authHeader());
        }
        config.put("connectTimeoutMs", 5000);
        config.put("requestTimeoutMs", 15000);
        return validator.config("mcp", config);
    }

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param sourceKey 数据源Key参数
     * @param rawValue {@code rawValue}参数
     * @return 处理结果
     */
    private McpImportEntryView parse(String sourceKey, Object rawValue) {
        List<String> diagnostics = new ArrayList<>();
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            diagnostics.add("服务配置必须是 JSON 对象");
            return unsupported(sourceKey, diagnostics);
        }
        Map<String, Object> config = stringMap(rawMap);
        String endpointUrl = normalizedEndpoint(config, diagnostics);
        String transport = transport(config, diagnostics);
        Auth auth = auth(config, diagnostics);
        boolean importable = endpointUrl != null && transport != null && auth.supported();
        if (importable) {
            try {
                connectorConfig(new McpImportEntryView(
                    sourceKey, suggestedKey(sourceKey), suggestedName(sourceKey), endpointUrl,
                    transport, auth.type(), auth.header(), auth.credentialRef(),
                    auth.required(), true, List.copyOf(diagnostics)
                ));
            } catch (ServiceException exception) {
                diagnostics.add(exception.getMessage());
                importable = false;
            }
        }
        return new McpImportEntryView(
            sourceKey, suggestedKey(sourceKey), suggestedName(sourceKey), endpointUrl,
            transport == null ? "streamable_http" : transport,
            auth.type(), auth.header(), auth.credentialRef(), auth.required(),
            importable, List.copyOf(diagnostics)
        );
    }

    /**
     * 处理{@code normalizedEndpoint}并返回对应结果。
     *
     * @param config {@code config}参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private String normalizedEndpoint(Map<String, Object> config, List<String> diagnostics) {
        Object raw = first(config, "url", "serverUrl", "sse_url", "sseUrl", "endpoint");
        if (!(raw instanceof String text) || text.isBlank()) {
            diagnostics.add("缺少 url/serverUrl；当前仅支持远程 HTTP/SSE MCP 服务");
            return null;
        }
        try {
            URI normalized = endpointPolicy.normalize(text);
            return normalized.toString();
        } catch (ServiceException exception) {
            diagnostics.add(exception.getMessage());
            return null;
        }
    }

    /**
     * 处理{@code transport}并返回对应结果。
     *
     * @param config {@code config}参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private String transport(Map<String, Object> config, List<String> diagnostics) {
        Object raw = first(config, "type", "transport");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return "streamable_http";
        }
        String value = String.valueOf(raw).strip().toLowerCase(Locale.ROOT).replace('-', '_');
        if (Set.of("http", "streamable", "streamable_http").contains(value)) {
            return "streamable_http";
        }
        if ("sse".equals(value)) {
            return "sse";
        }
        diagnostics.add("不支持 transport/type " + safeLabel(value) + "；仅支持 streamable_http 或 sse");
        return null;
    }

    /**
     * 处理认证并返回对应结果。
     *
     * @param config {@code config}参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private Auth auth(Map<String, Object> config, List<String> diagnostics) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> headers = object(config.get("headers"), "headers", diagnostics);
        Map<String, Object> environment = object(config.get("env"), "env", diagnostics);
        if (headers == null || environment == null) {
            return Auth.unsupported();
        }
        List<Map.Entry<String, Object>> populated = headers.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank())
            .toList();
        if (populated.size() > 1) {
            diagnostics.add("当前只支持一个鉴权 Header；请移除其他 Header 后导入");
            return Auth.unsupported();
        }
        if (populated.size() == 1) {
            Map.Entry<String, Object> header = populated.getFirst();
            return headerAuth(header.getKey(), header.getValue(), diagnostics);
        }
        Object exportedAuthorization = caseInsensitive(environment, "Authorization");
        if (exportedAuthorization != null && !String.valueOf(exportedAuthorization).isBlank()) {
            return headerAuth("Authorization", exportedAuthorization, diagnostics);
        }
        boolean hasUnmappedSecret = config.entrySet().stream().anyMatch(entry ->
            INLINE_SECRET_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))
                && entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()
        );
        if (hasUnmappedSecret || !environment.isEmpty()) {
            diagnostics.add("检测到无法映射的 env/secret；请改用 headers 中的环境变量占位符");
            return Auth.unsupported();
        }
        return new Auth("none", null, null, false, true);
    }

    /**
     * 处理header认证并返回对应结果。
     *
     * @param rawHeader {@code rawHeader}参数
     * @param rawValue {@code rawValue}参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private Auth headerAuth(String rawHeader, Object rawValue, List<String> diagnostics) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String header = rawHeader == null ? "" : rawHeader.strip();
        if (header.isBlank() || header.length() > 64) {
            diagnostics.add("鉴权 Header 名称无效");
            return Auth.unsupported();
        }
        String value = rawValue instanceof String text ? text.strip() : "";
        if ("authorization".equalsIgnoreCase(header)) {
            if (value.regionMatches(true, 0, "Basic ", 0, 6)) {
                diagnostics.add("暂不支持 Basic Authorization；请改用 Bearer 或自定义 Header");
                return Auth.unsupported();
            }
            String token = value.regionMatches(true, 0, "Bearer ", 0, 7)
                ? value.substring(7).strip() : value;
            String reference = environmentReference(token);
            if (reference == null) {
                diagnostics.add("检测到内联 Authorization，值已丢弃；请提供 env:NAME 凭据引用");
            }
            return new Auth("bearer", null, reference, reference == null, true);
        }
        String reference = environmentReference(value);
        if (reference == null) {
            diagnostics.add("检测到内联 Header 凭据，值已丢弃；请提供 env:NAME 凭据引用");
        }
        return new Auth("header", header, reference, reference == null, true);
    }

    /**
     * 处理{@code serverMap}并返回对应结果。
     *
     * @param document 文档参数
     * @return 处理结果
     */
    private Map<String, Object> serverMap(Map<String, Object> document) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (document == null) {
            return Map.of();
        }
        Object nested = document.get("mcpServers");
        if (!(nested instanceof Map<?, ?>)) {
            nested = document.get("servers");
        }
        if (nested instanceof Map<?, ?> map) {
            return stringMap(map);
        }
        if (first(document, "url", "serverUrl", "sse_url", "sseUrl", "endpoint") != null) {
            return Map.of("server", document);
        }
        return Map.of();
    }

    /**
     * 处理{@code object}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private Map<String, Object> object(Object value, String label, List<String> diagnostics) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            diagnostics.add(label + " 必须是 JSON 对象");
            return null;
        }
        return stringMap(map);
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
        return result;
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param source 数据源参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    /**
     * 处理{@code caseInsensitive}并返回对应结果。
     *
     * @param source 数据源参数
     * @param name 名称
     * @return 处理结果
     */
    private Object caseInsensitive(Map<String, Object> source, String name) {
        return source.entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * 处理{@code environmentReference}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String environmentReference(String raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = ENV_REF.matcher(raw.strip());
        if (!matcher.matches()) {
            return null;
        }
        for (int index = 1; index <= matcher.groupCount(); index++) {
            if (matcher.group(index) != null) {
                return "env:" + matcher.group(index);
            }
        }
        return null;
    }

    /**
     * 处理数据源Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String sourceKey(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > 128
            || normalized.chars().anyMatch(ch -> ch == 0 || ch == '\r' || ch == '\n')) {
            throw badRequest("MCP 服务键名无效");
        }
        return normalized;
    }

    /**
     * 处理{@code suggestedKey}并返回对应结果。
     *
     * @param sourceKey 数据源Key参数
     * @return 处理结果
     */
    private String suggestedKey(String sourceKey) {
        String key = sourceKey.toLowerCase(Locale.ROOT)
            .replaceAll("^mcp[-_.]+", "")
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[^a-z]+", "")
            .replaceAll("[-_.]+$", "");
        if (key.isBlank()) {
            key = "server";
        }
        String result = "mcp-" + key;
        return result.length() <= 128 ? result : result.substring(0, 128).replaceAll("[-_.]+$", "");
    }

    /**
     * 处理{@code suggestedName}并返回对应结果。
     *
     * @param sourceKey 数据源Key参数
     * @return 处理结果
     */
    private String suggestedName(String sourceKey) {
        return sourceKey.length() <= 128 ? sourceKey : sourceKey.substring(0, 128);
    }

    /**
     * 处理{@code safeLabel}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeLabel(String value) {
        String safe = value.replaceAll("[^a-z0-9_-]", "");
        return safe.isBlank() ? "未知" : safe.substring(0, Math.min(safe.length(), 32));
    }

    /**
     * 处理{@code unsupported}并返回对应结果。
     *
     * @param sourceKey 数据源Key参数
     * @param diagnostics {@code diagnostics}参数
     * @return 处理结果
     */
    private McpImportEntryView unsupported(String sourceKey, List<String> diagnostics) {
        return new McpImportEntryView(
            sourceKey, suggestedKey(sourceKey), suggestedName(sourceKey), null,
            "streamable_http", "none", null, null, false, false, List.copyOf(diagnostics)
        );
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
     * 封装认证相关的不可变数据。
     */
    private record Auth(
        String type,
        String header,
        String credentialRef,
        boolean required,
        boolean supported
    ) {
        /**
         * 处理{@code unsupported}并返回对应结果。
         *
         * @return 处理结果
         */
        private static Auth unsupported() {
            return new Auth("none", null, null, false, false);
        }
    }
}
