package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 表示知识库Embedding相关的领域对象。
 * Bounded OpenAI-compatible embedding client used by parse and retrieval workers. */
@Component
public class KnowledgeEmbeddingClient {

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BATCH = 32;

    private final ModelEndpointPolicy endpointPolicy;
    private final ModelCredentialResolver credentialResolver;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public KnowledgeEmbeddingClient(
        ModelEndpointPolicy endpointPolicy,
        ModelCredentialResolver credentialResolver,
        JsonMapper jsonMapper
    ) {
        this.endpointPolicy = endpointPolicy;
        this.credentialResolver = credentialResolver;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    /**
     * 处理嵌入式会话并返回对应结果。
     *
     * @param model 模型参数
     * @param inputs {@code inputs}参数
     * @param expectedDimension {@code expectedDimension}参数
     * @return 符合条件的数据集合
     */
    public List<VectorValue> embed(AgentModel model, List<String> inputs, int expectedDimension) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (model == null || !"embedding".equals(model.getModelType())
            || !"active".equals(model.getStatus())) {
            throw new IllegalArgumentException("知识库向量模型不存在或未启用");
        }
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("向量批次必须包含 1-32 条文本");
        }
        for (String input : inputs) {
            if (input == null || input.isBlank() || input.length() > 16_000) {
                throw new IllegalArgumentException("向量输入为空或过长");
            }
        }
        URI endpoint = endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl());
        URI uri = URI.create((endpoint.toString().endsWith("/")
            ? endpoint.toString() : endpoint + "/") + "embeddings");
        endpointPolicy.validateNetworkTarget(uri);
        String credential = credentialResolver.resolve(model.getCredentialRef());
        String body = jsonMapper.writeValueAsString(Map.of(
            "model", model.getModelName(), "input", inputs
        ));
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + credential)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("向量模型响应超过 8MB 限制");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("向量模型请求失败（HTTP " + response.statusCode() + "）");
            }
            return parse(bytes, inputs.size(), expectedDimension);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量模型请求被中断");
        } catch (IOException exception) {
            throw new IllegalStateException("无法连接向量模型");
        }
    }

    /**
     * 处理嵌入式会话One并返回对应结果。
     *
     * @param model 模型参数
     * @param input {@code input}参数
     * @param expectedDimension {@code expectedDimension}参数
     * @return 处理结果
     */
    public VectorValue embedOne(AgentModel model, String input, int expectedDimension) {
        return embed(model, List.of(input), expectedDimension).getFirst();
    }

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param bytes {@code bytes}参数
     * @param expectedCount {@code expectedCount}参数
     * @param expectedDimension {@code expectedDimension}参数
     * @return 符合条件的数据集合
     */
    private List<VectorValue> parse(byte[] bytes, int expectedCount, int expectedDimension) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        JsonNode root = jsonMapper.readTree(bytes);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw new IllegalStateException("向量模型返回数量不一致");
        }
        List<IndexedVector> indexed = new ArrayList<>();
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.size() != expectedDimension) {
                throw new IllegalStateException("向量模型返回维度不一致");
            }
            List<Double> values = new ArrayList<>(expectedDimension);
            for (JsonNode number : embedding) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble())) {
                    throw new IllegalStateException("向量模型返回了无效数值");
                }
                values.add(number.asDouble());
            }
            JsonNode indexNode = item.get("index");
            int index = indexNode != null && indexNode.isIntegralNumber()
                ? indexNode.asInt() : fallbackIndex;
            if (index < 0 || index >= expectedCount) {
                throw new IllegalStateException("向量模型返回索引无效");
            }
            indexed.add(new IndexedVector(index, new VectorValue(List.copyOf(values))));
            fallbackIndex++;
        }
        indexed.sort(Comparator.comparingInt(IndexedVector::index));
        for (int index = 0; index < indexed.size(); index++) {
            if (indexed.get(index).index() != index) {
                throw new IllegalStateException("向量模型返回索引重复或缺失");
            }
        }
        return indexed.stream().map(IndexedVector::vector).toList();
    }

    /**
     * 封装{@code VectorValue}相关的不可变数据。
     */
    public record VectorValue(List<Double> values) {
        /**
         * 处理{@code postgresValue}并返回对应结果。
         *
         * @return 处理结果
         */
        public String postgresValue() {
            return values.toString().replace(" ", "");
        }
    }

    /**
     * 封装{@code IndexedVector}相关的不可变数据。
     */
    private record IndexedVector(int index, VectorValue vector) {
    }
}
