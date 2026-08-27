package group.aitools.nhs.platform.model.service;

import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.web.ModelConnectionView;
import group.aitools.nhs.platform.model.web.ModelOptionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示Http模型提供方相关的领域对象。
 * Bounded, no-redirect OpenAI-compatible provider client. */
@Component
public class HttpModelProviderClient implements ModelProviderClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_DISCOVERED_MODELS = 500;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ModelEndpointPolicy endpointPolicy;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpModelProviderClient(ModelEndpointPolicy endpointPolicy, JsonMapper jsonMapper) {
        this(
            endpointPolicy,
            jsonMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        );
    }

    /**
     * 创建 {@code HttpModelProviderClient} 实例并初始化所需依赖。
     *
     * @param endpointPolicy endpoint策略参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param httpClient http客户端参数
     */
    HttpModelProviderClient(
        ModelEndpointPolicy endpointPolicy,
        JsonMapper jsonMapper,
        HttpClient httpClient
    ) {
        this.endpointPolicy = endpointPolicy;
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param endpoint {@code endpoint}参数
     * @param credential 凭据参数
     * @return 符合条件的数据集合
     */
    @Override
    public List<ModelOptionView> discover(URI endpoint, String credential) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        ProviderResponse response = send("GET", appendPath(endpoint, "models"), credential, null);
        requireSuccess(response);
        try {
            JsonNode root = jsonMapper.readTree(response.body());
            JsonNode data = root != null && root.isArray() ? root : root == null ? null : root.get("data");
            if (data == null || !data.isArray()) {
                throw new ModelProviderException("供应商模型列表格式无法识别");
            }
            Map<String, ModelOptionView> options = new LinkedHashMap<>();
            for (JsonNode item : data) {
                if (options.size() >= MAX_DISCOVERED_MODELS) {
                    break;
                }
                String id;
                String name;
                if (item.isTextual()) {
                    id = item.asText().strip();
                    name = id;
                } else if (item.isObject()) {
                    id = firstText(item, "id", "model", "model_name");
                    name = firstText(item, "name");
                    if (name == null || name.isBlank()) {
                        name = id;
                    }
                } else {
                    continue;
                }
                if (id != null && !id.isBlank() && id.length() <= 255) {
                    options.putIfAbsent(id, new ModelOptionView(id, truncate(name, 255)));
                }
            }
            return List.copyOf(options.values());
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException("供应商模型列表不是有效 JSON");
        }
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param model 模型参数
     * @param endpoint {@code endpoint}参数
     * @param credential 凭据参数
     * @return 处理结果
     */
    @Override
    public ModelConnectionView test(AgentModel model, URI endpoint, String credential) {
        long started = System.nanoTime();
        try {
            TestRequest request = buildTestRequest(model, endpoint);
            ProviderResponse response = send("POST", request.uri(), credential, request.body());
            requireSuccess(response);
            String summary = validateTestResponse(model.getModelType(), response.body());
            return new ModelConnectionView(
                true,
                "连接成功",
                summary,
                elapsedMillis(started)
            );
        } catch (ModelProviderException exception) {
            return ModelConnectionView.failure(exception.getMessage(), elapsedMillis(started));
        } catch (RuntimeException exception) {
            return ModelConnectionView.failure("模型连通性测试失败", elapsedMillis(started));
        }
    }

    /**
     * 处理{@code describeImages}并返回对应结果。
     *
     * @param model 模型参数
     * @param endpoint {@code endpoint}参数
     * @param credential 凭据参数
     * @param systemPrompt 系统提示词参数
     * @param userPrompt 用户提示词参数
     * @param images {@code images}参数
     * @return 处理结果
     */
    @Override
    public String describeImages(
        AgentModel model,
        URI endpoint,
        String credential,
        String systemPrompt,
        String userPrompt,
        List<ModelImageInput> images
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (model == null || !"multimodal".equals(model.getModelType())) {
            throw new ModelProviderException("默认多模态模型不可用");
        }
        if (images == null || images.isEmpty() || images.size() > 5) {
            throw new ModelProviderException("图片输入数量无效");
        }
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "text", "text", boundedPrompt(userPrompt, "识图提示词")));
        for (ModelImageInput image : images) {
            if (image == null || !Set.of("image/png", "image/jpeg", "image/webp").contains(image.mimeType())
                || image.base64() == null || image.base64().isBlank()
                || image.base64().length() > 16 * 1024 * 1024) {
                throw new ModelProviderException("图片输入无效");
            }
            try {
                java.util.Base64.getDecoder().decode(image.base64());
            } catch (IllegalArgumentException exception) {
                throw new ModelProviderException("图片输入不是有效 Base64");
            }
            content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + image.mimeType() + ";base64," + image.base64())
            ));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        body.put("messages", List.of(
            Map.of("role", "system", "content", boundedPrompt(systemPrompt, "识图系统提示词")),
            Map.of("role", "user", "content", List.copyOf(content))
        ));
        body.put("max_tokens", Math.min(2048, Math.max(128,
            model.getMaxOutputTokens() == null ? 1024 : model.getMaxOutputTokens())));
        body.put("temperature", 0.1);
        body.put("stream", false);
        ProviderResponse response = send(
            "POST", appendPath(endpoint, "chat/completions"), credential,
            jsonMapper.writeValueAsString(body)
        );
        requireSuccess(response);
        try {
            JsonNode root = jsonMapper.readTree(response.body());
            JsonNode choices = root == null ? null : root.get("choices");
            JsonNode message = choices != null && choices.isArray() && !choices.isEmpty()
                ? choices.get(0).get("message") : null;
            JsonNode contentNode = message == null ? null : message.get("content");
            String text = responseText(contentNode);
            if (text == null || text.isBlank()) {
                throw new ModelProviderException("多模态模型未返回有效图片描述");
            }
            return text.strip();
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException("多模态模型响应不是有效 JSON");
        }
    }

    /**
 * 处理{@code complete}并返回对应结果。
 * Executes one bounded, non-streaming chat completion and returns only assistant text. */
    public String complete(
        AgentModel model,
        URI endpoint,
        String credential,
        String systemPrompt,
        String userPrompt
    ) {
        return complete(model, endpoint, credential, systemPrompt, userPrompt, 1024);
    }

    /**
 * 处理{@code complete}并返回对应结果。
 * Executes a bounded completion with an explicit output budget for structured metadata. */
    public String complete(
        AgentModel model,
        URI endpoint,
        String credential,
        String systemPrompt,
        String userPrompt,
        int requestedMaxTokens
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (model == null || !List.of("chat", "multimodal").contains(model.getModelType())) {
            throw new ModelProviderException("数据门户需要可用的对话模型");
        }
        String system = boundedPrompt(systemPrompt, "系统提示词");
        String user = boundedPrompt(userPrompt, "用户提示词");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        body.put("messages", List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user)
        ));
        int configuredMaximum = model.getMaxOutputTokens() == null
            ? requestedMaxTokens : model.getMaxOutputTokens();
        body.put("max_tokens", Math.min(8192, Math.max(128,
            Math.min(requestedMaxTokens, configuredMaximum))));
        body.put("temperature", 0.2);
        body.put("stream", false);
        ProviderResponse response = send(
            "POST",
            appendPath(endpoint, "chat/completions"),
            credential,
            jsonMapper.writeValueAsString(body)
        );
        requireSuccess(response);
        try {
            JsonNode root = jsonMapper.readTree(response.body());
            JsonNode choices = root == null ? null : root.get("choices");
            JsonNode message = choices != null && choices.isArray() && !choices.isEmpty()
                ? choices.get(0).get("message") : null;
            JsonNode content = message == null ? null : message.get("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()) {
                throw new ModelProviderException("供应商未返回有效对话内容");
            }
            return content.asText().strip();
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException("供应商响应不是有效 JSON");
        }
    }

    /**
     * 处理bounded提示词并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String boundedPrompt(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 32_000 || normalized.indexOf('\0') >= 0) {
            throw new ModelProviderException(label + "无效或超过32000字符");
        }
        return normalized;
    }

    /**
     * 处理{@code responseText}并返回对应结果。
     *
     * @param content 待处理内容
     * @return 处理结果
     */
    private String responseText(JsonNode content) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode item : content) {
            JsonNode text = item == null ? null : item.get("text");
            if (text != null && text.isTextual()) {
                result.append(text.asText());
            }
        }
        return result.toString();
    }

    /**
     * 构建{@code TestRequest}。
     *
     * @param model 模型参数
     * @param endpoint {@code endpoint}参数
     * @return 处理结果
     */
    private TestRequest buildTestRequest(AgentModel model, URI endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        return switch (model.getModelType()) {
            case "chat", "multimodal" -> {
                body.put("messages", List.of(Map.of("role", "user", "content", "Reply with pong.")));
                body.put("max_tokens", 8);
                body.put("temperature", 0);
                body.put("stream", false);
                yield new TestRequest(
                    appendPath(endpoint, "chat/completions"),
                    jsonMapper.writeValueAsString(body)
                );
            }
            case "embedding" -> {
                body.put("input", "ping");
                yield new TestRequest(
                    appendPath(endpoint, "embeddings"),
                    jsonMapper.writeValueAsString(body)
                );
            }
            case "rerank" -> {
                body.put("query", "ping");
                body.put("documents", List.of("pong"));
                body.put("top_n", 1);
                yield new TestRequest(
                    appendPath(endpoint, "rerank"),
                    jsonMapper.writeValueAsString(body)
                );
            }
            default -> throw new ModelProviderException("不支持测试该模型类型");
        };
    }

    /**
     * 校验{@code TestResponse}，并在条件不满足时终止处理。
     *
     * @param modelType 业务类型
     * @param body {@code body}参数
     * @return 处理结果
     */
    private String validateTestResponse(String modelType, byte[] body) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ModelProviderException("供应商返回格式无法识别");
            }
            if ("embedding".equals(modelType)) {
                JsonNode data = root.get("data");
                JsonNode embedding = data != null && data.isArray() && !data.isEmpty()
                    ? data.get(0).get("embedding") : null;
                if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                    throw new ModelProviderException("供应商未返回有效向量");
                }
                return "embedding dimension=" + embedding.size();
            }
            if ("rerank".equals(modelType)) {
                JsonNode results = root.get("results");
                if (results == null || !results.isArray() || results.isEmpty()) {
                    throw new ModelProviderException("供应商未返回有效重排结果");
                }
                return "rerank result received";
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new ModelProviderException("供应商未返回有效对话结果");
            }
            return "chat completion received";
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException("供应商响应不是有效 JSON");
        }
    }

    /**
     * 处理{@code send}并返回对应结果。
     *
     * @param method {@code method}参数
     * @param uri {@code uri}参数
     * @param credential 凭据参数
     * @param body {@code body}参数
     * @return 处理结果
     */
    private ProviderResponse send(String method, URI uri, String credential, String body) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        endpointPolicy.validateNetworkTarget(uri);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + credential);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ModelProviderException("供应商响应超过 64KB 限制");
                }
                return new ProviderResponse(response.statusCode(), bytes);
            }
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("模型连通性测试已中断");
        } catch (IOException exception) {
            throw new ModelProviderException("无法连接模型供应商");
        }
    }

    /**
     * 校验{@code Success}，并在条件不满足时终止处理。
     *
     * @param response {@code response}参数
     */
    private void requireSuccess(ProviderResponse response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ModelProviderException("供应商鉴权失败，请检查凭证引用");
        }
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new ModelProviderException("供应商返回重定向，已按安全策略拒绝");
        }
        throw new ModelProviderException("供应商请求失败（HTTP " + response.statusCode() + "）");
    }

    /**
     * 处理{@code appendPath}并返回对应结果。
     *
     * @param endpoint {@code endpoint}参数
     * @param suffix {@code suffix}参数
     * @return 处理结果
     */
    private URI appendPath(URI endpoint, String suffix) {
        String base = endpoint.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + suffix);
    }

    /**
     * 处理{@code firstText}并返回对应结果。
     *
     * @param node {@code node}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return null;
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 处理{@code elapsedMillis}并返回对应结果。
     *
     * @param started {@code started}参数
     * @return 处理结果
     */
    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /**
     * 封装提供方相关的不可变数据。
     */
    private record ProviderResponse(int statusCode, byte[] body) {
    }

    /**
     * 封装{@code Test}相关的不可变数据。
     */
    private record TestRequest(URI uri, String body) {
    }
}
