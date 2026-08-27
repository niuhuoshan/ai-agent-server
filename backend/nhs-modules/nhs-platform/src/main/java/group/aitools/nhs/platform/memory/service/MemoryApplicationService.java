package group.aitools.nhs.platform.memory.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.web.CreateMemoryRequest;
import group.aitools.nhs.platform.memory.web.MemoryView;
import group.aitools.nhs.platform.memory.web.ReviewMemoryRequest;
import group.aitools.nhs.platform.memory.web.UpdateMemoryRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责记忆相关的业务编排与领域规则处理。
 */
@Service
public class MemoryApplicationService {

    private static final Pattern MEMORY_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Set<String> MEMORY_TYPES = Set.of(
        "summary", "preference", "fact", "feedback", "candidate"
    );
    private static final Set<String> SOURCE_TYPES = Set.of(
        "conversation", "task", "artifact", "manual"
    );
    private static final Set<String> SENSITIVE_LEVELS = Set.of(
        "public", "internal", "sensitive", "secret"
    );
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
        "password", "secret", "token", "apikey", "api_key", "credential", "privatekey"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final MemoryScopeAuthorizationService scopeAuthorization;
    private final PlatformIdGenerator idGenerator;
    private final MemoryCatalogMapper mapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code MemoryApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param scopeAuthorization 范围授权参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public MemoryApplicationService(
        CurrentPrincipalProvider principalProvider,
        MemoryScopeAuthorizationService scopeAuthorization,
        PlatformIdGenerator idGenerator,
        MemoryCatalogMapper mapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.scopeAuthorization = scopeAuthorization;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    public java.util.List<MemoryView> list(
        String scopeType, Long scopeId, String search, int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String scope = normalizeScope(scopeType);
        scopeAuthorization.requireView(principal, scope, scopeId, true);
        boolean includeSensitive = "user".equals(scope)
            || scopeAuthorization.canManage(principal, scope, scopeId);
        String normalizedSearch = normalizeSearch(search);
        return mapper.selectScopeMemories(
            scope, scopeId, includeSensitive, normalizedSearch, limit
        ).stream().map(memory -> MemoryView.from(memory, jsonMapper)).toList();
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryView create(String scopeType, Long scopeId, CreateMemoryRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String scope = normalizeScope(scopeType);
        scopeAuthorization.requireManage(principal, scope, scopeId, true);
        PreparedMemory prepared = prepare(
            request.memoryType(), request.content(), request.sourceType(), request.sourceId(),
            request.confidence(), request.sensitiveLevel(), request.expiresAt(), request.metadata()
        );
        String key = request.memoryKey().strip().toLowerCase(Locale.ROOT);
        if (!MEMORY_KEY.matcher(key).matches()) {
            throw badRequest("记忆标识无效");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMemory memory = new AgentMemory();
        memory.setId(idGenerator.nextId());
        memory.setMemoryKey(key);
        memory.setScopeType(scope);
        memory.setScopeId(scopeId);
        apply(memory, prepared);
        memory.setRevisionNo(1L);
        memory.setCreatedBy(principal.id());
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setDelFlag("0");
        approveOwnUserMemory(memory, principal, now);
        try {
            mapper.insertMemory(memory);
        } catch (DuplicateKeyException exception) {
            throw conflict("该作用域已存在相同记忆标识");
        }
        return MemoryView.from(memory, jsonMapper);
    }

    /**
     * 更新{@code update}。
     *
     * @param memoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryView update(Long memoryId, UpdateMemoryRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockById(memoryId);
        AgentMemory memory = requireMemory(memoryId);
        scopeAuthorization.requireManage(
            principal, memory.getScopeType(), memory.getScopeId(), true
        );
        if (!request.expectedRevision().equals(memory.getRevisionNo())) {
            throw conflict("记忆已被其他请求修改");
        }
        PreparedMemory prepared = prepare(
            request.memoryType(), request.content(), request.sourceType(), request.sourceId(),
            request.confidence(), request.sensitiveLevel(), request.expiresAt(), request.metadata()
        );
        apply(memory, prepared);
        memory.setRevisionNo(request.expectedRevision());
        memory.setReviewStatus("pending");
        memory.setReviewedBy(null);
        memory.setReviewedAt(null);
        memory.setReviewComment(null);
        memory.setUpdatedAt(LocalDateTime.now());
        approveOwnUserMemory(memory, principal, memory.getUpdatedAt());
        if (mapper.updateMemory(memory) != 1) {
            throw conflict("记忆已被其他请求修改");
        }
        memory.setRevisionNo(memory.getRevisionNo() + 1);
        return MemoryView.from(memory, jsonMapper);
    }

    /**
     * 处理{@code review}并返回对应结果。
     *
     * @param memoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryView review(Long memoryId, ReviewMemoryRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockById(memoryId);
        AgentMemory memory = requireMemory(memoryId);
        if ("user".equals(memory.getScopeType())) {
            throw conflict("个人记忆由用户显式保存后直接批准，不进入共享审核");
        }
        scopeAuthorization.requireManage(
            principal, memory.getScopeType(), memory.getScopeId(), true
        );
        String decision = requiredEnum(
            request.decision(), Set.of("approved", "rejected"), "审核决定"
        );
        String comment = optionalText(request.comment(), 2000, "审核说明");
        if (mapper.reviewMemory(
            memoryId, request.expectedRevision(), decision, principal.id(), comment,
            LocalDateTime.now()
        ) != 1) {
            throw conflict("记忆不是待审核状态或已被修改");
        }
        return MemoryView.from(requireMemory(memoryId), jsonMapper);
    }

    /**
     * 删除{@code delete}。
     *
     * @param memoryId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long memoryId, Long expectedRevision) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockById(memoryId);
        AgentMemory memory = requireMemory(memoryId);
        scopeAuthorization.requireManage(
            principal, memory.getScopeType(), memory.getScopeId(), true
        );
        if (mapper.softDelete(memoryId, expectedRevision, LocalDateTime.now()) != 1) {
            throw conflict("记忆已被其他请求修改");
        }
    }

    /**
 * 删除{@code Batch}。
 *
     * Atomically soft-deletes a set of memories after locking and authorizing
     * every source row. Consolidation uses this so a stale source revision
     * cannot leave a partially deleted group.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<MemoryCatalogMapper.MemoryRevision> revisions) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (revisions == null || revisions.isEmpty()) {
            throw badRequest("记忆删除列表不能为空");
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Set<Long> ids = new HashSet<>();
        for (MemoryCatalogMapper.MemoryRevision revision : revisions) {
            if (revision == null || revision.id() == null || revision.revisionNo() == null
                || revision.id() <= 0 || revision.revisionNo() <= 0 || !ids.add(revision.id())) {
                throw badRequest("记忆删除版本无效");
            }
            mapper.lockById(revision.id());
            AgentMemory memory = requireMemory(revision.id());
            scopeAuthorization.requireManage(
                principal, memory.getScopeType(), memory.getScopeId(), true
            );
        }
        int deleted = mapper.softDeleteBatch(revisions, LocalDateTime.now());
        if (deleted != revisions.size()) {
            throw conflict("记忆在合并前已被其他请求修改");
        }
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param memory 记忆参数
     * @param prepared {@code prepared}参数
     */
    private void apply(AgentMemory memory, PreparedMemory prepared) {
        memory.setMemoryType(prepared.memoryType());
        memory.setContent(prepared.content());
        memory.setContentHash(ContentHashing.sha256(prepared.content()));
        memory.setSourceType(prepared.sourceType());
        memory.setSourceId(prepared.sourceId());
        memory.setConfidence(prepared.confidence());
        memory.setSensitiveLevel(prepared.sensitiveLevel());
        memory.setExpiresAt(prepared.expiresAt());
        memory.setMetadataJson(prepared.metadataJson());
        memory.setReviewStatus("pending");
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param memoryType 业务类型
     * @param content 待处理内容
     * @param sourceType 业务类型
     * @param sourceId 资源标识
     * @param confidence {@code confidence}参数
     * @param sensitiveLevel {@code sensitiveLevel}参数
     * @param expiresAt {@code expiresAt}参数
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private PreparedMemory prepare(
        String memoryType,
        String content,
        String sourceType,
        Long sourceId,
        Double confidence,
        String sensitiveLevel,
        LocalDateTime expiresAt,
        Map<String, Object> metadata
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String type = requiredEnum(memoryType, MEMORY_TYPES, "记忆类型");
        String normalizedContent = requiredText(content, 4000, "记忆正文");
        String source = requiredEnum(sourceType, SOURCE_TYPES, "记忆来源");
        if ((sourceId == null) != "manual".equals(source)) {
            throw badRequest("非手工记忆必须提供正数来源 ID，手工记忆不能伪造来源 ID");
        }
        if (sourceId != null && sourceId <= 0) {
            throw badRequest("记忆来源 ID 无效");
        }
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw badRequest("记忆置信度必须在 0-1 之间");
        }
        String sensitivity = sensitiveLevel == null || sensitiveLevel.isBlank()
            ? "internal" : requiredEnum(sensitiveLevel, SENSITIVE_LEVELS, "敏感级别");
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw badRequest("记忆过期时间必须晚于当前时间");
        }
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        rejectSensitiveMetadata(safeMetadata);
        String metadataJson = jsonMapper.writeValueAsString(safeMetadata);
        if (metadataJson.getBytes(StandardCharsets.UTF_8).length > 32 * 1024) {
            throw badRequest("记忆元数据超过 32KB 限制");
        }
        return new PreparedMemory(
            type, normalizedContent, source, sourceId, confidence, sensitivity,
            expiresAt, metadataJson
        );
    }

    /**
     * 处理approveOwn用户记忆相关逻辑。
     *
     * @param memory 记忆参数
     * @param principal 当前操作主体
     * @param now {@code now}参数
     */
    private void approveOwnUserMemory(
        AgentMemory memory, CurrentPrincipal principal, LocalDateTime now
    ) {
        if (!"user".equals(memory.getScopeType())) {
            return;
        }
        memory.setReviewStatus("approved");
        memory.setReviewedBy(principal.id());
        memory.setReviewedAt(now);
        memory.setReviewComment("用户显式保存");
    }

    /**
     * 处理rejectSensitive元数据相关逻辑。
     *
     * @param value {@code value}参数
     */
    private void rejectSensitiveMetadata(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replace("-", "")
                    .toLowerCase(Locale.ROOT);
                if (SENSITIVE_METADATA_KEYS.contains(key)) {
                    throw badRequest("记忆元数据不能包含密钥或凭证字段");
                }
                rejectSensitiveMetadata(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::rejectSensitiveMetadata);
        }
    }

    /**
     * 校验记忆，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentMemory requireMemory(Long id) {
        AgentMemory memory = mapper.selectById(id);
        if (memory == null) {
            throw new ServiceException("记忆不存在", HttpStatus.NOT_FOUND);
        }
        return memory;
    }

    /**
     * 处理normalize范围并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeScope(String value) {
        return requiredEnum(value, Set.of("user", "project", "task"), "记忆作用域");
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
            throw badRequest("记忆搜索词无效");
        }
        return normalized;
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param values {@code values}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> values, String label) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!values.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maximum, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalText(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "超过长度限制");
        }
        return normalized;
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
     * 封装Prepared记忆相关的不可变数据。
     */
    private record PreparedMemory(
        String memoryType,
        String content,
        String sourceType,
        Long sourceId,
        Double confidence,
        String sensitiveLevel,
        LocalDateTime expiresAt,
        String metadataJson
    ) {
    }
}
