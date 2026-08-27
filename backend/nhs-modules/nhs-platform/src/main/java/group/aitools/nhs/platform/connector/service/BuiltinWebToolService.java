package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.knowledge.service.KnowledgeDocumentParser;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责BuiltinWeb工具相关的业务编排与领域规则处理。
 * Bounded public-web builtins with one SSRF policy and an optional managed Chromium binary. */
@Service
public class BuiltinWebToolService {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BLOCKED_HEADERS = Set.of(
        "accept-encoding", "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Pattern CHARSET = Pattern.compile(
        "(?:^|;)\\s*charset=([A-Za-z0-9._:-]{1,64})", Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_HEADERS = 64;
    private static final int MAX_HEADER_VALUE_LENGTH = 8_192;
    private static final int MAX_URL_LENGTH = 8_192;
    private static final int MAX_QUERY_VALUES = 256;
    private static final int MAX_CODE_CHARACTERS = 256 * 1024;
    private static final int MAX_DIAGNOSTICS = 24;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_RENDER_OUTPUT_BYTES = 2 * 1024 * 1024;

    private final ConnectorEndpointPolicy endpointPolicy;
    private final KnowledgeDocumentParser documentParser;
    private final GeneratedFileService generatedFileService;
    private final JsonMapper jsonMapper;
    private final Path chromiumExecutable;
    private final int connectTimeoutMs;
    private final int requestTimeoutMs;
    private final int maxResponseBytes;
    private final int chromiumTimeoutMs;
    private final boolean chromiumSandbox;

    /**
     * 创建 {@code BuiltinWebToolService} 实例并初始化所需依赖。
     *
     * @param endpointPolicy endpoint策略参数
     * @param documentParser 文档Parser参数
     * @param generatedFileService generated文件Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param chromiumExecutable {@code chromiumExecutable}参数
     * @param connectTimeoutMs {@code connectTimeoutMs}参数
     * @param requestTimeoutMs {@code requestTimeoutMs}参数
     * @param maxResponseBytes {@code maxResponseBytes}参数
     * @param chromiumTimeoutMs {@code chromiumTimeoutMs}参数
     * @param chromiumSandbox chromium沙箱参数
     */
    public BuiltinWebToolService(
        ConnectorEndpointPolicy endpointPolicy,
        KnowledgeDocumentParser documentParser,
        GeneratedFileService generatedFileService,
        JsonMapper jsonMapper,
        @Value("${agent.platform.web-tools.chromium-executable:}") String chromiumExecutable,
        @Value("${agent.platform.web-tools.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${agent.platform.web-tools.request-timeout-ms:30000}") int requestTimeoutMs,
        @Value("${agent.platform.web-tools.max-response-bytes:524288}") int maxResponseBytes,
        @Value("${agent.platform.web-tools.chromium-timeout-ms:30000}") int chromiumTimeoutMs,
        @Value("${agent.platform.web-tools.chromium-sandbox:false}") boolean chromiumSandbox
    ) {
        this.endpointPolicy = endpointPolicy;
        this.documentParser = documentParser;
        this.generatedFileService = generatedFileService;
        this.jsonMapper = jsonMapper;
        this.chromiumExecutable = configuredPath(chromiumExecutable);
        this.connectTimeoutMs = boundedConfiguration(
            connectTimeoutMs, 250, 30_000, "web-tools connect-timeout-ms"
        );
        this.requestTimeoutMs = boundedConfiguration(
            requestTimeoutMs, 1_000, 120_000, "web-tools request-timeout-ms"
        );
        this.maxResponseBytes = boundedConfiguration(
            maxResponseBytes, 16 * 1024, 768 * 1024, "web-tools max-response-bytes"
        );
        this.chromiumTimeoutMs = boundedConfiguration(
            chromiumTimeoutMs, 5_000, 120_000, "web-tools chromium-timeout-ms"
        );
        this.chromiumSandbox = chromiumSandbox;
    }

    /**
     * 处理{@code chromiumAvailable}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean chromiumAvailable() {
        if (chromiumExecutable == null) {
            return false;
        }
        try {
            Path real = chromiumExecutable.toRealPath();
            return Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(real);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 处理系统HttpRequest并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> systemHttpRequest(Map<String, Object> arguments) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String method = requiredText(value(arguments, "method"), "method", 16)
            .toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw badRequest("system_http_request 仅支持 GET/POST/PUT/PATCH/DELETE");
        }
        URI target = externalTarget(requiredText(value(arguments, "url"), "url", MAX_URL_LENGTH));
        target = appendQuery(target, objectMap(value(arguments, "params"), "params"));
        Map<String, String> headers = headers(value(arguments, "headers"));
        Object body = value(arguments, "body");
        if (!BODY_METHODS.contains(method) && body != null) {
            throw badRequest(method + " 请求不能携带 body");
        }
        if (body != null) {
            String declaredContentType = headers.entrySet().stream()
                .filter(entry -> "content-type".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            if (declaredContentType != null
                && !declaredContentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw badRequest("带 body 的 Web 工具请求仅支持 application/json");
            }
        }
        return execute(method, target, headers, body, false);
    }

    /**
     * 处理{@code fetchStaticWebUrl}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> fetchStaticWebUrl(Map<String, Object> arguments) {
        URI target = externalTarget(requiredText(value(arguments, "url"), "url", MAX_URL_LENGTH));
        Map<String, Object> response = execute("GET", target, Map.of(), null, true);
        String contentType = String.valueOf(response.get("content_type"));
        if (!isReadableContentType(contentType)
            && !isLikelyTextUrl(String.valueOf(response.get("url")))) {
            throw new ServiceException(
                "静态抓取仅支持 HTML、JSON、XML、Markdown 和纯文本", HttpStatus.UNSUPPORTED_TYPE
            );
        }
        Object raw = response.get("content");
        if (raw instanceof String text && !isJsonContentType(contentType)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String extracted = extractText(bytes, contentType, fileName((String) response.get("url")));
            response.put("content", extracted);
            response.put("extracted", true);
        }
        return new LinkedHashMap<>(response);
    }

    /**
     * 处理renderAnd快照并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> renderAndSnapshot(Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!chromiumAvailable()) {
            throw unavailable(
                "web_renderer_and_snapshot",
                "Chromium 未配置；请设置 agent.platform.web-tools.chromium-executable 为可执行文件的绝对路径"
            );
        }
        URI target = externalTarget(requiredText(value(arguments, "url"), "url", MAX_URL_LENGTH));
        endpointPolicy.validateNetworkTarget(policyEndpoint(target));

        Path temporaryDirectory = null;
        AtomicReference<String> blockedUrl = new AtomicReference<>();
        try {
            temporaryDirectory = Files.createTempDirectory("agent-web-render-");
            Path screenshot = temporaryDirectory.resolve("snapshot.png");
            String pageHtml;
            String finalUrl;
            Integer statusCode;
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setChromiumSandbox(chromiumSandbox)
                     .setExecutablePath(chromiumExecutable.toRealPath())
                     .setTimeout(chromiumTimeoutMs));
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                     .setViewportSize(1280, 800)
                     .setUserAgent(
                         "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                             + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                     ))) {
                context.route("**/*", route -> {
                    try {
                        externalTarget(route.request().url());
                        route.resume();
                    } catch (RuntimeException exception) {
                        blockedUrl.compareAndSet(null, route.request().url());
                        route.abort("blockedbyclient");
                    }
                });
                context.routeWebSocket("**/*", socket -> socket.close());
                Page page = context.newPage();
                Response response = page.navigate(target.toString(), new Page.NavigateOptions()
                    .setTimeout(chromiumTimeoutMs)
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshot)
                    .setFullPage(false)
                    .setTimeout(chromiumTimeoutMs));
                pageHtml = page.content();
                finalUrl = page.url();
                statusCode = response == null ? null : response.status();
            }
            if (blockedUrl.get() != null) {
                throw new ServiceException("Chromium 请求被公网 Endpoint 策略拦截", HttpStatus.FORBIDDEN);
            }
            if (!Files.isRegularFile(screenshot, LinkOption.NOFOLLOW_LINKS)) {
                throw new ServiceException("Chromium 未生成截图", 502);
            }
            long screenshotSize = Files.size(screenshot);
            if (screenshotSize <= 0 || screenshotSize > 10L * 1024 * 1024) {
                throw new ServiceException("Chromium 截图为空或超过10MB限制", 502);
            }
            GeneratedFileService.PublishedFile published = generatedFileService.publish(
                screenshot, "web-snapshot.png"
            );
            byte[] domBytes = pageHtml.getBytes(StandardCharsets.UTF_8);
            boolean truncated = domBytes.length > MAX_RENDER_OUTPUT_BYTES;
            if (truncated) {
                domBytes = Arrays.copyOf(domBytes, MAX_RENDER_OUTPUT_BYTES);
            }
            String text = extractText(domBytes, "text/html; charset=UTF-8", "rendered-page.html");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", finalUrl == null || finalUrl.isBlank() ? target.toString() : finalUrl);
            if (statusCode != null) {
                result.put("status_code", statusCode);
            }
            result.put("text", text);
            result.put("snapshot", published.toolPayload());
            result.put("renderer", "managed_chromium");
            result.put("truncated", truncated);
            return Map.copyOf(result);
        } catch (PlaywrightException exception) {
            if (blockedUrl.get() != null) {
                throw new ServiceException("Chromium 请求被公网 Endpoint 策略拦截", HttpStatus.FORBIDDEN);
            }
            if (exception.getMessage() != null && exception.getMessage().toLowerCase(Locale.ROOT)
                .contains("timeout")) {
                throw new ServiceException("Chromium 网页渲染超时", 504);
            }
            throw new ServiceException("Chromium 网页渲染失败", 502);
        } catch (IOException exception) {
            throw new ServiceException("Chromium 网页渲染无法启动", 502);
        } finally {
            removeTemporaryTree(temporaryDirectory);
        }
    }

    /**
     * 处理{@code lintSyntax}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> lintSyntax(Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String code = requiredCode(value(arguments, "code"));
        String language = optionalText(value(arguments, "language"));
        language = language == null ? "python" : language.toLowerCase(Locale.ROOT);
        if (!"python".equals(language)) {
            return Map.of(
                "language", language,
                "supported", false,
                "valid", false,
                "message", "当前静态语法检测仅支持 Python"
            );
        }
        if (code.indexOf('\0') >= 0) {
            throw badRequest("code 包含 NUL 字符");
        }
        try {
            TSParser parser = new TSParser();
            TreeSitterPython python = new TreeSitterPython();
            if (!parser.setLanguage(python)) {
                throw unavailable("code_syntax_linter", "Python 语法解析器版本不兼容");
            }
            TSTree tree = parser.parseString(null, code);
            if (tree == null) {
                throw unavailable("code_syntax_linter", "Python 语法解析器没有返回语法树");
            }
            TSNode root = tree.getRootNode();
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            if (root.hasError()) {
                collectDiagnostics(root, code, diagnostics);
            }
            boolean valid = diagnostics.isEmpty() && !root.hasError();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("language", language);
            result.put("supported", true);
            result.put("valid", valid);
            result.put("diagnostics", List.copyOf(diagnostics));
            result.put(
                "message",
                valid ? "静态语法检测通过，未发现 Python 语法错误"
                    : "发现 " + diagnostics.size() + " 个 Python 语法错误"
            );
            return Map.copyOf(result);
        } catch (ServiceException exception) {
            throw exception;
        } catch (LinkageError | RuntimeException exception) {
            throw unavailable("code_syntax_linter", "Python 语法解析器无法加载");
        }
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param method {@code method}参数
     * @param initialTarget {@code initialTarget}参数
     * @param headers {@code headers}参数
     * @param body {@code body}参数
     * @param followRedirects {@code followRedirects}参数
     * @return 处理结果
     */
    private Map<String, Object> execute(
        String method,
        URI initialTarget,
        Map<String, String> headers,
        Object body,
        boolean followRedirects
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        URI target = initialTarget;
        for (int redirect = 0; redirect <= (followRedirects ? MAX_REDIRECTS : 0); redirect++) {
            endpointPolicy.validateNetworkTarget(policyEndpoint(target));
            HttpRequest request = request(method, target, headers, body);
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServiceException("Web 工具请求被中断", 502);
            } catch (IOException exception) {
                throw new ServiceException("Web 工具连接失败", 502);
            }
            int statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode < 400) {
                close(response.body());
                if (!followRedirects) {
                    throw new ServiceException("system_http_request 禁止 HTTP 重定向", 502);
                }
                String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new ServiceException("Web 重定向缺少 Location", 502));
                target = externalTarget(target.resolve(location).toString());
                if (redirect == MAX_REDIRECTS) {
                    throw new ServiceException("Web 重定向次数超过限制", 502);
                }
                continue;
            }
            byte[] bytes = boundedBody(response.body());
            String contentType = normalizedContentType(
                response.headers().firstValue("Content-Type").orElse("application/octet-stream")
            );
            Object content = responseContent(bytes, contentType);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", target.toString());
            result.put("status_code", statusCode);
            result.put("ok", statusCode >= 200 && statusCode < 300);
            result.put("content_type", contentType);
            result.put("content", content);
            result.put("size", bytes.length);
            return result;
        }
        throw new ServiceException("Web 请求没有返回响应", 502);
    }

    /**
     * 处理{@code request}并返回对应结果。
     *
     * @param method {@code method}参数
     * @param target {@code target}参数
     * @param headers {@code headers}参数
     * @param body {@code body}参数
     * @return 处理结果
     */
    private HttpRequest request(
        String method,
        URI target,
        Map<String, String> headers,
        Object body
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .header("Accept", "application/json, text/html, text/plain;q=0.9, */*;q=0.1")
            .header("User-Agent", "nhs-web-tool/1.0");
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(body), StandardCharsets.UTF_8
                ));
        }
        return request.build();
    }

    /**
     * 处理{@code boundedBody}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private byte[] boundedBody(InputStream input) {
        try (InputStream stream = input) {
            byte[] body = stream.readNBytes(maxResponseBytes + 1);
            if (body.length > maxResponseBytes) {
                throw new ServiceException("Web 工具响应超过大小限制", 502);
            }
            return body;
        } catch (IOException exception) {
            throw new ServiceException("Web 工具响应读取失败", 502);
        }
    }

    /**
     * 处理{@code responseContent}并返回对应结果。
     *
     * @param body {@code body}参数
     * @param contentType 业务类型
     * @return 处理结果
     */
    private Object responseContent(byte[] body, String contentType) {
        if (body.length == 0) {
            return "";
        }
        String text = decode(body, contentType);
        if (isJsonContentType(contentType)) {
            try {
                return jsonMapper.readValue(text, Object.class);
            } catch (RuntimeException ignored) {
                // Keep the upstream body visible when an endpoint declares JSON incorrectly.
            }
        }
        return text;
    }

    /**
     * 处理{@code externalTarget}并返回对应结果。
     *
     * @param rawUrl {@code rawUrl}参数
     * @return 处理结果
     */
    private URI externalTarget(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.strip();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH || value.indexOf('\\') >= 0) {
            throw badRequest("Web 工具 URL 无效");
        }
        URI target;
        try {
            target = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Web 工具 URL 无效");
        }
        if (!target.isAbsolute() || target.getHost() == null || target.getUserInfo() != null
            || target.getFragment() != null || target.getPort() == 0) {
            throw badRequest("Web 工具 URL 必须是无凭据、无片段的绝对 HTTP/HTTPS URL");
        }
        URI endpoint = policyEndpoint(target);
        endpointPolicy.validateNetworkTarget(endpoint);
        return target;
    }

    /**
     * 处理策略Endpoint并返回对应结果。
     *
     * @param target {@code target}参数
     * @return 处理结果
     */
    private URI policyEndpoint(URI target) {
        String path = target.getRawPath();
        String raw = target.getScheme() + "://" + target.getRawAuthority()
            + (path == null || path.isEmpty() ? "/" : path);
        return endpointPolicy.normalize(raw);
    }

    /**
     * 处理append查询并返回对应结果。
     *
     * @param target {@code target}参数
     * @param params {@code params}参数
     * @return 处理结果
     */
    private URI appendQuery(URI target, Map<String, Object> params) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (params.isEmpty()) {
            return target;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.isBlank() || key.length() > 256) {
                throw badRequest("params 参数名无效");
            }
            Object raw = entry.getValue();
            if (raw instanceof List<?> values) {
                for (Object value : values) {
                    pairs.add(queryPair(key, scalar(value)));
                    if (pairs.size() > MAX_QUERY_VALUES) {
                        throw badRequest("params 参数数量超过限制");
                    }
                }
            } else {
                pairs.add(queryPair(key, scalar(raw)));
            }
        }
        String query = String.join("&", pairs);
        String existing = target.getRawQuery();
        String raw = target.getScheme() + "://" + target.getRawAuthority()
            + (target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath())
            + "?" + (existing == null || existing.isEmpty() ? query : existing + "&" + query);
        if (raw.length() > MAX_URL_LENGTH) {
            throw badRequest("Web 工具 URL 超过长度限制");
        }
        return externalTarget(raw);
    }

    /**
     * 获取{@code Pair}。
     *
     * @param key {@code key}参数
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String queryPair(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        throw badRequest("params 仅支持标量或标量数组");
    }

    /**
     * 处理{@code headers}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, String> headers(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> source = objectMap(value, "headers");
        if (source.size() > MAX_HEADERS) {
            throw badRequest("headers 数量超过限制");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches() || BLOCKED_HEADERS.contains(lower)) {
                throw badRequest("headers 包含不允许的请求头: " + name);
            }
            if (!(entry.getValue() instanceof String headerValue)
                || headerValue.length() > MAX_HEADER_VALUE_LENGTH
                || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0
                || headerValue.indexOf('\0') >= 0) {
                throw badRequest("请求头值无效: " + name);
            }
            result.put(name, headerValue);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 处理{@code objectMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> objectMap(Object value, String label) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw badRequest(label + " 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw badRequest(label + " 的字段名必须是字符串");
            }
            result.put(key, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 处理{@code extractText}并返回对应结果。
     *
     * @param bytes {@code bytes}参数
     * @param contentType 业务类型
     * @param fileName 名称
     * @return 处理结果
     */
    private String extractText(byte[] bytes, String contentType, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            KnowledgeDocumentParser.ParsedDocument parsed = documentParser.parse(
                new ByteArrayInputStream(bytes), fileName, contentType
            );
            String content = parsed.content();
            return content.length() <= 30_000 ? content : content.substring(0, 30_000);
        } catch (IllegalArgumentException exception) {
            String fallback = decode(bytes, contentType).replace('\0', ' ').strip();
            return fallback.length() <= 30_000 ? fallback : fallback.substring(0, 30_000);
        }
    }

    /**
     * 处理{@code collectDiagnostics}相关逻辑。
     *
     * @param node {@code node}参数
     * @param code {@code code}参数
     * @param diagnostics {@code diagnostics}参数
     */
    private void collectDiagnostics(
        TSNode node,
        String code,
        List<Map<String, Object>> diagnostics
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (diagnostics.size() >= MAX_DIAGNOSTICS) {
            return;
        }
        if (node.isError() || node.isMissing()) {
            TSPoint point = node.getStartPoint();
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("type", node.isMissing() ? "missing" : "syntax_error");
            diagnostic.put("node", node.getType());
            diagnostic.put("line", point.getRow() + 1);
            diagnostic.put("column", point.getColumn() + 1);
            diagnostic.put("snippet", lineAt(code, point.getRow()));
            diagnostics.add(Map.copyOf(diagnostic));
            return;
        }
        if (!node.hasError()) {
            return;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectDiagnostics(node.getChild(index), code, diagnostics);
            if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                return;
            }
        }
    }

    /**
     * 处理{@code lineAt}并返回对应结果。
     *
     * @param code {@code code}参数
     * @param zeroBasedLine {@code zeroBasedLine}参数
     * @return 处理结果
     */
    private String lineAt(String code, int zeroBasedLine) {
        int start = 0;
        int current = 0;
        while (current < zeroBasedLine) {
            int newline = code.indexOf('\n', start);
            if (newline < 0) {
                return "";
            }
            start = newline + 1;
            current++;
        }
        int end = code.indexOf('\n', start);
        String line = code.substring(start, end < 0 ? code.length() : end).replace('\r', ' ');
        return line.length() <= 500 ? line : line.substring(0, 500);
    }

    /**
     * 处理{@code decode}并返回对应结果。
     *
     * @param bytes {@code bytes}参数
     * @param contentType 业务类型
     * @return 处理结果
     */
    private String decode(byte[] bytes, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        Matcher matcher = CHARSET.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try {
                charset = Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                // UTF-8 is the deterministic fallback for an invalid declared charset.
            }
        }
        return new String(bytes, charset);
    }

    /**
     * 处理{@code normalizedContentType}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizedContentType(String value) {
        String contentType = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return contentType.length() <= 255 ? contentType : contentType.substring(0, 255);
    }

    /**
     * 判断{@code JsonContentType}是否满足要求。
     *
     * @param contentType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isJsonContentType(String contentType) {
        String mediaType = mediaType(contentType);
        return "application/json".equals(mediaType) || mediaType.endsWith("+json");
    }

    /**
     * 判断{@code ReadableContentType}是否满足要求。
     *
     * @param contentType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isReadableContentType(String contentType) {
        String mediaType = mediaType(contentType);
        return mediaType.startsWith("text/") || isJsonContentType(contentType)
            || mediaType.endsWith("+xml") || Set.of(
                "application/xml", "application/xhtml+xml", "application/javascript",
                "application/x-javascript", "application/markdown"
            ).contains(mediaType);
    }

    /**
     * 判断{@code LikelyTextUrl}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isLikelyTextUrl(String value) {
        try {
            String path = URI.create(value).getPath();
            if (path == null) {
                return false;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".txt")
                || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".js") || lower.endsWith(".css");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 处理{@code mediaType}并返回对应结果。
     *
     * @param contentType 业务类型
     * @return 处理结果
     */
    private String mediaType(String contentType) {
        int separator = contentType == null ? -1 : contentType.indexOf(';');
        return (separator < 0 ? String.valueOf(contentType) : contentType.substring(0, separator))
            .strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理文件Name并返回对应结果。
     *
     * @param url {@code url}参数
     * @return 处理结果
     */
    private String fileName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank() || path.endsWith("/")) {
                return "index.html";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isBlank() || name.length() > 255 ? "index.html" : name;
        } catch (RuntimeException exception) {
            return "index.html";
        }
    }

    /**
     * 处理{@code close}相关逻辑。
     *
     * @param input {@code input}参数
     */
    private void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The connection will be discarded by HttpClient after a failed redirect response.
        }
    }

    /**
     * 删除{@code TemporaryTree}。
     *
     * @param root {@code root}参数
     */
    private void removeTemporaryTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Renderer cleanup is best effort; generated artifacts are already copied out.
                }
            });
        } catch (IOException ignored) {
            // Renderer cleanup is best effort.
        }
    }

    /**
     * 处理{@code configuredPath}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Path configuredPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value.strip()).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                "agent.platform.web-tools.chromium-executable 必须是绝对路径"
            );
        }
        return path;
    }

    /**
     * 处理bounded配置并返回对应结果。
     *
     * @param value {@code value}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int boundedConfiguration(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " 超出允许范围");
        }
        return value;
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Object value(Map<String, Object> arguments, String key) {
        return arguments == null ? null : arguments.get(key);
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximumLength {@code maximumLength}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label, int maximumLength) {
        String text = optionalText(value);
        if (text == null || text.length() > maximumLength) {
            throw badRequest(label + " 为空或超过长度限制");
        }
        return text;
    }

    /**
     * 校验{@code dCode}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredCode(Object value) {
        if (!(value instanceof String code) || code.isEmpty()
            || code.length() > MAX_CODE_CHARACTERS) {
            throw badRequest("code 为空或超过长度限制");
        }
        return code;
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
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param tool 工具参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private ServiceException unavailable(String tool, String reason) {
        return new ServiceException("tool_unavailable: " + tool + " (" + reason + ")", 503);
    }

}
