package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeChunk;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示知识库Parse作业工作进程相关的领域对象。
 */
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.knowledge",
    name = "worker-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class KnowledgeParseJobWorker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_CHUNKS = 5000;

    private final String workerId = "knowledge-" + ManagementFactory.getRuntimeMXBean().getName();
    private final KnowledgeParsePersistenceService persistence;
    private final KnowledgeCatalogMapper mapper;
    private final KnowledgeFileStorage storage;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeChunker chunker;
    private final AgentModelMapper modelMapper;
    private final KnowledgeEmbeddingClient embeddingClient;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code KnowledgeParseJobWorker} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param mapper {@code mapper}参数
     * @param storage 存储参数
     * @param parser {@code parser}参数
     * @param chunker {@code chunker}参数
     * @param modelMapper 模型Mapper参数
     * @param embeddingClient embedding客户端参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public KnowledgeParseJobWorker(
        KnowledgeParsePersistenceService persistence,
        KnowledgeCatalogMapper mapper,
        KnowledgeFileStorage storage,
        KnowledgeDocumentParser parser,
        KnowledgeChunker chunker,
        AgentModelMapper modelMapper,
        KnowledgeEmbeddingClient embeddingClient,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.persistence = persistence;
        this.mapper = mapper;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.modelMapper = modelMapper;
        this.embeddingClient = embeddingClient;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code poll}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.knowledge.worker-delay-ms:2000}",
        initialDelayString = "${agent.platform.knowledge.worker-initial-delay-ms:5000}"
    )
    public void poll() {
        for (int count = 0; count < 3; count++) {
            KnowledgeParseJobRow job = persistence.claim(workerId);
            if (job == null) {
                return;
            }
            process(job);
        }
    }

    /**
     * 执行{@code process}相关的处理流程。
     *
     * @param job 作业参数
     */
    void process(KnowledgeParseJobRow job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Long documentId = null;
        Long revision = null;
        try {
            Map<String, Object> payload = jsonMapper.readValue(job.getPayloadJson(), MAP_TYPE);
            documentId = positiveLong(payload.get("documentId"), "documentId");
            revision = positiveLong(payload.get("revision"), "revision");
            Long baseId = positiveLong(payload.get("knowledgeBaseId"), "knowledgeBaseId");
            AgentKnowledgeDocument document = mapper.selectDocumentById(documentId);
            AgentKnowledgeBase base = mapper.selectBaseById(baseId);
            if (document == null || base == null || !baseId.equals(document.getKnowledgeBaseId())
                || !revision.equals(document.getRevisionNo()) || !"local".equals(document.getStorageType())
                || !"active".equals(base.getStatus())) {
                throw new IllegalStateException("知识解析作业引用已失效");
            }
            if (!persistence.start(documentId, revision, Boolean.TRUE.equals(job.getRecovered()))) {
                throw new IllegalStateException("知识文档当前不能开始解析");
            }
            document.setStatus("processing");
            KnowledgeBaseConfig config = KnowledgeBaseConfig.from(
                jsonMapper.readValue(base.getConfigJson(), MAP_TYPE)
            );
            KnowledgeDocumentParser.ParsedDocument parsed;
            try (InputStream input = storage.open(document.getStorageRef())) {
                parsed = parser.parse(input, document.getName(), document.getMimeType());
            }
            persistence.renew(job, workerId);
            List<KnowledgeChunker.Chunk> split = chunker.split(
                parsed.content(), config.chunkSize(), config.chunkOverlap()
            );
            if (split.isEmpty() || split.size() > MAX_CHUNKS) {
                throw new IllegalArgumentException("文档分块为空或超过 5000 个限制");
            }
            List<KnowledgeEmbeddingClient.VectorValue> vectors = embeddings(job, config, split);
            List<AgentKnowledgeChunk> chunks = chunks(base, document, split, vectors, config);
            Map<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
            metadata.put("detectedMimeType", parsed.detectedMimeType() == null ? "" : parsed.detectedMimeType());
            metadata.put("contentCharacters", parsed.content().length());
            metadata.put("chunkSize", config.chunkSize());
            metadata.put("chunkOverlap", config.chunkOverlap());
            persistence.complete(
                job, workerId, document, parsed.parserType(),
                jsonMapper.writeValueAsString(metadata), chunks
            );
        } catch (Exception exception) {
            String error = safeError(exception);
            if (documentId != null && revision != null) {
                persistence.fail(job, workerId, documentId, revision, error);
            } else {
                mapper.failParseJob(job.getId(), workerId, error);
            }
        }
    }

    /**
     * 处理{@code embeddings}并返回对应结果。
     *
     * @param job 作业参数
     * @param config {@code config}参数
     * @param chunks {@code chunks}参数
     * @return 符合条件的数据集合
     */
    private List<KnowledgeEmbeddingClient.VectorValue> embeddings(
        KnowledgeParseJobRow job,
        KnowledgeBaseConfig config,
        List<KnowledgeChunker.Chunk> chunks
    ) {
        if (config.embeddingModelId() == null) {
            return List.of();
        }
        AgentModel model = modelMapper.selectModelById(config.embeddingModelId());
        List<KnowledgeEmbeddingClient.VectorValue> result = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += 32) {
            int end = Math.min(chunks.size(), start + 32);
            List<String> inputs = chunks.subList(start, end).stream()
                .map(KnowledgeChunker.Chunk::content)
                .toList();
            result.addAll(embeddingClient.embed(model, inputs, config.embeddingDimension()));
            persistence.renew(job, workerId);
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code chunks}并返回对应结果。
     *
     * @param base {@code base}参数
     * @param document 文档参数
     * @param split {@code split}参数
     * @param vectors {@code vectors}参数
     * @param config {@code config}参数
     * @return 符合条件的数据集合
     */
    private List<AgentKnowledgeChunk> chunks(
        AgentKnowledgeBase base,
        AgentKnowledgeDocument document,
        List<KnowledgeChunker.Chunk> split,
        List<KnowledgeEmbeddingClient.VectorValue> vectors,
        KnowledgeBaseConfig config
    ) {
        List<AgentKnowledgeChunk> result = new ArrayList<>(split.size());
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < split.size(); index++) {
            KnowledgeChunker.Chunk source = split.get(index);
            AgentKnowledgeChunk chunk = new AgentKnowledgeChunk();
            chunk.setId(idGenerator.nextId());
            chunk.setKnowledgeBaseId(base.getId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkNo(source.number());
            chunk.setContent(source.content());
            chunk.setContentHash(source.contentHash());
            chunk.setTokenCount(source.tokenCount());
            if (!vectors.isEmpty()) {
                chunk.setEmbeddingModelId(config.embeddingModelId());
                chunk.setEmbeddingDimension(config.embeddingDimension());
                chunk.setEmbedding(vectors.get(index).postgresValue());
            }
            chunk.setMetadataJson(jsonMapper.writeValueAsString(Map.of(
                "documentName", document.getName(),
                "startOffset", source.startOffset(),
                "endOffset", source.endOffset()
            )));
            chunk.setStatus("active");
            chunk.setCreatedAt(now);
            result.add(chunk);
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException(label + " 无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
