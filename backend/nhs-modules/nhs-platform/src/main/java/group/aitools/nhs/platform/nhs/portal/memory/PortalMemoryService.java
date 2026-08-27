package group.aitools.nhs.platform.nhs.portal.memory;

import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryApplicationService;
import group.aitools.nhs.platform.memory.service.MemoryVectorApplicationService;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
import group.aitools.nhs.platform.memory.web.CreateMemoryRequest;
import group.aitools.nhs.platform.memory.web.MemoryView;
import group.aitools.nhs.platform.memory.web.UpdateMemoryRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 负责门户记忆相关的业务编排与领域规则处理。
 *
 * Nhs personal-memory projection backed by governed agent_memory rows.
 * Session and daily summaries are explicit memory records with metadata keys;
 * no Redis/vector success is reported when those providers are unavailable.
 */
@Service
public class PortalMemoryService {

    private final CurrentPrincipalProvider principalProvider;
    private final MemoryApplicationService memoryService;
    private final MemoryCatalogMapper memoryMapper;
    private final AgentConversationMapper conversationMapper;
    private final PortalMemoryOperationsAuditService operationsAuditService;
    private final MemoryVectorApplicationService vectorService;
    private final PortalChatBIModelGateway modelGateway;

    public PortalMemoryService(
        CurrentPrincipalProvider principalProvider,
        MemoryApplicationService memoryService,
        MemoryCatalogMapper memoryMapper,
        AgentConversationMapper conversationMapper,
        PortalMemoryOperationsAuditService operationsAuditService
    ) {
        this(
            principalProvider, memoryService, memoryMapper, conversationMapper,
            operationsAuditService, null, null
        );
    }

    /**
     * 创建 {@code PortalMemoryService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param memoryService 记忆Service参数
     * @param memoryMapper 记忆Mapper参数
     * @param conversationMapper 会话Mapper参数
     * @param operationsAuditService operations审计Service参数
     * @param vectorService {@code vectorService}参数
     */
    public PortalMemoryService(
        CurrentPrincipalProvider principalProvider,
        MemoryApplicationService memoryService,
        MemoryCatalogMapper memoryMapper,
        AgentConversationMapper conversationMapper,
        PortalMemoryOperationsAuditService operationsAuditService,
        MemoryVectorApplicationService vectorService
    ) {
        this(
            principalProvider, memoryService, memoryMapper, conversationMapper,
            operationsAuditService, vectorService, null
        );
    }

    /**
     * 创建 {@code PortalMemoryService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param memoryService 记忆Service参数
     * @param memoryMapper 记忆Mapper参数
     * @param conversationMapper 会话Mapper参数
     * @param operationsAuditService operations审计Service参数
     * @param vectorService {@code vectorService}参数
     * @param modelGateway 模型Gateway参数
     */
    @Autowired
    public PortalMemoryService(
        CurrentPrincipalProvider principalProvider,
        MemoryApplicationService memoryService,
        MemoryCatalogMapper memoryMapper,
        AgentConversationMapper conversationMapper,
        PortalMemoryOperationsAuditService operationsAuditService,
        MemoryVectorApplicationService vectorService,
        PortalChatBIModelGateway modelGateway
    ) {
        this.principalProvider = principalProvider;
        this.memoryService = memoryService;
        this.memoryMapper = memoryMapper;
        this.conversationMapper = conversationMapper;
        this.operationsAuditService = operationsAuditService;
        this.vectorService = vectorService;
        this.modelGateway = modelGateway;
    }

    /**
     * 处理{@code summaries}并返回对应结果。
     *
     * @param keyword {@code keyword}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> summaries(String keyword, int limit) {
        return summariesForUser(current().id(), keyword, limit);
    }

    /**
     * 处理当前用户Id并返回对应结果。
     *
     * @return 处理结果
     */
    public Long currentUserId() {
        return current().id();
    }

