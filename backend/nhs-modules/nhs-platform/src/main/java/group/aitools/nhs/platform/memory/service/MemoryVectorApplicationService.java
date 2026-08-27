package group.aitools.nhs.platform.memory.service;

import group.aitools.nhs.platform.knowledge.service.KnowledgeEmbeddingClient;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.domain.MemoryRuntimeConfig;
import group.aitools.nhs.platform.memory.domain.MemoryEmbeddedRow;
import group.aitools.nhs.platform.memory.domain.MemoryVectorMatch;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责记忆Vector相关的业务编排与领域规则处理。
 * PostgreSQL/pgvector implementation of Nhs memory embedding and recall. */
@Service
public class MemoryVectorApplicationService {

    private static final int MAX_REBUILD_ROWS = 10_000;
    private static final int EMBEDDING_BATCH = 32;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MemoryCatalogMapper memoryMapper;
    private final AgentModelMapper modelMapper;
    private final KnowledgeEmbeddingClient embeddingClient;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code MemoryVectorApplicationService} 实例并初始化所需依赖。
     *
     * @param memoryMapper 记忆Mapper参数
     * @param modelMapper 模型Mapper参数
     * @param embeddingClient embedding客户端参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public MemoryVectorApplicationService(
        MemoryCatalogMapper memoryMapper,
        AgentModelMapper modelMapper,
        KnowledgeEmbeddingClient embeddingClient,
        JsonMapper jsonMapper
    ) {
        this.memoryMapper = memoryMapper;
        this.modelMapper = modelMapper;
        this.embeddingClient = embeddingClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 设置{@code tings}。
     *
     * @return 处理结果
     */
    public Settings settings() {
        return Settings.from(memoryMapper.selectRuntimeConfig());
    }

    /**
     * 更新{@code update}。
     *
     * @param patch {@code patch}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Settings update(SettingsPatch patch, Long userId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Settings current = settings();
        if (patch.expectedRevision() != null && !patch.expectedRevision().equals(current.revision())) {
            throw conflict("记忆配置已被其他管理员修改，请刷新后重试");
        }
        boolean enabled = value(patch.enabled(), current.enabled());
        boolean summaryEnabled = value(patch.summaryEnabled(), current.summaryEnabled());
        boolean embeddingEnabled = value(patch.embeddingEnabled(), current.embeddingEnabled());
        Long modelId = patch.embeddingModelId() == null
            ? current.embeddingModelId() : patch.embeddingModelId();
        Integer dimension = patch.embeddingDimension() == null
            ? current.embeddingDimension() : patch.embeddingDimension();
        if (!embeddingEnabled) {
            modelId = null;
            dimension = null;
        }
        validateEmbeddingModel(modelId, dimension, embeddingEnabled);

        MemoryRuntimeConfig row = new MemoryRuntimeConfig();
        row.setId((short) 1);
        row.setEnabled(enabled);
        row.setSummaryEnabled(summaryEnabled);
        row.setEmbeddingModelId(modelId);
        row.setEmbeddingDimension(dimension);
        row.setSearchKnnTopK(bounded(
            patch.searchKnnTopK(), current.searchKnnTopK(), 1, 200, "默认向量召回数量"
        ));
        row.setVectorWeight(decimal(
            patch.vectorWeight(), current.vectorWeight(), 0, 1, "向量检索权重"
        ));
        row.setConsolidationThreshold(decimal(
            patch.consolidationThreshold(), current.consolidationThreshold(), 0, 1, "记忆合并阈值"
        ));
        row.setBaseHalfLifeDays(decimal(
            patch.baseHalfLifeDays(), current.baseHalfLifeDays(), 0.01, 3650, "记忆半衰期"
        ));
        row.setSummaryTtlDays(bounded(
            patch.summaryTtlDays(), current.summaryTtlDays(), 1, 3650, "摘要保留天数"
        ));
        row.setRevisionNo(current.revision());
        row.setUpdatedBy(userId);
        row.setUpdatedAt(LocalDateTime.now());
        if (memoryMapper.updateRuntimeConfig(row) != 1) {
            throw conflict("记忆配置已被其他管理员修改，请刷新后重试");
        }
        return settings();
    }

    /**
     * 处理{@code testEmbedding}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> testEmbedding() {
        Settings settings = requireEmbeddingSettings();
        AgentModel model = requireEmbeddingModel(settings);
        long started = System.nanoTime();
        KnowledgeEmbeddingClient.VectorValue vector = embeddingClient.embedOne(
            model, "记忆服务连通性测试", settings.embeddingDimension()
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("provider", "postgres_pgvector");
        result.put("model_id", model.getId());
        result.put("model_name", model.getDisplayName());
        result.put("dimensions", vector.values().size());
        result.put("sample", vector.values().stream().limit(5).toList());
        result.put("latency_ms", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return result;
    }

    /**
     * 处理{@code vectorStoreStatus}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> vectorStoreStatus(String scopeType, Long scopeId) {
        Settings settings = settings();
        boolean extension = memoryMapper.hasVectorExtension();
        long embedded = settings.embeddingEnabled()
            ? memoryMapper.countEmbeddedMemories(
                scopeType, scopeId, settings.embeddingModelId(), settings.embeddingDimension()
            ) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", extension && settings.embeddingEnabled());
        result.put("provider", "postgres_pgvector");
        result.put("compatible_with", "nhs_redis_vector");
        result.put("vector_extension_present", extension);
        result.put("embedding_enabled", settings.embeddingEnabled());
        result.put("embedding_model_id", settings.embeddingModelId());
        result.put("embedding_dimension", settings.embeddingDimension());
        result.put("embedded_document_count", embedded);
        result.put("message", extension
            ? (settings.embeddingEnabled() ? "PostgreSQL pgvector 记忆检索可用" : "请先配置 Embedding 模型")
            : "PostgreSQL vector 扩展不可用");
        return result;
    }

    /**
     * 处理{@code rebuild}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param maximumRows {@code maximumRows}参数
     * @return 处理结果
     */
    public RebuildResult rebuild(String scopeType, Long scopeId, int maximumRows) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Settings settings = requireEmbeddingSettings();
        AgentModel model = requireEmbeddingModel(settings);
        if (!memoryMapper.hasVectorExtension()) {
            throw unavailable("PostgreSQL vector 扩展不可用");
        }
        int limit = Math.min(Math.max(maximumRows, 1), MAX_REBUILD_ROWS);
        int indexed = 0;
        while (indexed < limit) {
            int batchSize = Math.min(EMBEDDING_BATCH, limit - indexed);
            List<AgentMemory> batch = memoryMapper.selectMemoriesMissingEmbedding(
                scopeType, scopeId, model.getId(), settings.embeddingDimension(), batchSize
            );
            if (batch.isEmpty()) {
                break;
            }
            List<KnowledgeEmbeddingClient.VectorValue> vectors = embeddingClient.embed(
                model, batch.stream().map(AgentMemory::getContent).toList(), settings.embeddingDimension()
            );
            for (int index = 0; index < batch.size(); index++) {
                AgentMemory memory = batch.get(index);
                if (memoryMapper.updateEmbedding(
                    memory.getId(), model.getId(), settings.embeddingDimension(), vectors.get(index).postgresValue()
                ) != 1) {
                    throw conflict("记忆在向量写入前已被删除或修改");
                }
                indexed++;
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        boolean capped = indexed >= limit && !memoryMapper.selectMemoriesMissingEmbedding(
            scopeType, scopeId, model.getId(), settings.embeddingDimension(), 1
        ).isEmpty();
        return new RebuildResult(indexed, capped, model.getId(), settings.embeddingDimension());
    }

    /**
     * 处理{@code indexPendingBatch}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    public int indexPendingBatch(int limit) {
        Settings settings = settings();
        if (!settings.enabled() || !settings.embeddingEnabled() || !memoryMapper.hasVectorExtension()) {
            return 0;
        }
        return rebuild(null, null, Math.min(Math.max(limit, 1), EMBEDDING_BATCH)).indexed();
    }

    /**
     * 处理index记忆相关逻辑。
     *
     * @param memoryId 资源标识
     * @param content 待处理内容
     */
    public void indexMemory(Long memoryId, String content) {
        Settings settings = settings();
        if (!settings.enabled() || !settings.embeddingEnabled() || !memoryMapper.hasVectorExtension()) {
            return;
        }
        AgentModel model = requireEmbeddingModel(settings);
        KnowledgeEmbeddingClient.VectorValue vector = embeddingClient.embedOne(
            model, content, settings.embeddingDimension()
        );
        memoryMapper.updateEmbedding(
            memoryId, model.getId(), settings.embeddingDimension(), vector.postgresValue()
        );
    }

    /**
 * 处理index记忆Required相关逻辑。
 *
     * Required indexing path for consolidation. Best-effort maintenance may
     * skip an unavailable provider, but consolidation must fail closed before
     * its source rows are removed.
     */
    public void indexMemoryRequired(Long memoryId, String content) {
        Settings settings = settings();
        if (!settings.enabled() || !settings.embeddingEnabled()) {
            throw unavailable("记忆向量服务未启用");
        }
        if (!memoryMapper.hasVectorExtension()) {
            throw unavailable("PostgreSQL vector 扩展不可用，未删除原记忆");
        }
        AgentModel model = requireEmbeddingModel(settings);
        KnowledgeEmbeddingClient.VectorValue vector = embeddingClient.embedOne(
            model, content, settings.embeddingDimension()
        );
        if (memoryMapper.updateEmbedding(
            memoryId, model.getId(), settings.embeddingDimension(), vector.postgresValue()
        ) != 1) {
            throw conflict("合并记忆在向量写入前已被删除或修改");
        }
    }

    /**
     * 处理embedded会话Memories并返回对应结果。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<EmbeddedMemory> embeddedSessionMemories(Long userId, int limit) {
        Settings settings = settings();
        if (!settings.embeddingEnabled() || !memoryMapper.hasVectorExtension()) {
            return List.of();
        }
        return memoryMapper.selectEmbeddedSessionMemories(
                "user", userId, settings.embeddingModelId(), settings.embeddingDimension(),
                Math.min(Math.max(limit, 1), 500)
            ).stream()
            .map(row -> new EmbeddedMemory(
                row.getId(), row.getMemoryKey(), row.getContent(), row.getMetadataJson(),
                parseVector(row.getEmbedding()), row.getRevisionNo(), row.getUpdatedAt()
            ))
            .filter(value -> !value.vector().isEmpty())
            .toList();
    }

    /**
     * 处理{@code parseVector}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Double> parseVector(String value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return List.of();
        }
        List<Double> result = new ArrayList<>();
        for (String item : normalized.split(",")) {
            try {
                double number = Double.parseDouble(item.strip());
                if (!Double.isFinite(number)) {
                    return List.of();
                }
                result.add(number);
            } catch (RuntimeException exception) {
                return List.of();
            }
        }
        return List.copyOf(result);
    }

    /**
     * 查询{@code search}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param query 查询参数
     * @param requestedLimit 数量上限
     * @return 符合条件的数据集合
     */
    public List<SearchHit> search(String scopeType, Long scopeId, String query, int requestedLimit) {
        Settings settings = requireEmbeddingSettings();
        AgentModel model = requireEmbeddingModel(settings);
        int limit = Math.min(Math.max(requestedLimit, 1), 200);
        KnowledgeEmbeddingClient.VectorValue queryVector = embeddingClient.embedOne(
            model, query, settings.embeddingDimension()
        );
        List<MemoryVectorMatch> matches = memoryMapper.searchByVector(
            scopeType, scopeId, model.getId(), settings.embeddingDimension(),
            queryVector.postgresValue(), limit
        );
        LocalDateTime now = LocalDateTime.now();
        List<SearchHit> result = new ArrayList<>(matches.size());
        for (MemoryVectorMatch match : matches) {
            Map<String, Object> metadata = metadata(match.getMetadataJson());
            int references = integer(metadata.get("reference_count"), 0);
            double vectorScore = match.getVectorScore() == null ? 0 : clamp(match.getVectorScore());
            double ageDays = match.getUpdatedAt() == null ? 0
                : Math.max(0, Duration.between(match.getUpdatedAt(), now).toSeconds() / 86_400.0);
            double strength = settings.baseHalfLifeDays() * (1 + Math.log1p(Math.max(0, references)));
            double retention = Math.exp(-ageDays / strength);
            result.add(new SearchHit(
                match.getId(), match.getMemoryKey(), match.getMemoryType(), match.getContent(),
                match.getSourceType(), match.getSourceId(), match.getConfidence(),
                match.getSensitiveLevel(), metadata, match.getExpiresAt(), match.getUpdatedAt(),
                vectorScore, clamp(vectorScore * retention), references
            ));
        }
        result.sort((left, right) -> Double.compare(right.finalScore(), left.finalScore()));
        return List.copyOf(result);
    }

    /**
     * 校验{@code EmbeddingSettings}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private Settings requireEmbeddingSettings() {
        Settings settings = settings();
        if (!settings.enabled()) {
            throw unavailable("记忆服务未启用");
        }
        if (!settings.embeddingEnabled()) {
            throw unavailable("当前未配置可用的 Embedding 模型");
        }
        return settings;
    }

    /**
     * 校验Embedding模型，并在条件不满足时终止处理。
     *
     * @param settings {@code settings}参数
     * @return 处理结果
     */
    private AgentModel requireEmbeddingModel(Settings settings) {
        AgentModel model = modelMapper.selectModelById(settings.embeddingModelId());
        if (model == null || !"embedding".equals(model.getModelType()) || !"active".equals(model.getStatus())) {
            throw unavailable("配置的 Embedding 模型不存在或未启用");
        }
        return model;
    }

    /**
     * 校验Embedding模型，并在条件不满足时终止处理。
     *
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param enabled {@code enabled}参数
     */
    private void validateEmbeddingModel(Long modelId, Integer dimension, boolean enabled) {
        if (!enabled) {
            return;
        }
        if (modelId == null || modelId <= 0 || dimension == null || dimension < 1 || dimension > 8192) {
            throw badRequest("启用向量记忆时必须选择 Embedding 模型并填写 1-8192 的维度");
        }
        AgentModel model = modelMapper.selectModelById(modelId);
        if (model == null || !"embedding".equals(model.getModelType()) || !"active".equals(model.getStatus())) {
            throw badRequest("选择的 Embedding 模型不存在、类型不符或未启用");
        }
    }

    /**
     * 处理元数据并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> metadata(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = jsonMapper.readValue(value, MAP_TYPE);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param fallback {@code fallback}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean value(Boolean requested, boolean fallback) {
        return requested == null ? fallback : requested;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param fallback {@code fallback}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int bounded(Integer requested, int fallback, int minimum, int maximum, String label) {
        int value = requested == null ? fallback : requested;
        if (value < minimum || value > maximum) {
            throw badRequest(label + "必须在" + minimum + "到" + maximum + "之间");
        }
        return value;
    }

    /**
     * 处理{@code decimal}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param fallback {@code fallback}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private double decimal(Double requested, double fallback, double minimum, double maximum, String label) {
        double value = requested == null ? fallback : requested;
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw badRequest(label + "必须在" + minimum + "到" + maximum + "之间");
        }
        return value;
    }

    /**
     * 处理{@code clamp}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
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
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
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

    /**
     * 封装{@code Settings}相关的不可变数据。
     */
    public record Settings(
        boolean enabled,
        boolean summaryEnabled,
        Long embeddingModelId,
        Integer embeddingDimension,
        int searchKnnTopK,
        double vectorWeight,
        double consolidationThreshold,
        double baseHalfLifeDays,
        int summaryTtlDays,
        long revision,
        Long updatedBy,
        LocalDateTime updatedAt
    ) {
        /**
         * 处理{@code from}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        static Settings from(MemoryRuntimeConfig value) {
            if (value == null) {
                return new Settings(true, true, null, null, 5, 0.7, 0.82, 7, 30, 1, null, null);
            }
            return new Settings(
                !Boolean.FALSE.equals(value.getEnabled()),
                !Boolean.FALSE.equals(value.getSummaryEnabled()),
                value.getEmbeddingModelId(), value.getEmbeddingDimension(),
                value.getSearchKnnTopK() == null ? 5 : value.getSearchKnnTopK(),
                value.getVectorWeight() == null ? 0.7 : value.getVectorWeight(),
                value.getConsolidationThreshold() == null ? 0.82 : value.getConsolidationThreshold(),
                value.getBaseHalfLifeDays() == null ? 7 : value.getBaseHalfLifeDays(),
                value.getSummaryTtlDays() == null ? 30 : value.getSummaryTtlDays(),
                value.getRevisionNo() == null ? 1 : value.getRevisionNo(),
                value.getUpdatedBy(), value.getUpdatedAt()
            );
        }

        /**
         * 处理{@code embeddingEnabled}并返回对应结果。
         *
         * @return 判断结果，{@code true} 表示条件成立
         */
        public boolean embeddingEnabled() {
            return embeddingModelId != null && embeddingDimension != null;
        }
    }

    /**
     * 封装{@code SettingsPatch}相关的不可变数据。
     */
    public record SettingsPatch(
        Boolean enabled,
        Boolean summaryEnabled,
        Boolean embeddingEnabled,
        Long embeddingModelId,
        Integer embeddingDimension,
        Integer searchKnnTopK,
        Double vectorWeight,
        Double consolidationThreshold,
        Double baseHalfLifeDays,
        Integer summaryTtlDays,
        Long expectedRevision
    ) {
    }

    /**
     * 封装{@code Rebuild}相关的不可变数据。
     */
    public record RebuildResult(int indexed, boolean capped, Long modelId, int dimension) {
    }

    /**
     * 封装{@code SearchHit}相关的不可变数据。
     */
    public record SearchHit(
        Long id,
        String memoryKey,
        String memoryType,
        String content,
        String sourceType,
        Long sourceId,
        Double confidence,
        String sensitiveLevel,
        Map<String, Object> metadata,
        LocalDateTime expiresAt,
        LocalDateTime updatedAt,
        double vectorScore,
        double finalScore,
        int referenceCount
    ) {
    }

    /**
     * 封装Embedded记忆相关的不可变数据。
     */
    public record EmbeddedMemory(
        Long id,
        String memoryKey,
        String content,
        String metadataJson,
        List<Double> vector,
        Long revision,
        LocalDateTime updatedAt
    ) {
    }
}
