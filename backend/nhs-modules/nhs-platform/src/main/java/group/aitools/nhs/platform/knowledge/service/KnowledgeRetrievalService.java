package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeMetricsMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeRetrievalRow;
import group.aitools.nhs.platform.knowledge.web.KnowledgeCitationView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeRetrievalView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeRetrieveRequest;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.provider.ExternalKnowledgeProviderRegistry;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;

/**
 * 负责知识库Retrieval相关的业务编排与领域规则处理。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeRetrievalService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final KnowledgeAuthorizationContextFactory contextFactory;
    private final KnowledgeCatalogMapper mapper;
    private final AgentModelMapper modelMapper;
    private final KnowledgeEmbeddingClient embeddingClient;
    private final JsonMapper jsonMapper;
    private final ExternalKnowledgeProviderRegistry externalProviders;
    private final PlatformIdGenerator idGenerator;
    private final KnowledgeMetricsMapper metricsMapper;
    private KnowledgeDirectoryAccessService directoryAccess;

    /**
     * 创建 {@code KnowledgeRetrievalService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contextFactory 待处理内容
     * @param mapper {@code mapper}参数
     * @param modelMapper 模型Mapper参数
     * @param embeddingClient embedding客户端参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public KnowledgeRetrievalService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        KnowledgeCatalogMapper mapper,
        AgentModelMapper modelMapper,
        KnowledgeEmbeddingClient embeddingClient,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, authorizationEnforcer, contextFactory, mapper, modelMapper,
            embeddingClient, jsonMapper, new ExternalKnowledgeProviderRegistry(List.of()), null, null
        );
    }

    /**
     * 创建 {@code KnowledgeRetrievalService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contextFactory 待处理内容
     * @param mapper {@code mapper}参数
     * @param modelMapper 模型Mapper参数
     * @param embeddingClient embedding客户端参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param externalProviders {@code externalProviders}参数
     */
    public KnowledgeRetrievalService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        KnowledgeCatalogMapper mapper,
        AgentModelMapper modelMapper,
        KnowledgeEmbeddingClient embeddingClient,
        JsonMapper jsonMapper,
        ExternalKnowledgeProviderRegistry externalProviders
    ) {
        this(
            principalProvider, authorizationEnforcer, contextFactory, mapper, modelMapper,
            embeddingClient, jsonMapper, externalProviders, null, null
        );
    }

    /**
     * 创建 {@code KnowledgeRetrievalService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contextFactory 待处理内容
     * @param mapper {@code mapper}参数
     * @param modelMapper 模型Mapper参数
     * @param embeddingClient embedding客户端参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param externalProviders {@code externalProviders}参数
     * @param idGenerator {@code idGenerator}参数
     * @param metricsMapper {@code metricsMapper}参数
     */
    @Autowired
    public KnowledgeRetrievalService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        KnowledgeCatalogMapper mapper,
        AgentModelMapper modelMapper,
        KnowledgeEmbeddingClient embeddingClient,
        JsonMapper jsonMapper,
        ExternalKnowledgeProviderRegistry externalProviders,
        PlatformIdGenerator idGenerator,
        KnowledgeMetricsMapper metricsMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.contextFactory = contextFactory;
        this.mapper = mapper;
        this.modelMapper = modelMapper;
        this.embeddingClient = embeddingClient;
        this.jsonMapper = jsonMapper;
        this.externalProviders = externalProviders;
        this.idGenerator = idGenerator;
        this.metricsMapper = metricsMapper;
    }

    /**
     * 设置目录Access。
     *
     * @param directoryAccess 目录Access参数
     */
    @Autowired(required = false)
    public void setDirectoryAccess(KnowledgeDirectoryAccessService directoryAccess) {
        this.directoryAccess = directoryAccess;
    }

    /**
     * 处理{@code retrieve}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public KnowledgeRetrievalView retrieve(KnowledgeRetrieveRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        long startedAt = System.nanoTime();
        KnowledgeRetrievalView result = null;
        try {
            result = retrieve(
                principal, request.knowledgeBaseIds(), request.query(), request.topK(),
                request.similarityThreshold(), request.vectorWeight(), true, Map.of()
            );
            return result;
        } finally {
            recordMetrics(principal, request, result, startedAt);
        }
    }

    /**
     * 处理{@code recordMetrics}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param request 请求参数
     * @param result 结果参数
     * @param startedAt {@code startedAt}参数
     */
    private void recordMetrics(
        CurrentPrincipal principal,
        KnowledgeRetrieveRequest request,
        KnowledgeRetrievalView result,
        long startedAt
    ) {
        if (idGenerator == null || metricsMapper == null || principal == null || request == null) {
            return;
        }
        String query = request.query() == null ? "" : request.query().strip();
        List<Long> baseIds = request.knowledgeBaseIds() == null
            ? List.of()
            : request.knowledgeBaseIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> documentIds = result == null
            ? List.of()
            : new ArrayList<>(new LinkedHashSet<>(result.citations().stream()
                .map(KnowledgeCitationView::documentId)
                .filter(java.util.Objects::nonNull)
                .toList()));
        int latencyMs = (int) Math.min(
            Integer.MAX_VALUE,
            Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        );
        try {
            metricsMapper.insertEvent(
                idGenerator.nextId(), principal.id(), null, ContentHashing.sha256(query),
                Math.min(query.length(), 4000), jsonMapper.writeValueAsString(baseIds),
                result == null ? "failed" : normalizeMetricsStatus(result.status()),
                result == null ? 0 : result.citations().size(),
                jsonMapper.writeValueAsString(documentIds), latencyMs, LocalDateTime.now()
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Knowledge retrieval metrics could not be persisted", exception);
        }
    }

    /**
     * 处理{@code normalizeMetricsStatus}并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String normalizeMetricsStatus(String status) {
        if ("ok".equals(status) || "empty".equals(status)) {
            return status;
        }
        return "failed";
    }

    /**
     * 处理{@code retrieve}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param baseIds 资源标识集合
     * @param rawQuery raw查询参数
     * @param requestedTopK {@code requestedTopK}参数
     * @param requestedThreshold {@code requestedThreshold}参数
     * @param requestedVectorWeight {@code requestedVectorWeight}参数
     * @param userInterfaceOperation 用户Interface操作参数
     * @param frozenConfigs {@code frozenConfigs}参数
     * @return 处理结果
     */
    public KnowledgeRetrievalView retrieve(
        CurrentPrincipal principal,
        List<Long> baseIds,
        String rawQuery,
        Integer requestedTopK,
        Double requestedThreshold,
        Double requestedVectorWeight,
        boolean userInterfaceOperation,
        Map<Long, KnowledgeBaseConfig> frozenConfigs
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isBlank() || query.length() > 4000 || query.indexOf('\0') >= 0) {
            throw new ServiceException("知识检索问题为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        if (baseIds == null || baseIds.isEmpty() || baseIds.size() > 10
            || baseIds.stream().anyMatch(id -> id == null || id <= 0)
            || baseIds.stream().distinct().count() != baseIds.size()) {
            throw new ServiceException("知识检索必须选择 1-10 个不重复知识库", HttpStatus.BAD_REQUEST);
        }
        int globalTopK = requestedTopK == null ? 6 : requestedTopK;
        if (globalTopK < 1 || globalTopK > 20) {
            throw new ServiceException("topK 必须在 1-20 之间", HttpStatus.BAD_REQUEST);
        }
        List<Candidate> combined = new ArrayList<>();
        for (Long baseId : baseIds) {
            AgentKnowledgeBase base = mapper.selectBaseById(baseId);
            if (base == null || !"active".equals(base.getStatus())) {
                throw new ServiceException("知识库不存在或未启用：" + baseId, HttpStatus.NOT_FOUND);
            }
            authorizationEnforcer.requireAllowed(
                principal,
                contextFactory.context(principal, base, "read", userInterfaceOperation)
            );
            KnowledgeDirectoryAccessService.DirectoryAccess directoryScope = directoryAccess == null
                ? KnowledgeDirectoryAccessService.DirectoryAccess.all()
                : directoryAccess.access(principal, baseId, "read");
            if (!directoryScope.allDirectories() && !directoryScope.rootAllowed()
                && directoryScope.directoryIds().isEmpty()) {
                continue;
            }
            if (!"postgres_pgvector".equals(base.getProviderType())) {
                if (baseIds.size() != 1) {
                    throw new ServiceException(
                        "外部知识Provider检索不能与其他知识库混合", HttpStatus.BAD_REQUEST
                    );
                }
                return externalProviders.require(base.getProviderType()).retrieve(
                    principal, base, query, globalTopK
                );
            }
            KnowledgeBaseConfig config = frozenConfigs.getOrDefault(baseId, config(base));
            int candidateLimit = Math.min(60, Math.max(globalTopK, config.topK()) * 3);
            Map<Long, Candidate> candidates = new LinkedHashMap<>();
            for (KnowledgeRetrievalRow row : mapper.searchLexicalScoped(
                baseId, query, candidateLimit, new ArrayList<>(directoryScope.directoryIds()),
                directoryScope.rootAllowed(), directoryScope.allDirectories()
            )) {
                candidates.computeIfAbsent(row.getChunkId(), ignored -> new Candidate(row))
                    .lexical = clamp(row.getScore());
            }
            double vectorWeight = requestedVectorWeight == null
                ? config.vectorWeight() : bounded(requestedVectorWeight, "vectorWeight");
            if (config.embeddingModelId() != null) {
                AgentModel model = modelMapper.selectModelById(config.embeddingModelId());
                KnowledgeEmbeddingClient.VectorValue queryVector = embeddingClient.embedOne(
                    model, query, config.embeddingDimension()
                );
                for (KnowledgeRetrievalRow row : mapper.searchVectorScoped(
                    baseId, config.embeddingModelId(), config.embeddingDimension(),
                    queryVector.postgresValue(), candidateLimit,
                    new ArrayList<>(directoryScope.directoryIds()),
                    directoryScope.rootAllowed(), directoryScope.allDirectories()
                )) {
                    candidates.computeIfAbsent(row.getChunkId(), ignored -> new Candidate(row))
                        .vector = clamp(row.getScore());
                }
            } else {
                vectorWeight = 0;
            }
            double threshold = requestedThreshold == null
                ? config.similarityThreshold() : bounded(requestedThreshold, "similarityThreshold");
            for (Candidate candidate : candidates.values()) {
                candidate.score = vectorWeight * candidate.vector
                    + (1 - vectorWeight) * candidate.lexical;
                if (candidate.score >= threshold) {
                    combined.add(candidate);
                }
            }
        }
        combined.sort(
            Comparator.comparingDouble((Candidate value) -> value.score).reversed()
                .thenComparing(value -> value.row.getChunkId())
        );
        List<Candidate> selected = combined.stream().limit(globalTopK).toList();
        if (selected.isEmpty()) {
            return new KnowledgeRetrievalView(
                "empty",
                "当前知识库中暂无足够依据回答该问题。请换关键词或补充文档，不要编造流程、制度或操作步骤。",
                List.of()
            );
        }
        List<KnowledgeCitationView> citations = new ArrayList<>(selected.size());
        StringBuilder context = new StringBuilder(
            "仅根据以下知识库片段回答。每个基于文档的陈述末尾必须追加对应 [ID:n] 引用。\n\n"
        );
        int index = 1;
        for (Candidate candidate : selected) {
            KnowledgeRetrievalRow row = candidate.row;
            String citationId = String.valueOf(index++);
            Map<String, Object> metadata = parseMetadata(row.getMetadataJson());
            citations.add(new KnowledgeCitationView(
                citationId, row.getChunkId(), row.getKnowledgeBaseId(), row.getDocumentId(),
                row.getDocumentName(), row.getChunkNo(), candidate.score, row.getContent(), metadata
            ));
            context.append("--- [ID:").append(citationId).append("] 来源: ")
                .append(row.getDocumentName()).append(" ---\n")
                .append(row.getContent()).append("\n\n");
        }
        return new KnowledgeRetrievalView("ok", context.toString(), List.copyOf(citations));
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @param base {@code base}参数
     * @return 处理结果
     */
    private KnowledgeBaseConfig config(AgentKnowledgeBase base) {
        try {
            Map<String, Object> raw = jsonMapper.readValue(base.getConfigJson(), MAP_TYPE);
            return KnowledgeBaseConfig.from(raw);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException("知识库配置无效：" + base.getId(), HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理parse元数据并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = jsonMapper.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private double bounded(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new ServiceException(label + " 必须在 0-1 之间", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code clamp}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double clamp(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    /**
     * 表示{@code Candidate}相关的领域对象。
     */
    private static final class Candidate {
        private final KnowledgeRetrievalRow row;
        private double lexical;
        private double vector;
        private double score;

        /**
         * 创建 {@code Candidate} 实例并初始化所需依赖。
         *
         * @param row {@code row}参数
         */
        private Candidate(KnowledgeRetrievalRow row) {
            this.row = row;
        }
    }
}