    /**
     * 处理{@code capabilities}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> capabilities() {
        MemoryVectorApplicationService.Settings settings = vectorService == null
            ? null : vectorService.settings();
        boolean enabled = settings == null || settings.enabled();
        boolean summariesEnabled = enabled && (settings == null || settings.summaryEnabled());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("relational_store", Map.of("available", true, "provider", "postgresql"));
        result.put("relational_search", Map.of(
            "available", enabled, "provider", "postgresql_tsvector", "owner_scoped", true
        ));
        result.put("session_summaries", Map.of("available", summariesEnabled));
        result.put("daily_summaries", Map.of("available", summariesEnabled, "rebuild", summariesEnabled));
        result.put("relational_consolidation", Map.of(
            "available", summariesEnabled, "mode", "daily_summary"
        ));
        result.put("long_term_memory", Map.of("available", enabled));
        result.put("intelligent_consolidation", Map.of(
            "available", enabled && vectorService != null && settings.embeddingEnabled(),
            "reason", !enabled
                ? "记忆服务已由平台配置关闭"
                : vectorService == null || !settings.embeddingEnabled()
                ? "当前未配置 Embedding 模型"
                : "已启用 pgvector 记忆向量"
        ));
        if (vectorService != null) {
            result.put("vector_search", Map.of(
                "available", enabled && settings.embeddingEnabled(),
                "provider", "postgres_pgvector",
                "model_id", settings.embeddingModelId() == null ? "" : settings.embeddingModelId(),
                "dimension", settings.embeddingDimension() == null ? 0 : settings.embeddingDimension(),
                "top_k", settings.searchKnnTopK()
            ));
        }
        return result;
    }

    /**
     * 处理{@code consolidate}并返回对应结果。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> consolidate() {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        requireSummaryEnabled("memory.consolidate");
        Long userId = current().id();
        Map<String, Object> semantic = consolidateSimilarMemories(userId);
        List<MemoryView> memories = userMemories(userId, 500);
        TreeSet<String> dates = new TreeSet<>();
        TreeSet<String> existing = new TreeSet<>();
        for (MemoryView memory : memories) {
            String date = dateOf(memory);
            if (date == null) {
                continue;
            }
            if (sessionSummary(memory)) {
                dates.add(date);
            } else if (dailySummary(memory)) {
                existing.add(date);
            }
        }
        int created = 0;
        int updated = 0;
        for (String date : dates) {
            rebuildDailyForUser(userId, date);
            if (existing.contains(date)) {
                updated++;
            } else {
                created++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", Boolean.TRUE.equals(semantic.get("available"))
            ? "postgresql_pgvector" : "postgresql_relational");
        result.put("semantic_groups", semantic.get("groups"));
        result.put("semantic_merged", semantic.get("merged"));
        result.put("semantic_failed", semantic.get("failed"));
        result.put("semantic_message", semantic.get("message"));
        result.put("days_processed", dates.size());
        result.put("daily_summaries_created", created);
        result.put("daily_summaries_updated", updated);
        result.put("intelligent_rewrite", Boolean.TRUE.equals(semantic.get("available"))
            && ((Number) semantic.getOrDefault("merged", 0)).intValue() > 0);
        return result;
    }

    /**
     * 处理{@code consolidateSimilarMemories}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> consolidateSimilarMemories(Long userId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("groups", 0);
        result.put("merged", 0);
        result.put("failed", 0);
        if (vectorService == null || modelGateway == null
            || !vectorService.settings().embeddingEnabled()) {
            result.put("message", "未配置向量模型或聊天模型，已跳过智能记忆合并");
            return result;
        }
        List<MemoryVectorApplicationService.EmbeddedMemory> memories =
            vectorService.embeddedSessionMemories(userId, 500);
        if (memories.size() < 2) {
            result.put("available", true);
            result.put("message", "没有达到合并阈值的会话记忆");
            return result;
        }
        double threshold = vectorService.settings().consolidationThreshold();
        boolean[] visited = new boolean[memories.size()];
        List<List<MemoryVectorApplicationService.EmbeddedMemory>> groups = new ArrayList<>();
        for (int index = 0; index < memories.size(); index++) {
            if (visited[index]) {
                continue;
            }
            ArrayList<Integer> stack = new ArrayList<>();
            ArrayList<MemoryVectorApplicationService.EmbeddedMemory> group = new ArrayList<>();
            stack.add(index);
            visited[index] = true;
            while (!stack.isEmpty()) {
                int currentIndex = stack.remove(stack.size() - 1);
                MemoryVectorApplicationService.EmbeddedMemory currentMemory = memories.get(currentIndex);
                group.add(currentMemory);
                for (int candidate = currentIndex + 1; candidate < memories.size(); candidate++) {
                    if (!visited[candidate] && cosine(
                        currentMemory.vector(), memories.get(candidate).vector()
                    ) >= threshold) {
                        visited[candidate] = true;
                        stack.add(candidate);
                    }
                }
                for (int candidate = 0; candidate < currentIndex; candidate++) {
                    if (!visited[candidate] && cosine(
                        currentMemory.vector(), memories.get(candidate).vector()
                    ) >= threshold) {
                        visited[candidate] = true;
                        stack.add(candidate);
                    }
                }
            }
            if (group.size() > 1) {
                groups.add(List.copyOf(group));
            }
        }
        result.put("available", true);
        result.put("groups", groups.size());
        int merged = 0;
        int failed = 0;
        for (List<MemoryVectorApplicationService.EmbeddedMemory> group : groups) {
            MemoryView consolidated = null;
            boolean sourcesDeleted = false;
            try {
                String fragments = group.stream()
                    .map(value -> "- " + value.content())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
                String mergedContent = modelGateway.complete(
                    "你是企业记忆整理助手。请把相似的会话摘要合并为一条简洁、准确的中文陈述，保留事实、名称和数值，只返回合并后的正文。",
                    "请合并以下记忆摘要：\n\n" + fragments
                ).content();
                mergedContent = mergedContent == null ? "" : mergedContent.strip();
                if (mergedContent.isBlank()) {
                    failed++;
                    continue;
                }
                if (mergedContent.length() > 4000) {
                    mergedContent = mergedContent.substring(0, 4000);
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("kind", "consolidated");
                metadata.put("source_memory_ids", group.stream().map(
                    MemoryVectorApplicationService.EmbeddedMemory::id
                ).toList());
                metadata.put("reference_count", group.size());
                consolidated = memoryService.create("user", userId, new CreateMemoryRequest(
                    "consolidated-" + UUID.randomUUID(), "summary", mergedContent,
                    "manual", null, 1.0, "internal", null, metadata
                ));
                vectorService.indexMemoryRequired(consolidated.id(), consolidated.content());
                memoryService.deleteBatch(group.stream()
                    .map(old -> new MemoryCatalogMapper.MemoryRevision(old.id(), old.revision()))
                    .toList());
                sourcesDeleted = true;
                merged++;
            } catch (RuntimeException exception) {
                if (consolidated != null && !sourcesDeleted) {
                    try {
                        memoryService.delete(consolidated.id(), consolidated.revisionNo());
                    } catch (RuntimeException ignored) {
                        // Keep the source rows authoritative even if cleanup is
                        // concurrently rejected; the failure is still audited.
                    }
                }
                failed++;
            }
        }
        result.put("merged", merged);
        result.put("failed", failed);
        result.put("message", failed == 0 ? "智能记忆合并完成" : "部分记忆合并失败，原记忆已保留");
        return result;
    }

    /**
     * 处理{@code cosine}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 处理结果
     */
    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    /**
     * 查询{@code search}列表。
     *
     * @param query 查询参数
     * @param requestedLimit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> search(String query, Integer requestedLimit) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator("memory.search_test");
        requireMemoryEnabled("memory.search_test");
        List<Map<String, Object>> result;
        boolean degraded = false;
        int limit;
        try {
            limit = requestedLimit == null ? configLimit(principal.id()) : requestedLimit;
            String normalized = text(query, 255, "检索词");
            if (limit < 1 || limit > 200) {
                throw new ServiceException("检索数量必须在1到200之间", HttpStatus.BAD_REQUEST);
            }
            List<MemoryView> lexical = memoryService.list("user", principal.id(), normalized, limit).stream()
                .filter(value -> !configMemory(value))
                .toList();
            Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
            for (MemoryView value : lexical) {
                Map<String, Object> row = searchView(value);
                row.put("lexical_score", 1.0D);
                row.put("score", 1.0D);
                merged.put(value.id(), row);
            }
            if (vectorService != null && vectorService.settings().embeddingEnabled()) {
                try {
                    for (MemoryVectorApplicationService.SearchHit hit : vectorService.search(
                        "user", principal.id(), normalized, limit
                    )) {
                        Map<String, Object> row = merged.computeIfAbsent(hit.id(), ignored -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", hit.id());
                            value.put("memory_key", hit.memoryKey());
                            value.put("memory_type", hit.memoryType());
                            value.put("content", hit.content());
                            value.put("source_type", hit.sourceType());
                            value.put("source_id", hit.sourceId());
                            value.put("sensitive_level", hit.sensitiveLevel());
                            value.put("metadata", hit.metadata());
                            value.put("updated_at", hit.updatedAt());
                            return value;
                        });
                        row.put("vector_score", hit.vectorScore());
                        row.put("final_score", hit.finalScore());
                        row.put("score", hit.finalScore());
                        row.putIfAbsent("lexical_score", 0.0D);
                    }
                } catch (ServiceException exception) {
                    if (exception.getCode() == null || exception.getCode() >= 500) {
                        degraded = true;
                        operationsAuditService.record(
                            principal, "memory.search_test", principal.id(), "degraded",
                            "vector search unavailable; lexical results returned", "provider=postgres_pgvector"
                        );
                    } else {
                        throw exception;
                    }
                }
            }
            result = merged.values().stream()
                .sorted((left, right) -> Double.compare(
                    number(right.get("score")), number(left.get("score"))
                ))
                .limit(limit)
                .toList();
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.search_test", exception, "ownerId=" + principal.id());
            throw exception;
        }
        operationsAuditService.record(
            principal, "memory.search_test", principal.id(), degraded ? "degraded" : "success",
            degraded ? "vector search unavailable; owner-scoped lexical results returned"
                : "owner-scoped relational search test completed",
            "ownerId=" + principal.id() + ", limit=" + limit + ", resultCount=" + result.size()
        );
        return result;
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> config() {
        CurrentPrincipal principal = requireAdministrator("memory.config_view");
        Map<String, Object> result;
        try {
            result = configView(principal.id());
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.config_view", exception, "ownerId=" + principal.id());
            throw exception;
        }
        operationsAuditService.record(
            principal, "memory.config_view", principal.id(), "success", "memory configuration viewed",
            "ownerId=" + principal.id() + ", stored=" + result.get("stored")
        );
        return result;
    }

    /**
     * 处理{@code configView}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> configView(Long userId) {
        if (vectorService == null) {
            MemoryView stored = memoryConfig(userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", "postgresql_tsvector");
            result.put("default_search_limit", stored == null ? 50 : configLimit(stored));
            result.put("consolidation_mode", "relational_daily_summary");
            result.put("embedding_enabled", false);
            result.put("redis_vector_enabled", false);
            result.put("stored", stored != null);
            result.put("revision", stored == null ? null : stored.revisionNo());
            return result;
        }
        MemoryVectorApplicationService.Settings settings = vectorService.settings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "postgres_pgvector");
        result.put("default_search_limit", settings.searchKnnTopK());
        result.put("consolidation_mode", settings.embeddingEnabled()
            ? "vector_similarity" : "relational_daily_summary");
        result.put("search_knn_top_k", settings.searchKnnTopK());
        result.put("vector_weight", settings.vectorWeight());
        result.put("consolidation_threshold", settings.consolidationThreshold());
        result.put("base_half_life_days", settings.baseHalfLifeDays());
        result.put("summary_ttl_days", settings.summaryTtlDays());
        result.put("enabled", settings.enabled());
        result.put("summary_enabled", settings.summaryEnabled());
        result.put("embedding_enabled", settings.embeddingEnabled());
        result.put("embedding_model_id", settings.embeddingModelId());
        result.put("embedding_dimension", settings.embeddingDimension());
        result.put("redis_vector_enabled", false);
        result.put("stored", settings.updatedAt() != null);
        result.put("revision", settings.revision());
        return result;
    }

    /**
     * 更新{@code Config}。
     *
     * @param defaultSearchLimit 数量上限
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateConfig(Integer defaultSearchLimit) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (vectorService != null) {
            return updateConfig(new MemoryVectorApplicationService.SettingsPatch(
                null, null, null, null, null, defaultSearchLimit,
                null, null, null, null, null
            ));
        }
        CurrentPrincipal principal = requireAdministrator("memory.config_update");
        int limit = defaultSearchLimit == null ? 50 : defaultSearchLimit;
        Map<String, Object> result;
        try {
            if (limit < 1 || limit > 200) {
                throw new ServiceException("默认检索数量必须在1到200之间", HttpStatus.BAD_REQUEST);
            }
            MemoryView existing = memoryConfig(principal.id());
            Map<String, Object> metadata = Map.of(
                "kind", "memory_config", "default_search_limit", limit
            );
            if (existing == null) {
                memoryService.create("user", principal.id(), new CreateMemoryRequest(
                    "portal-memory-config", "preference", "Portal memory configuration",
                    "manual", null, 1.0, "internal", null, metadata
                ));
            } else {
                memoryService.update(existing.id(), new UpdateMemoryRequest(
                    existing.revisionNo(), "preference", "Portal memory configuration",
                    "manual", null, 1.0, "internal", null, metadata
                ));
            }
            result = configView(principal.id());
        } catch (RuntimeException exception) {
            auditFailure(
                principal, "memory.config_update", exception,
                "ownerId=" + principal.id() + ", defaultSearchLimit=" + limit
            );
            throw exception;
        }
        operationsAuditService.record(
            principal, "memory.config_update", principal.id(), "success", "memory configuration updated",
            "ownerId=" + principal.id() + ", defaultSearchLimit=" + limit
        );
        return result;
    }

    /**
     * 更新{@code Config}。
     *
     * @param patch {@code patch}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateConfig(MemoryVectorApplicationService.SettingsPatch patch) {
        CurrentPrincipal principal = requireAdministrator("memory.config_update");
        if (vectorService == null) {
            throw new ServiceException("记忆向量服务未加载", 503);
        }
        try {
            MemoryVectorApplicationService.Settings settings = vectorService.update(patch, principal.id());
            Map<String, Object> result = configView(principal.id());
            operationsAuditService.record(
                principal, "memory.config_update", principal.id(), "success",
                "memory vector configuration updated", "revision=" + settings.revision()
            );
            return result;
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.config_update", exception, "vector configuration update");
            throw exception;
        }
    }

    /**
     * 处理{@code indexStatus}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> indexStatus() {
        CurrentPrincipal principal = requireAdministrator("memory.index_status");
        Map<String, Object> result;
        try {
            result = indexStatusView(principal.id());
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.index_status", exception, "ownerId=" + principal.id());
            throw exception;
        }
        operationsAuditService.record(
            principal, "memory.index_status", principal.id(), "success", "memory index status checked",
            "ownerId=" + principal.id() + ", documentCount=" + result.get("document_count")
        );
        return result;
    }

    /**
     * 处理{@code indexStatusView}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> indexStatusView(Long userId) {
        boolean generatedSearchVector = memoryMapper.hasGeneratedSearchVector();
        boolean validLexicalIndex = generatedSearchVector && memoryMapper.hasValidLexicalIndex();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", generatedSearchVector && validLexicalIndex);
        result.put("provider", "postgresql_tsvector");
        result.put("owner_scoped", true);
        result.put("document_count", memoryMapper.countSearchDocuments("user", userId));
        result.put("automatically_maintained", generatedSearchVector);
        result.put("rebuild_required", !validLexicalIndex);
        result.put("search_vector_present", generatedSearchVector);
        result.put("lexical_index_present", validLexicalIndex);
        result.put("checked_at", LocalDateTime.now());
        if (vectorService != null) {
            result.put("vector", vectorService.vectorStoreStatus("user", userId));
        }
        return result;
    }

    /**
     * 校验{@code Index}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    public Map<String, Object> verifyIndex() {
        CurrentPrincipal principal = requireAdministrator("memory.index_verify");
        Map<String, Object> result;
        try {
            result = new LinkedHashMap<>(indexStatusView(principal.id()));
            if (!Boolean.TRUE.equals(result.get("available"))) {
                throw new ServiceException(
                    "记忆全文检索生成列或 GIN 索引不可用，请先修复数据库迁移", 503
                );
            }
            result.put("verified", true);
            result.put("rebuilt", false);
            result.put("message", "PostgreSQL 生成式全文索引会随记忆写入自动维护；向量索引可按需重建");
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.index_verify", exception, "ownerId=" + principal.id());
            throw exception;
        }
        operationsAuditService.record(
            principal, "memory.index_verify", principal.id(), "success", "memory index verified",
            "ownerId=" + principal.id() + ", provider=postgresql_tsvector, rebuilt=false"
        );
        return result;
    }

    /**
     * 处理{@code rebuildVectorIndex}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> rebuildVectorIndex() {
        CurrentPrincipal principal = requireAdministrator("memory.index_rebuild");
        if (vectorService == null) {
            throw new ServiceException("记忆向量服务未加载", 503);
        }
        MemoryVectorApplicationService.RebuildResult rebuilt = vectorService.rebuild(null, null, 10_000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("provider", "postgres_pgvector");
        result.put("indexed", rebuilt.indexed());
        result.put("capped", rebuilt.capped());
        result.put("model_id", rebuilt.modelId());
        result.put("dimension", rebuilt.dimension());
        result.put("message", rebuilt.capped() ? "向量重建达到本次上限，请再次执行" : "向量索引重建完成");
        operationsAuditService.record(
            principal, "memory.index_rebuild", principal.id(), "success", "memory vector index rebuilt",
            "indexed=" + rebuilt.indexed() + ", capped=" + rebuilt.capped()
        );
        return result;
    }

    /**
     * 处理{@code testEmbedding}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> testEmbedding() {
        CurrentPrincipal principal = requireAdministrator("memory.embedding_test");
        if (vectorService == null) {
            operationsAuditService.record(
                principal, "memory.embedding_test", principal.id(), "failure",
                "embedding provider unavailable", "ownerId=" + principal.id() + ", configured=false"
            );
            throw new ServiceException("记忆向量服务未加载", 503);
        }
        try {
            Map<String, Object> result = vectorService.testEmbedding();
            operationsAuditService.record(
                principal, "memory.embedding_test", principal.id(), "success", "embedding provider tested",
                "modelId=" + result.get("model_id") + ", dimensions=" + result.get("dimensions")
            );
            return result;
        } catch (RuntimeException exception) {
            auditFailure(principal, "memory.embedding_test", exception, "ownerId=" + principal.id());
            throw exception;
        }
    }

    /**
     * 处理{@code testVectorStore}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> testVectorStore() {
        CurrentPrincipal principal = requireAdministrator("memory.redis_vector_test");
        if (vectorService == null) {
            operationsAuditService.record(
                principal, "memory.redis_vector_test", principal.id(), "failure",
                "redis vector provider unavailable", "ownerId=" + principal.id() + ", configured=false"
            );
            throw new ServiceException("记忆向量服务未加载", 503);
        }
        Map<String, Object> result = vectorService.vectorStoreStatus("user", principal.id());
        operationsAuditService.record(
            principal, "memory.redis_vector_test", principal.id(),
            Boolean.TRUE.equals(result.get("available")) ? "success" : "failure",
            String.valueOf(result.get("message")), "provider=postgres_pgvector"
        );
        if (!Boolean.TRUE.equals(result.get("available"))) {
            throw new ServiceException(String.valueOf(result.get("message")), 503);
        }
        return result;
    }

    /**
     * 处理summariesFor用户并返回对应结果。
     *
     * @param userId 资源标识
     * @param keyword {@code keyword}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> summariesForUser(Long userId, String keyword, int limit) {
        requireUserAccess(userId);
        String query = keyword == null ? null : keyword.strip().toLowerCase(Locale.ROOT);
        return memoryService.list("user", userId, keyword, Math.min(Math.max(limit, 1), 500)).stream()
            .filter(this::sessionSummary)
            .filter(value -> query == null || query.isBlank()
                || value.content().toLowerCase(Locale.ROOT).contains(query)
                || conversationId(value) .toLowerCase(Locale.ROOT).contains(query))
            .map(value -> summaryView(value, userId))
            .toList();
    }

    /**
 * 处理finalize会话Summary并返回对应结果。
 *
     * Idempotently projects a finalized private conversation into governed
     * personal memory and guarantees that the corresponding daily rollup
     * exists. Both records use the same PostgreSQL transaction as the caller.
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationMemoryFinalizeResult finalizeConversationSummary(
        Long userId,
        Long conversationId,
        String summary,
        LocalDate activityDate
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireUserAccess(userId);
        if (!memoryEnabled() || !summaryEnabled()) {
            return new ConversationMemoryFinalizeResult(
                null, false, activityDate == null ? null : activityDate.toString(), false
            );
        }
        if (conversationId == null || conversationId <= 0) {
            throw new ServiceException("会话ID无效", HttpStatus.BAD_REQUEST);
        }
        String content = boundedSummary(summary, 4000);
        if (content.isBlank()) {
            throw new ServiceException("会话摘要不能为空", HttpStatus.BAD_REQUEST);
        }
        LocalDate date = activityDate == null ? LocalDate.now() : activityDate;
        List<MemoryView> memories = userMemories(userId, 500);
        String id = String.valueOf(conversationId);
        MemoryView existing = memories.stream()
            .filter(this::sessionSummary)
            .filter(value -> id.equals(conversationId(value)))
            .reduce(this::newer)
            .orElse(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "session_summary");
        metadata.put("conversation_id", id);
        metadata.put("date", date.toString());
        metadata.put("summary_type", "session");

        MemoryView stored = existing;
        boolean changed = existing == null
            || !content.equals(existing.content())
            || !date.toString().equals(dateOf(existing));
        if (existing == null) {
            stored = memoryService.create("user", userId, new CreateMemoryRequest(
                "conversation-summary-" + conversationId, "summary", content,
                "conversation", conversationId, 1.0, "internal", null, metadata
            ));
        } else if (changed) {
            stored = memoryService.update(existing.id(), new UpdateMemoryRequest(
                existing.revisionNo(), "summary", content, "conversation", conversationId,
                1.0, "internal", null, metadata
            ));
        }
        boolean dailyExists = memories.stream()
            .anyMatch(value -> dailySummary(value) && date.toString().equals(dateOf(value)));
        if (changed || !dailyExists) {
            rebuildDailyForUser(userId, date.toString());
        }
        return new ConversationMemoryFinalizeResult(
            stored == null ? null : stored.id(), changed, date.toString(), changed || !dailyExists
        );
    }

    /**
     * 处理{@code summaryDetail}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param historyLimit 数量上限
     * @return 处理结果
     */
    public Map<String, Object> summaryDetail(String conversationId, int historyLimit) {
        return summaryDetailForUser(current().id(), conversationId, historyLimit);
    }

