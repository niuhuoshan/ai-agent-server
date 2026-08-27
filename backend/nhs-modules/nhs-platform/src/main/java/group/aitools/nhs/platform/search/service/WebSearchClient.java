package group.aitools.nhs.platform.search.service;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ConnectorCredentialResolver;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示{@code WebSearch}相关的领域对象。
 * Bounded JSON search transport with strict endpoint, redirect and response controls. */
@Component
public class WebSearchClient {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ConnectorEndpointPolicy endpointPolicy;
    private final ConnectorCredentialResolver credentialResolver;
    private final JsonMapper jsonMapper;

    public WebSearchClient(
        ConnectorEndpointPolicy endpointPolicy,
        ConnectorCredentialResolver credentialResolver,
        JsonMapper jsonMapper
    ) {
        this.endpointPolicy = endpointPolicy;
        this.credentialResolver = credentialResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param connector 连接器参数
     * @param config {@code config}参数
     * @param query 查询参数
     * @param maxResults {@code maxResults}参数
     * @return 符合条件的数据集合
     */
    public List<SearchHit> search(
        AgentConnector connector,
        SearchProviderConfig config,
        String query,
        int maxResults
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        URI endpoint = endpointPolicy.normalize(connector.getEndpointUrl());
        URI target = "GET".equals(config.requestMethod())
            ? queryTarget(endpoint, config, query, maxResults) : endpoint;
        endpointPolicy.requireSameOrigin(endpoint, target);
        endpointPolicy.validateNetworkTarget(endpoint);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
            .timeout(config.requestTimeout())
            .header("Accept", "application/json")
            .header("User-Agent", "nhs-search/1.0");
        applyCredential(connector, config, request);
        if ("POST".equals(config.requestMethod())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(config.queryParam(), query);
            payload.put(config.countParam(), maxResults);
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(payload), StandardCharsets.UTF_8
                ));
        } else {
            request.GET();
        }
        try {
            HttpResponse<InputStream> response = client.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                throw providerError("search_redirect_rejected", "搜索Provider返回了重定向");
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw providerError("search_response_too_large", "搜索Provider响应超过2MB限制");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerError(
                    "search_http_" + response.statusCode(),
                    "搜索Provider调用失败（HTTP " + response.statusCode() + "）"
                );
            }
            return parse(config.engine(), body, maxResults);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerError("search_interrupted", "搜索Provider调用被中断");
        } catch (IOException exception) {
            throw providerError("search_connection_failed", "无法连接搜索Provider");
        }
    }

    /**
     * 获取{@code Target}。
     *
     * @param endpoint {@code endpoint}参数
     * @param config {@code config}参数
     * @param query 查询参数
     * @param maxResults {@code maxResults}参数
     * @return 处理结果
     */
    private URI queryTarget(
        URI endpoint,
        SearchProviderConfig config,
        String query,
        int maxResults
    ) {
        try {
            return new URI(
                endpoint.getScheme(), endpoint.getAuthority(), endpoint.getPath(),
                "searxng".equals(config.engine())
                    ? config.queryParam() + "=" + query + "&format=json"
                    : config.queryParam() + "=" + query + "&" + config.countParam() + "=" + maxResults,
                null
            );
        } catch (URISyntaxException exception) {
            throw providerError("search_request_invalid", "搜索Provider请求参数无效");
        }
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
        SearchProviderConfig config,
        HttpRequest.Builder request
    ) {
        if ("none".equals(config.authType())) {
            return;
        }
        String credential = credentialResolver.resolve(connector.getCredentialRef());
        if ("bearer".equals(config.authType())) {
            request.header("Authorization", "Bearer " + credential);
            return;
        }
        request.header(config.authHeader(), credential);
    }

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param engine {@code engine}参数
     * @param body {@code body}参数
     * @param maximum {@code maximum}参数
     * @return 符合条件的数据集合
     */
    private List<SearchHit> parse(String engine, byte[] body, int maximum) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw providerError("search_response_invalid", "搜索Provider返回了无效JSON");
        }
        JsonNode items = switch (engine) {
            case "brave" -> child(child(root, "web"), "results");
            case "bing" -> child(child(root, "webPages"), "value");
            case "serper" -> child(root, "organic");
            default -> firstArray(root, "results", "items", "data", "organic");
        };
        if (items == null && root != null && root.isArray()) {
            items = root;
        }
        if (items == null || !items.isArray()) {
            throw providerError("search_response_invalid", "搜索Provider响应缺少结果数组");
        }
        List<SearchHit> result = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : items) {
            if (rank > maximum || item == null || !item.isObject()) {
                break;
            }
            String title = firstText(item, "title", "name");
            String rawUrl = firstText(item, "url", "link");
            URI url = safeResultUrl(rawUrl);
            if (title == null || url == null) {
                continue;
            }
            result.add(new SearchHit(
                rank++, bounded(title, 512), url.toString(),
                bounded(firstText(item, "description", "snippet", "content"), 4000),
                bounded(firstText(item, "publishedAt", "date", "age"), 128),
                url.getHost().toLowerCase()
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code firstArray}并返回对应结果。
     *
     * @param root {@code root}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private JsonNode firstArray(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode value = child(root, key);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 处理{@code child}并返回对应结果。
     *
     * @param source 数据源参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private JsonNode child(JsonNode source, String key) {
        return source == null ? null : source.get(key);
    }

    /**
     * 处理{@code firstText}并返回对应结果。
     *
     * @param source 数据源参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String firstText(JsonNode source, String... keys) {
        for (String key : keys) {
            JsonNode value = child(source, key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return null;
    }

    /**
     * 处理safe结果Url并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private URI safeResultUrl(String value) {
        if (value == null || value.length() > 4096) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            return uri.isAbsolute() && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /**
     * 处理提供方Error并返回对应结果。
     *
     * @param code {@code code}参数
     * @param message 待处理内容
     * @return 处理结果
     */
    private SearchProviderException providerError(String code, String message) {
        return new SearchProviderException(code, message);
    }

    /**
     * 封装{@code SearchHit}相关的不可变数据。
     */
    public record SearchHit(
        int rank,
        String title,
        String url,
        String snippet,
        String publishedAt,
        String source
    ) {
    }
}
