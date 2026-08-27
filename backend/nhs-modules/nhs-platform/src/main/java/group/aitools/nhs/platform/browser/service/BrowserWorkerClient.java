package group.aitools.nhs.platform.browser.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示浏览器工作进程相关的领域对象。
 * HTTP adapter for the isolated local Docker Playwright worker. */
@Component
public class BrowserWorkerClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final String workerUrl;
    private final Duration timeout;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public BrowserWorkerClient(
        @Value("${agent.platform.browser.worker-url:http://127.0.0.1:8787}") String workerUrl,
        @Value("${agent.platform.browser.request-timeout-ms:30000}") int timeoutMs,
        JsonMapper jsonMapper
    ) {
        String normalized = workerUrl == null ? "" : workerUrl.strip();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new IllegalArgumentException("浏览器 Worker 地址必须是 HTTP 或 HTTPS");
        }
        this.workerUrl = normalized;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 120_000)));
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param sessionKey 会话Key参数
     * @param profileKey 配置档案Key参数
     * @param startUrl {@code startUrl}参数
     * @param uploadScope upload范围参数
     * @return 处理结果
     */
    public Map<String, Object> open(String sessionKey, String profileKey, String startUrl, String uploadScope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionKey);
        if (profileKey != null) body.put("profile_key", profileKey);
        if (startUrl != null) body.put("start_url", startUrl);
        body.put("upload_scope", uploadScope);
        return request("POST", "/sessions", body);
    }

    /**
 * 处理健康状态并返回对应结果。
 * Returns bounded Worker lifecycle facts without exposing page content. */
    public Map<String, Object> health() {
        return request("GET", "/health", null);
    }

    /**
     * 处理{@code navigate}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param url {@code url}参数
     * @return 处理结果
     */
    public Map<String, Object> navigate(String workerSessionId, String url) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/navigate",
            Map.of("url", url));
    }

    /**
     * 处理快照并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> snapshot(String workerSessionId) {
        return request("GET", "/sessions/" + segment(workerSessionId) + "/snapshot", null);
    }

    /**
     * 处理{@code click}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @return 处理结果
     */
    public Map<String, Object> click(String workerSessionId, String selector) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/click",
            Map.of("selector", selector));
    }

    /**
     * 处理{@code fill}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @param value {@code value}参数
     * @return 处理结果
     */
    public Map<String, Object> fill(String workerSessionId, String selector, String value) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/fill",
            Map.of("selector", selector, "value", value));
    }

    /**
     * 处理{@code press}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param key {@code key}参数
     * @return 处理结果
     */
    public Map<String, Object> press(String workerSessionId, String key) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/press",
            Map.of("key", key));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param action {@code action}参数
     * @return 处理结果
     */
    public Map<String, Object> history(String workerSessionId, String action) {
        if (action == null || !java.util.Set.of("back", "forward", "reload").contains(action)) {
            throw new ServiceException("浏览器历史动作无效", HttpStatus.BAD_REQUEST);
        }
        return request("POST", "/sessions/" + segment(workerSessionId) + "/" + action, Map.of());
    }

    /**
     * 处理{@code waitFor}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param condition {@code condition}参数
     * @param value {@code value}参数
     * @param timeoutMs {@code timeoutMs}参数
     * @return 处理结果
     */
    public Map<String, Object> waitFor(
        String workerSessionId, String condition, String value, Integer timeoutMs
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("condition", condition);
        body.put("value", value == null ? "" : value);
        body.put("timeout_ms", timeoutMs == null ? 5_000 : timeoutMs);
        return request("POST", "/sessions/" + segment(workerSessionId) + "/wait-for", body);
    }

    /**
     * 获取{@code Option}。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    public Map<String, Object> selectOption(
        String workerSessionId, String selector, String value, String label
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selector", selector);
        if (value != null && !value.isBlank()) body.put("value", value);
        if (label != null && !label.isBlank()) body.put("label", label);
        return request("POST", "/sessions/" + segment(workerSessionId) + "/select-option", body);
    }

    /**
     * 处理{@code readVisible}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> readVisible(String workerSessionId) {
        return request("GET", "/sessions/" + segment(workerSessionId) + "/read-visible", null);
    }

    /**
     * 处理{@code drag}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param sourceSelector 数据源Selector参数
     * @param targetSelector {@code targetSelector}参数
     * @return 处理结果
     */
    public Map<String, Object> drag(String workerSessionId, String sourceSelector, String targetSelector) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/drag",
            Map.of("source_selector", sourceSelector, "target_selector", targetSelector));
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @return 处理结果
     */
    public Map<String, Object> download(String workerSessionId, String selector) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/download",
            Map.of("selector", selector));
    }

    /**
     * 处理{@code manualInput}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param input {@code input}参数
     * @return 处理结果
     */
    public Map<String, Object> manualInput(String workerSessionId, Map<String, Object> input) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/manual-input", input);
    }

    /**
     * 处理{@code scroll}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param x {@code x}参数
     * @param y {@code y}参数
     * @param selector {@code selector}参数
     * @return 处理结果
     */
    public Map<String, Object> scroll(String workerSessionId, Integer x, Integer y, String selector) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x == null ? 0 : x);
        body.put("y", y == null ? 600 : y);
        if (selector != null && !selector.isBlank()) body.put("selector", selector);
        return request("POST", "/sessions/" + segment(workerSessionId) + "/scroll", body);
    }

    /**
     * 处理{@code hover}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @return 处理结果
     */
    public Map<String, Object> hover(String workerSessionId, String selector) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/hover",
            Map.of("selector", selector));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param selector {@code selector}参数
     * @param files {@code files}参数
     * @return 处理结果
     */
    public Map<String, Object> upload(String workerSessionId, String selector, java.util.List<String> files) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/upload",
            Map.of("selector", selector, "files", files));
    }

    /**
     * 处理{@code tabs}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> tabs(String workerSessionId) {
        return request("GET", "/sessions/" + segment(workerSessionId) + "/tabs", null);
    }

    /**
     * 处理{@code openTab}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param url {@code url}参数
     * @return 处理结果
     */
    public Map<String, Object> openTab(String workerSessionId, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (url != null && !url.isBlank()) body.put("url", url);
        return request("POST", "/sessions/" + segment(workerSessionId) + "/tabs", body);
    }

    /**
     * 处理{@code activateTab}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> activateTab(String workerSessionId, String tabId) {
        return request("POST", "/sessions/" + segment(workerSessionId) + "/tabs/"
            + segment(tabId) + "/activate", Map.of());
    }

    /**
     * 处理{@code closeTab}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> closeTab(String workerSessionId, String tabId) {
        return request("DELETE", "/sessions/" + segment(workerSessionId) + "/tabs/"
            + segment(tabId), null);
    }

    /**
     * 处理{@code close}并返回对应结果。
     *
     * @param workerSessionId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> close(String workerSessionId) {
        return request("DELETE", "/sessions/" + segment(workerSessionId), null);
    }

    /**
 * 清理或重置配置档案。
 * Clears cookies/storage and closes matching Worker sessions for one owner scope. */
    public Map<String, Object> clearProfile(String profileKey, String uploadScope) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (profileKey != null && !profileKey.isBlank()) body.put("profile_key", profileKey);
        body.put("upload_scope", uploadScope);
        return request("POST", "/profiles/clear", body);
    }

    /**
     * 处理{@code request}并返回对应结果。
     *
     * @param method {@code method}参数
     * @param path {@code path}参数
     * @param body {@code body}参数
     * @return 处理结果
     */
    private Map<String, Object> request(String method, String path, Map<String, Object> body) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        URI uri;
        try {
            uri = URI.create(workerUrl + path);
        } catch (IllegalArgumentException exception) {
            throw unavailable("浏览器 Worker 地址无效");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "nhs-browser/1.0");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(body)
                ));
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw unavailable("浏览器 Worker 响应超过大小限制");
            }
            String text = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = text.length() > 1000 ? text.substring(0, 1000) : text;
                throw new ServiceException("浏览器 Worker 调用失败（HTTP " + response.statusCode()
                    + "）: " + message, response.statusCode() >= 400 ? response.statusCode() : 503);
            }
            if (text.isBlank()) return Map.of("success", true);
            Map<String, Object> value = jsonMapper.readValue(text, MAP_TYPE);
            if (value == null) return Map.of("success", true);
            // Worker responses may intentionally contain nullable page/profile fields.
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("浏览器 Worker 调用被中断");
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw unavailable("无法连接浏览器 Worker");
        }
    }

    /**
     * 处理{@code segment}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,255}")) {
            throw new ServiceException("浏览器 Worker 会话标识无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, 503);
    }
}
