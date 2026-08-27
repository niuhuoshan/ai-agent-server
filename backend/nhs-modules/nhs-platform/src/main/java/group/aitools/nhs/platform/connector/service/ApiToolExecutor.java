package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表示接口工具相关的领域对象。
 * Executes bounded, same-origin API and search tools through one hardened HTTP boundary. */
@Component
public class ApiToolExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private final ConnectorEndpointPolicy endpointPolicy;
    private final ConnectorConfigurationValidator configurationValidator;
    private final ConnectorCredentialResolver credentialResolver;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ApiToolExecutor} 实例并初始化所需依赖。
     *
     * @param endpointPolicy endpoint策略参数
     * @param configurationValidator 配置Validator参数
     * @param credentialResolver 凭据Resolver参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ApiToolExecutor(
        ConnectorEndpointPolicy endpointPolicy,
        ConnectorConfigurationValidator configurationValidator,
        ConnectorCredentialResolver credentialResolver,
        JsonMapper jsonMapper
    ) {
        this.endpointPolicy = endpointPolicy;
        this.configurationValidator = configurationValidator;
        this.credentialResolver = credentialResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param connector 连接器参数
     * @param executionPolicy 执行策略参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public ApiInvocationResult invoke(
        AgentConnector connector,
        Map<String, Object> executionPolicy,
        Map<String, Object> arguments
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (connector == null || !Set.of("api", "search").contains(connector.getProviderType())) {
            throw new IllegalArgumentException("API/搜索连接器配置无效");
        }
        Map<String, Object> connectorConfig = configurationValidator.config(
            connector.getProviderType(), parseConfig(connector)
        );
        String method = requiredText(executionPolicy.get("method"), "API 工具 method")
            .toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw badGateway("API 工具 method 无效");
        }
        String path = requiredText(executionPolicy.get("path"), "API 工具 path");
        URI configured = endpointPolicy.normalize(connector.getEndpointUrl());
        URI target = target(configured, path, method, arguments);
        endpointPolicy.requireSameOrigin(configured, target);

        int connectTimeout = integer(
            connectorConfig.get("connectTimeoutMs"), 250, 30_000, "连接超时"
        );
        int requestTimeout = optionalInteger(
            executionPolicy.get("timeoutMs"),
            integer(connectorConfig.get("requestTimeoutMs"), 1_000, 120_000, "请求超时"),
            1_000, 120_000, "工具请求超时"
        );
        int maxOutputBytes = optionalInteger(
            executionPolicy.get("maxOutputBytes"), DEFAULT_MAX_OUTPUT_BYTES,
            1_024, 10 * 1024 * 1024, "工具结果大小"
        );
        int retryCount = optionalInteger(
            executionPolicy.get("retryCount"), 0, 0, 2, "工具重试次数"
        );
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMillis(requestTimeout))
            .header("Accept", "application/json, text/plain;q=0.9")
            .header("User-Agent", "nhs-api-tool/1.0");
        applyCredential(connector, connectorConfig, request);
        if (Set.of("POST", "PUT", "PATCH").contains(method)) {
            String contentType = optionalText(executionPolicy.get("contentType"));
            if (contentType != null && !"application/json".equalsIgnoreCase(contentType)) {
                throw badGateway("API 工具仅支持 application/json 请求体");
            }
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(arguments), StandardCharsets.UTF_8
                ));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpRequest preparedRequest = request.build();
            HttpResponse<InputStream> response = null;
            IOException lastFailure = null;
            for (int attempt = 0; attempt <= retryCount; attempt++) {
                endpointPolicy.validateNetworkTarget(configured);
                try {
                    response = client.send(preparedRequest, HttpResponse.BodyHandlers.ofInputStream());
                    break;
                } catch (IOException exception) {
                    lastFailure = exception;
                }
            }
            if (response == null) {
                throw lastFailure == null ? new IOException("API request failed") : lastFailure;
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                throw badGateway("API 工具禁止 HTTP 重定向");
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(maxOutputBytes + 1);
            }
            if (body.length > maxOutputBytes) {
                throw badGateway("API 工具结果超过大小限制");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            Object content = content(body, contentType);
            return new ApiInvocationResult(
                response.statusCode(), response.statusCode() < 200 || response.statusCode() >= 300,
                content, normalizedContentType(contentType)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw badGateway("API 工具调用被中断");
        } catch (IOException exception) {
            throw badGateway("API 工具连接失败");
        }
    }

    /**
     * 处理{@code target}并返回对应结果。
     *
     * @param configured {@code configured}参数
     * @param path {@code path}参数
     * @param method {@code method}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private URI target(
        URI configured,
        String path,
        String method,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!path.startsWith("/") || path.contains("\\") || path.contains("?") || path.contains("#")
            || path.length() > 512) {
            throw badGateway("API 工具 path 无效");
        }
        String raw = configured.getScheme() + "://" + configured.getRawAuthority() + path;
        if (Set.of("GET", "DELETE").contains(method) && !arguments.isEmpty()) {
            raw += "?" + query(arguments);
        }
        if (raw.length() > 8192) {
            throw badGateway("API 工具请求 URL 过长");
        }
        URI target = URI.create(raw);
        if (target.getRawPath().toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*")) {
            throw badGateway("API 工具 path 包含不安全编码");
        }
        return target;
    }

    /**
     * 获取查询。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private String query(Map<String, Object> arguments) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(arguments.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : entries) {
            Object value = entry.getValue();
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    pairs.add(pair(entry.getKey(), scalar(item)));
                }
            } else {
                pairs.add(pair(entry.getKey(), scalar(value)));
            }
        }
        return String.join("&", pairs);
    }

    /**
     * 处理{@code scalar}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String scalar(Object value) {
        if (value == null || value instanceof String || value instanceof Number
            || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw badGateway("GET/DELETE 工具参数只能使用标量或标量数组");
    }

    /**
     * 处理{@code pair}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String pair(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 处理apply凭据相关逻辑。
     *
     * @param connector 连接器参数
     * @param config {@code config}参数
     * @param request 请求参数
     */
    private void applyCredential(
        AgentConnector connector,
        Map<String, Object> config,
        HttpRequest.Builder request
    ) {
        String authType = String.valueOf(config.get("authType"));
        if ("none".equals(authType)) {
            return;
        }
        String credential = credentialResolver.resolve(connector.getCredentialRef());
        if ("bearer".equals(authType)) {
            request.header("Authorization", "Bearer " + credential);
        } else if ("header".equals(authType)) {
            request.header(String.valueOf(config.get("authHeader")), credential);
        } else {
            throw badGateway("API 连接器鉴权配置无效");
        }
    }

    /**
     * 处理{@code content}并返回对应结果。
     *
     * @param bytes {@code bytes}参数
     * @param contentType 业务类型
     * @return 处理结果
     */
    private Object content(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String normalized = normalizedContentType(contentType);
        if ("application/json".equals(normalized) || normalized.endsWith("+json")) {
            try {
                return jsonMapper.readValue(text, Object.class);
            } catch (RuntimeException exception) {
                throw badGateway("API 工具返回了无效 JSON");
            }
        }
        return text;
    }

    /**
     * 处理{@code normalizedContentType}并返回对应结果。
     *
     * @param contentType 业务类型
     * @return 处理结果
     */
    private String normalizedContentType(String contentType) {
        int separator = contentType.indexOf(';');
        String value = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code parseConfig}并返回对应结果。
     *
     * @param connector 连接器参数
     * @return 处理结果
     */
    private Map<String, Object> parseConfig(AgentConnector connector) {
        try {
            Map<String, Object> config = jsonMapper.readValue(connector.getConfigJson(), MAP_TYPE);
            return config == null ? Map.of() : config;
        } catch (RuntimeException exception) {
            throw badGateway("API 连接器配置无效");
        }
    }

    /**
     * 处理{@code optionalInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int optionalInteger(
        Object value,
        int defaultValue,
        int minimum,
        int maximum,
        String label
    ) {
        return value == null ? defaultValue : integer(value, minimum, maximum, label);
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
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
            || number.longValue() < minimum || number.longValue() > maximum) {
            throw badGateway(label + "无效");
        }
        return Math.toIntExact(number.longValue());
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label) {
        String text = optionalText(value);
        if (text == null) {
            throw badGateway(label + "无效");
        }
        return text;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalText(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /**
     * 处理{@code badGateway}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badGateway(String message) {
        return new ServiceException(message, 502);
    }

    /**
     * 封装接口调用相关的不可变数据。
     */
    public record ApiInvocationResult(
        int statusCode,
        boolean error,
        Object content,
        String contentType
    ) {
    }
}