    /**
     * 处理summaryDetailFor用户并返回对应结果。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param historyLimit 数量上限
     * @return 处理结果
     */
    public Map<String, Object> summaryDetailForUser(Long userId, String conversationId, int historyLimit) {
        requireUserAccess(userId);
        String id = text(conversationId, 128, "会话标识");
        MemoryView summary = userMemories(userId, 500).stream()
            .filter(this::sessionSummary)
            .filter(value -> id.equals(conversationId(value)))
            .findFirst()
            .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary == null ? null : summaryView(summary, userId));
        List<Map<String, Object>> history = history(userId, id, historyLimit);
        result.put("history", history);
        result.put("has_history", !history.isEmpty());
        return result;
    }

    /**
     * 删除{@code Summary}。
     *
     * @param conversationId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSummary(String conversationId) {
        deleteSummaryForUser(current().id(), conversationId);
    }

    /**
     * 删除SummaryFor用户。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSummaryForUser(Long userId, String conversationId) {
        requireUserAccess(userId);
        String id = text(conversationId, 128, "会话标识");
        for (MemoryView value : userMemories(userId, 500)) {
            if (sessionSummary(value) && id.equals(conversationId(value))) {
                memoryService.delete(value.id(), value.revisionNo());
            }
        }
    }

    /**
     * 清理或重置会话记忆。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> clearSessionMemory() {
        return clearSessionMemoryForUser(current().id());
    }

    /**
     * 清理或重置会话记忆For用户。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> clearSessionMemoryForUser(Long userId) {
        requireUserAccess(userId);
        int sessionDeleted = 0;
        int dailyDeleted = 0;
        for (MemoryView value : userMemories(userId, 500)) {
            if (sessionSummary(value)) {
                memoryService.delete(value.id(), value.revisionNo());
                sessionDeleted++;
            } else if (dailySummary(value)) {
                memoryService.delete(value.id(), value.revisionNo());
                dailyDeleted++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_summaries_deleted", sessionDeleted);
        result.put("daily_summaries_deleted", dailyDeleted);
        result.put("history_deleted", 0);
        return result;
    }

    /**
     * 处理{@code ltm}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, String> ltm() {
        Map<String, String> result = new LinkedHashMap<>();
        for (MemoryView value : userMemories(current().id(), 500)) {
            if (!ltmMemory(value)) {
                continue;
            }
            String key = metadataText(value, "key");
            if (key == null || key.isBlank()) {
                key = value.memoryKey();
            }
            result.put(key, value.content());
        }
        return result;
    }

    /**
     * 处理{@code putLtm}相关逻辑。
     *
     * @param key {@code key}参数
     * @param content 待处理内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void putLtm(String key, String content) {
        CurrentPrincipal principal = current();
        requireMemoryEnabled("memory.ltm_update");
        String normalizedKey = text(key, 128, "长期记忆键").toLowerCase(Locale.ROOT);
        String normalizedContent = text(content, 4000, "长期记忆内容");
        MemoryView existing = userMemories(principal.id(), 500).stream()
            .filter(this::ltmMemory)
            .filter(value -> normalizedKey.equalsIgnoreCase(metadataText(value, "key")))
            .findFirst()
            .orElse(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "ltm");
        metadata.put("key", normalizedKey);
        if (existing == null) {
            memoryService.create("user", principal.id(), new CreateMemoryRequest(
                "ltm-" + Integer.toUnsignedString(normalizedKey.hashCode()), "preference",
                normalizedContent, "manual", null, 1.0, "internal", null, metadata
            ));
            return;
        }
        memoryService.update(existing.id(), new UpdateMemoryRequest(
            existing.revisionNo(), "preference", normalizedContent, "manual", null, 1.0,
            "internal", null, metadata
        ));
    }

    /**
     * 删除{@code Ltm}。
     *
     * @param key {@code key}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteLtm(String key) {
        String normalized = text(key, 128, "长期记忆键");
        for (MemoryView value : userMemories(current().id(), 500)) {
            if (ltmMemory(value) && normalized.equalsIgnoreCase(metadataText(value, "key"))) {
                memoryService.delete(value.id(), value.revisionNo());
                return;
            }
        }
        throw new ServiceException("长期记忆不存在", HttpStatus.NOT_FOUND);
    }

    /**
     * 处理{@code dailySummaries}并返回对应结果。
     *
     * @param keyword {@code keyword}参数
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> dailySummaries(String keyword, String from, String to, int limit) {
        return dailySummariesForUser(current().id(), keyword, from, to, limit);
    }

    /**
     * 处理dailySummariesFor用户并返回对应结果。
     *
     * @param userId 资源标识
     * @param keyword {@code keyword}参数
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> dailySummariesForUser(
        Long userId, String keyword, String from, String to, int limit
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        requireUserAccess(userId);
        String dateFrom = from == null || from.isBlank() ? null : validDate(from);
        String dateTo = to == null || to.isBlank() ? null : validDate(to);
        String query = keyword == null ? null : keyword.strip().toLowerCase(Locale.ROOT);
        Map<String, List<Map<String, Object>>> sessionsByDate = new LinkedHashMap<>();
        Map<String, MemoryView> dailyByDate = new LinkedHashMap<>();
        for (MemoryView memory : userMemories(userId, 500)) {
            String date = dateOf(memory);
            if (date == null || !within(date, dateFrom, dateTo)) {
                continue;
            }
            if (sessionSummary(memory)) {
                sessionsByDate.computeIfAbsent(date, ignored -> new ArrayList<>())
                    .add(summaryView(memory, userId));
            } else if (dailySummary(memory)) {
                dailyByDate.merge(date, memory, this::newer);
            }
        }
        TreeSet<String> dates = new TreeSet<>(Comparator.reverseOrder());
        dates.addAll(sessionsByDate.keySet());
        dates.addAll(dailyByDate.keySet());
        return dates.stream()
            .filter(date -> matchesDailyQuery(query, dailyByDate.get(date), sessionsByDate.getOrDefault(date, List.of())))
            .limit(Math.min(Math.max(limit, 1), 200))
            .map(date -> dailyView(
                userId, date, sessionsByDate.getOrDefault(date, List.of()), dailyByDate.get(date)
            ))
            .toList();
    }

    /**
     * 处理{@code dailyDetail}并返回对应结果。
     *
     * @param day {@code day}参数
     * @return 处理结果
     */
    public Map<String, Object> dailyDetail(String day) {
        return dailyDetailForUser(current().id(), day);
    }

    /**
     * 处理dailyDetailFor用户并返回对应结果。
     *
     * @param userId 资源标识
     * @param day {@code day}参数
     * @return 处理结果
     */
    public Map<String, Object> dailyDetailForUser(Long userId, String day) {
        requireUserAccess(userId);
        String date = validDate(day);
        List<MemoryView> memories = userMemories(userId, 500);
        List<Map<String, Object>> sessions = memories.stream()
            .filter(this::sessionSummary)
            .filter(value -> date.equals(dateOf(value)))
            .map(value -> summaryView(value, userId))
            .toList();
        MemoryView stored = memories.stream()
            .filter(this::dailySummary)
            .filter(value -> date.equals(dateOf(value)))
            .reduce(this::newer)
            .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", dailyView(userId, date, sessions, stored));
        result.put("sessions", sessions);
        return result;
    }

    /**
     * 删除{@code Daily}。
     *
     * @param day {@code day}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDaily(String day) {
        deleteDailyForUser(current().id(), day);
    }

    /**
     * 删除DailyFor用户。
     *
     * @param userId 资源标识
     * @param day {@code day}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDailyForUser(Long userId, String day) {
        requireUserAccess(userId);
        String date = validDate(day);
        boolean deleted = false;
        for (MemoryView value : userMemories(userId, 500)) {
            if (dailySummary(value) && date.equals(dateOf(value))) {
                memoryService.delete(value.id(), value.revisionNo());
                deleted = true;
            }
        }
        if (!deleted) {
            throw new ServiceException("指定日期没有已保存的每日摘要", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 处理{@code rebuildDaily}并返回对应结果。
     *
     * @param day {@code day}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuildDaily(String day) {
        return rebuildDailyForUser(current().id(), day);
    }

    /**
     * 处理rebuildDailyFor用户并返回对应结果。
     *
     * @param userId 资源标识
     * @param day {@code day}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuildDailyForUser(Long userId, String day) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireUserAccess(userId);
        requireSummaryEnabled("memory.daily_rebuild");
        String date = validDate(day);
        List<MemoryView> memories = userMemories(userId, 500);
        List<Map<String, Object>> sessions = memories.stream()
            .filter(this::sessionSummary)
            .filter(value -> date.equals(dateOf(value)))
            .map(value -> summaryView(value, userId))
            .toList();
        if (sessions.isEmpty()) {
            throw new ServiceException("指定日期没有可整理的会话摘要", HttpStatus.NOT_FOUND);
        }
        String content = boundedSummary(
            sessions.stream().map(value -> "- " + value.get("summary"))
                .reduce((a, b) -> a + "\n" + b).orElse(""),
            4000
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "daily_summary");
        metadata.put("date", date);
        MemoryView existing = memories.stream()
            .filter(this::dailySummary)
            .filter(value -> date.equals(dateOf(value)))
            .reduce(this::newer)
            .orElse(null);
        MemoryView stored;
        if (existing == null) {
            stored = memoryService.create("user", userId, new CreateMemoryRequest(
                "daily-summary-" + date, "summary", content, "manual", null, 1.0,
                "internal", null, metadata
            ));
        } else {
            stored = memoryService.update(existing.id(), new UpdateMemoryRequest(
                existing.revisionNo(), "summary", content, "manual", null, 1.0,
                "internal", null, metadata
            ));
        }
        return dailyView(userId, date, sessions, stored);
    }

    /**
     * 查询{@code View}列表。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> searchView(MemoryView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("memory_key", value.memoryKey());
        result.put("memory_type", value.memoryType());
        result.put("content", value.content());
        result.put("source_type", value.sourceType());
        result.put("source_id", value.sourceId());
        result.put("sensitive_level", value.sensitiveLevel());
        result.put("metadata", value.metadata());
        result.put("updated_at", value.updatedAt());
        return result;
    }

    /**
     * 处理记忆Config并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private MemoryView memoryConfig(Long userId) {
        return userMemories(userId, 500).stream()
            .filter(this::configMemory)
            .findFirst()
            .orElse(null);
    }

    /**
     * 处理config记忆并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean configMemory(MemoryView value) {
        return "memory_config".equalsIgnoreCase(metadataText(value, "kind"));
    }

    /**
     * 处理{@code configLimit}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private int configLimit(Long userId) {
        if (vectorService != null) {
            return vectorService.settings().searchKnnTopK();
        }
        MemoryView config = memoryConfig(userId);
        return config == null ? 50 : configLimit(config);
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }

    /**
     * 处理{@code configLimit}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    private int configLimit(MemoryView config) {
        Object raw = config.metadata() == null
            ? null : config.metadata().get("default_search_limit");
        if (raw instanceof Number number) {
            return Math.min(200, Math.max(1, number.intValue()));
        }
        try {
            return Math.min(200, Math.max(1, Integer.parseInt(String.valueOf(raw))));
        } catch (RuntimeException ignored) {
            return 50;
        }
    }

    /**
     * 处理用户Memories并返回对应结果。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<MemoryView> userMemories(Long userId, int limit) {
        requireUserAccess(userId);
        return memoryService.list("user", userId, null, Math.min(limit, 500));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> history(Long userId, String conversationId, int limit) {
        try {
            long id = Long.parseLong(conversationId);
            if (conversationMapper.selectOwnedConversation(id, userId) == null) {
                return List.of();
            }
            return conversationMapper.selectMessages(id, 0, Math.min(Math.max(limit, 1), 100)).stream()
                .map(this::message)
                .toList();
        } catch (NumberFormatException exception) {
            return List.of();
        }
    }

    /**
     * 处理消息并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> message(ConversationMessageRow value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.getId());
        result.put("role", value.getRole());
        result.put("content", value.getContent());
        result.put("created_at", value.getCreatedAt());
        return result;
    }

    /**
     * 处理{@code summaryView}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> summaryView(MemoryView value, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("conversation_id", conversationId(value));
        result.put("summary", value.content());
        result.put("content", value.content());
        result.put("last_active", value.updatedAt() == null ? null : value.updatedAt().toEpochSecond(ZoneOffset.UTC));
        result.put("updated_at", value.updatedAt());
        result.put("created_at", value.createdAt());
        result.put("memory_type", value.memoryType());
        result.put("has_history", !history(userId, conversationId(value), 1).isEmpty());
        result.put("metadata", value.metadata());
        return result;
    }

    /**
     * 处理{@code dailyView}并返回对应结果。
     *
     * @param userId 资源标识
     * @param date {@code date}参数
     * @param sessions {@code sessions}参数
     * @param stored {@code stored}参数
     * @return 处理结果
     */
    private Map<String, Object> dailyView(
        Long userId,
        String date,
        List<Map<String, Object>> sessions,
        MemoryView stored
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("summary", stored == null ? derivedDailyContent(sessions) : stored.content());
        result.put("session_count", sessions.size());
        result.put("user_id", userId);
        result.put("stored", stored != null);
        result.put("id", stored == null ? null : stored.id());
        result.put("updated_at", stored == null ? null : stored.updatedAt());
        return result;
    }

    /**
     * 处理{@code derivedDailyContent}并返回对应结果。
     *
     * @param sessions {@code sessions}参数
     * @return 处理结果
     */
    private String derivedDailyContent(List<Map<String, Object>> sessions) {
        return boundedSummary(
            sessions.stream().map(value -> String.valueOf(value.get("summary")))
                .reduce((a, b) -> a + "\n" + b).orElse(""),
            4000
        );
    }

    /**
     * 处理{@code boundedSummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String boundedSummary(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= maximum) {
            return normalized;
        }
        int start = normalized.length() - maximum;
        if (start < normalized.length() && Character.isLowSurrogate(normalized.charAt(start))) {
            start++;
        }
        return normalized.substring(Math.min(start, normalized.length()));
    }

    /**
     * 判断Daily查询是否满足要求。
     *
     * @param query 查询参数
     * @param stored {@code stored}参数
     * @param sessions {@code sessions}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matchesDailyQuery(
        String query,
        MemoryView stored,
        List<Map<String, Object>> sessions
    ) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (stored != null && stored.content().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return sessions.stream().anyMatch(value ->
            String.valueOf(value.get("summary")).toLowerCase(Locale.ROOT).contains(query)
                || String.valueOf(value.get("conversation_id")).toLowerCase(Locale.ROOT).contains(query)
        );
    }

    /**
     * 处理{@code newer}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 处理结果
     */
    private MemoryView newer(MemoryView left, MemoryView right) {
        if (left.updatedAt() == null) {
            return right;
        }
        if (right.updatedAt() == null) {
            return left;
        }
        return right.updatedAt().isAfter(left.updatedAt()) ? right : left;
    }

    /**
     * 处理会话Summary并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sessionSummary(MemoryView value) {
        return "summary".equals(value.memoryType()) && !dailySummary(value)
            && (metadataText(value, "conversation_id") != null || value.sourceId() != null);
    }

    /**
     * 处理{@code dailySummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean dailySummary(MemoryView value) {
        return "daily_summary".equalsIgnoreCase(metadataText(value, "kind"));
    }

    /**
     * 处理ltm记忆并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean ltmMemory(MemoryView value) {
        return "preference".equals(value.memoryType()) && "ltm".equalsIgnoreCase(metadataText(value, "kind"));
    }

    /**
     * 处理会话Id并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String conversationId(MemoryView value) {
        String id = metadataText(value, "conversation_id");
        return id == null ? String.valueOf(value.sourceId()) : id;
    }

    /**
     * 处理{@code dateOf}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String dateOf(MemoryView value) {
        String date = metadataText(value, "date");
        if (date != null) {
            return date;
        }
        return value.updatedAt() == null ? null : value.updatedAt().toLocalDate().toString();
    }

    /**
     * 处理元数据Text并返回对应结果。
     *
     * @param value {@code value}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String metadataText(MemoryView value, String key) {
        Object raw = value.metadata() == null ? null : value.metadata().get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 处理{@code within}并返回对应结果。
     *
     * @param date {@code date}参数
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean within(String date, String from, String to) {
        if (from != null && date.compareTo(from) < 0) {
            return false;
        }
        return to == null || date.compareTo(to) <= 0;
    }

    /**
     * 处理{@code validDate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String validDate(String value) {
        try {
            return LocalDate.parse(text(value, 10, "日期")).toString();
        } catch (RuntimeException exception) {
            throw new ServiceException("日期格式必须为 YYYY-MM-DD", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理当前并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal current() {
        return principalProvider.currentPrincipal();
    }

    /**
     * 处理记忆Enabled并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean memoryEnabled() {
        return vectorService == null || vectorService.settings().enabled();
    }

    /**
     * 处理{@code summaryEnabled}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean summaryEnabled() {
        return vectorService == null || vectorService.settings().summaryEnabled();
    }

    /**
     * 校验记忆Enabled，并在条件不满足时终止处理。
     *
     * @param operation 操作参数
     */
    private void requireMemoryEnabled(String operation) {
        if (memoryEnabled()) {
            return;
        }
        CurrentPrincipal principal = current();
        operationsAuditService.record(
            principal, operation, principal.id(), "unavailable",
            "memory service disabled by platform configuration", "enabled=false"
        );
        throw new ServiceException("记忆服务已由平台配置关闭", 503);
    }

    /**
     * 校验{@code SummaryEnabled}，并在条件不满足时终止处理。
     *
     * @param operation 操作参数
     */
    private void requireSummaryEnabled(String operation) {
        if (memoryEnabled() && summaryEnabled()) {
            return;
        }
        CurrentPrincipal principal = current();
        operationsAuditService.record(
            principal, operation, principal.id(), "unavailable",
            "memory summary projection disabled by platform configuration",
            "enabled=" + memoryEnabled() + ", summaryEnabled=" + summaryEnabled()
        );
        throw new ServiceException("会话摘要功能已由平台配置关闭", 503);
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @param action {@code action}参数
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator(String action) {
        CurrentPrincipal principal = current();
        if (principal == null || !principal.isHuman() || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            if (principal != null) {
                operationsAuditService.record(
                    principal, action, principal.id(), "deny", "platform administrator role required",
                    "ownerId=" + principal.id()
                );
            }
            throw new ServiceException("仅平台管理员可以执行记忆运维操作", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理审计Failure相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param exception {@code exception}参数
     * @param summary {@code summary}参数
     */
    private void auditFailure(
        CurrentPrincipal principal,
        String action,
        RuntimeException exception,
        String summary
    ) {
        String reason = exception instanceof ServiceException serviceException
            ? "service_error(code=" + serviceException.getCode() + ")"
            : "runtime_error(type=" + exception.getClass().getSimpleName() + ")";
        operationsAuditService.record(principal, action, principal.id(), "failure", reason, summary);
    }

    /**
     * 校验用户Access，并在条件不满足时终止处理。
     *
     * @param userId 资源标识
     */
    private void requireUserAccess(Long userId) {
        CurrentPrincipal principal = current();
        if (!principal.id().equals(userId) && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("没有查看该用户记忆的权限", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 封装会话记忆Finalize相关的不可变数据。
     */
    public record ConversationMemoryFinalizeResult(
        Long memoryId,
        boolean changed,
        String date,
        boolean dailySummaryRefreshed
    ) {
    }
}
