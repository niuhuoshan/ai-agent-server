package group.aitools.nhs.platform.artifact.service;

import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import group.aitools.nhs.platform.artifact.mapper.ArtifactAcceptanceMapper;
import group.aitools.nhs.platform.artifact.persistence.row.ArtifactTaskRow;
import group.aitools.nhs.platform.artifact.web.ArtifactView;
import group.aitools.nhs.platform.artifact.web.RegisterArtifactRequest;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentTaskRun;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.audit.service.AuthorizationAuditService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 负责制品相关的业务编排与领域规则处理。
 * Registers immutable artifact versions and applies artifact-level visibility. */
@Service
public class ArtifactApplicationService {

    private static final Set<String> ARTIFACT_TYPES = Set.of(
        "code_change", "document", "data_table", "chart", "test_report", "log_summary", "json", "file"
    );
    private static final Set<String> STORAGE_TYPES = Set.of("local", "oss", "s3", "external");
    private static final Set<String> SENSITIVE_LEVELS = Set.of("public", "internal", "sensitive", "secret");
    private static final Set<String> VISIBILITIES = Set.of("inherit", "private", "enterprise_shared", "restricted");
    private static final Set<String> ARTIFACTABLE_RUN_STATUSES = Set.of(
        "running", "waiting_approval", "waiting_input", "blocked", "paused",
        "verifying", "succeeded", "failed"
    );
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,1023}");
    private static final Set<String> SECRET_PARTS = Set.of(
        "secret", "password", "token", "apikey", "api_key", "authorization", "credential", "privatekey"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AuthorizationAuditService auditService;
    private final TaskVisibilityService taskVisibilityService;
    private final TaskQueryService taskQueryService;
    private final ArtifactAcceptanceMapper artifactMapper;
    private final TaskRunCommandMapper runMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final NotificationApplicationService notificationService;

    /**
     * 创建 {@code ArtifactApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param auditService 审计Service参数
     * @param taskVisibilityService 任务VisibilityService参数
     * @param taskQueryService 任务查询Service参数
     * @param artifactMapper 制品Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param notificationService 通知Service参数
     */
    public ArtifactApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AuthorizationAuditService auditService,
        TaskVisibilityService taskVisibilityService,
        TaskQueryService taskQueryService,
        ArtifactAcceptanceMapper artifactMapper,
        TaskRunCommandMapper runMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NotificationApplicationService notificationService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.auditService = auditService;
        this.taskVisibilityService = taskVisibilityService;
        this.taskQueryService = taskQueryService;
        this.artifactMapper = artifactMapper;
        this.runMapper = runMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.notificationService = notificationService;
    }

    /**
     * 创建并保存{@code register}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param input {@code input}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ArtifactView register(Long taskId, Long runId, RegisterArtifactRequest input) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        runMapper.lockTask(taskId);
        ArtifactTaskRow task = requireTask(artifactMapper.selectTask(taskId));
        AgentTaskRun run = requireRun(taskId, runId);
        authorizeTaskOperation(principal, taskId, "operate");
        if (!ARTIFACTABLE_RUN_STATUSES.contains(run.getStatus())) {
            throw conflict("当前运行状态不允许登记制品：" + run.getStatus());
        }

        String artifactType = normalizeEnum(input.artifactType(), "file", ARTIFACT_TYPES, "制品类型");
        String name = normalizeName(input.name());
        String storageType = normalizeEnum(input.storageType(), "local", STORAGE_TYPES, "存储类型");
        String storageRef = normalizeStorageRef(storageType, input.storageRef());
        String contentHash = input.contentHash() == null ? null : input.contentHash().toLowerCase(Locale.ROOT);
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new ServiceException("制品内容哈希必须为64位SHA-256", HttpStatus.BAD_REQUEST);
        }
        String sensitiveLevel = normalizeEnum(input.sensitiveLevel(), "internal", SENSITIVE_LEVELS, "敏感级别");
        String visibility = normalizeEnum(input.visibility(), "inherit", VISIBILITIES, "可见性");
        if (Set.of("sensitive", "secret").contains(sensitiveLevel)
            && Set.of("inherit", "enterprise_shared").contains(visibility)) {
            throw new ServiceException("敏感制品不能继承企业共享可见性", HttpStatus.BAD_REQUEST);
        }
        if (input.stepId() != null) {
            if (input.stepId() <= 0 || !artifactMapper.stepBelongsToRun(runId, input.stepId())) {
                throw new ServiceException("制品步骤不属于当前运行", HttpStatus.BAD_REQUEST);
            }
        }
        String metadataJson = metadataJson(input.metadata());
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(idGenerator.nextId());
        artifact.setProjectId(task.getProjectId());
        artifact.setTaskId(taskId);
        artifact.setRunId(runId);
        artifact.setStepId(input.stepId());
        artifact.setArtifactType(artifactType);
        artifact.setName(name);
        artifact.setVersionNo(artifactMapper.selectNextVersion(taskId, runId, artifactType, name));
        artifact.setStorageType(storageType);
        artifact.setStorageRef(storageRef);
        artifact.setMimeType(normalizeMime(input.mimeType()));
        artifact.setSizeBytes(input.sizeBytes());
        artifact.setContentHash(contentHash);
        artifact.setSensitiveLevel(sensitiveLevel);
        artifact.setVisibility(visibility);
        artifact.setStatus("available");
        artifact.setMetadataJson(metadataJson);
        artifact.setCreatedBy(principal.id());
        artifact.setCreatedAt(LocalDateTime.now());
        if (artifactMapper.insertArtifact(artifact) != 1) {
            throw conflict("制品版本登记失败");
        }
        notificationService.publishTaskOwner(taskId, new NotificationMessage(
            "artifact:available:" + artifact.getId(),
            "artifact",
            "info",
            "任务生成了新制品",
            artifact.getName(),
            "artifact",
            artifact.getId()
        ));
        return ArtifactView.from(artifactMapper.selectArtifact(taskId, artifact.getId()), jsonMapper);
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ArtifactView> list(Long taskId, Long runId, int limit) {
        taskQueryService.get(taskId);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        ArtifactTaskRow task = requireTask(artifactMapper.selectTask(taskId));
        return artifactMapper.selectArtifacts(taskId, runId, limit).stream()
            .filter(artifact -> canView(principal, task, artifact))
            .map(artifact -> ArtifactView.from(artifact, jsonMapper))
            .toList();
    }

    /**
     * 查询{@code As}列表。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ArtifactView> listAs(
        CurrentPrincipal principal,
        Long taskId,
        Long runId,
        int limit
    ) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", taskId, null, "view", ResourceState.ACTIVE, false, Set.of(), taskId
        ));
        ArtifactTaskRow task = requireTask(artifactMapper.selectTask(taskId));
        if (runId != null) {
            requireRun(taskId, runId);
        }
        return artifactMapper.selectArtifacts(taskId, runId, limit).stream()
            .filter(artifact -> canView(principal, task, artifact))
            .map(artifact -> ArtifactView.from(artifact, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @param artifactId 资源标识
     * @return 处理结果
     */
    public ArtifactView get(Long taskId, Long artifactId) {
        taskQueryService.get(taskId);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        ArtifactTaskRow task = requireTask(artifactMapper.selectTask(taskId));
        AgentArtifact artifact = artifactMapper.selectArtifact(taskId, artifactId);
        if (artifact == null || "deleted".equals(artifact.getStatus())
            || !canView(principal, task, artifact)) {
            throw new ServiceException("制品不存在", HttpStatus.NOT_FOUND);
        }
        return ArtifactView.from(artifact, jsonMapper);
    }

    /**
     * 判断{@code View}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param task 任务参数
     * @param artifact 制品参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canView(CurrentPrincipal principal, ArtifactTaskRow task, AgentArtifact artifact) {
        if ("quarantined".equals(artifact.getStatus())
            && !principal.hasRole(group.aitools.nhs.platform.iam.domain.PlatformRole.PLATFORM_ADMIN)
            && !principal.id().equals(artifact.getCreatedBy())) {
            return false;
        }
        TaskVisibility taskVisibility = "restricted".equals(task.getVisibility())
            ? TaskVisibility.RESTRICTED : TaskVisibility.ENTERPRISE_SHARED;
        String visibility = artifact.getVisibility();
        AuthorizationDecision decision;
        if ("private".equals(visibility) && principal.id().equals(artifact.getCreatedBy())) {
            decision = authorizationEnforcer.decide(principal, new PermissionContext(
                "artifact", artifact.getId(), null, "view", ResourceState.ACTIVE, false,
                Set.of(BusinessRelation.OWNER), artifact.getTaskId()
            ));
        } else {
            TaskVisibility effective = "inherit".equals(visibility)
                ? taskVisibility
                : ("enterprise_shared".equals(visibility)
                    ? TaskVisibility.ENTERPRISE_SHARED : TaskVisibility.RESTRICTED);
            decision = taskVisibilityService.authorizeView(
                principal, artifact.getTaskId(), artifact.getId(), effective
            );
            auditService.record(principal, new PermissionContext(
                "artifact", artifact.getId(), null, "view", ResourceState.ACTIVE, false,
                Set.of(), artifact.getTaskId()
            ), decision);
        }
        return decision.allowed();
    }

    /**
     * 处理authorize任务操作相关逻辑。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param action {@code action}参数
     */
    private void authorizeTaskOperation(CurrentPrincipal principal, Long taskId, String action) {
        Set<BusinessRelation> relations = runMapper.selectRelations(
            taskId, principal.id(), principal.type().name().toLowerCase(Locale.ROOT)
        ).stream().map(BusinessRelation::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", taskId, null, action, ResourceState.ACTIVE, principal.isHuman(), relations, taskId
        ));
    }

    /**
     * 校验{@code Run}，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    private AgentTaskRun requireRun(Long taskId, Long runId) {
        AgentTaskRun run = runMapper.selectRun(taskId, runId);
        if (run == null) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        return run;
    }

    /**
     * 校验任务，并在条件不满足时终止处理。
     *
     * @param task 任务参数
     * @return 处理结果
     */
    private ArtifactTaskRow requireTask(ArtifactTaskRow task) {
        if (task == null) {
            throw new ServiceException("任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    /**
     * 处理{@code normalizeEnum}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String normalizeEnum(String value, String defaultValue, Set<String> allowed, String label) {
        String normalized = value == null || value.isBlank()
            ? defaultValue : value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code normalizeName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeName(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
            || value.contains("/") || value.contains("\\") || value.length() > 255) {
            throw new ServiceException("制品名称无效", HttpStatus.BAD_REQUEST);
        }
        return value.strip();
    }

    /**
     * 处理normalize存储Ref并返回对应结果。
     *
     * @param storageType 业务类型
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeStorageRef(String storageType, String value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
            throw new ServiceException("制品存储引用无效", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if ("external".equals(storageType)) {
            try {
                URI uri = new URI(normalized);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException("unsafe external uri");
                }
                return uri.toString();
            } catch (URISyntaxException | IllegalArgumentException exception) {
                throw new ServiceException("外部制品引用必须是无凭证的HTTPS地址", HttpStatus.BAD_REQUEST);
            }
        }
        if (!SAFE_KEY.matcher(normalized).matches() || normalized.contains("..")) {
            throw new ServiceException("制品存储引用必须是安全的不透明对象键", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code normalizeMime}并返回对应结果。
     *
     * @param mimeType 业务类型
     * @return 处理结果
     */
    private String normalizeMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        if (mimeType.length() > 128 || mimeType.indexOf('\0') >= 0) {
            throw new ServiceException("制品MIME类型无效", HttpStatus.BAD_REQUEST);
        }
        return mimeType.strip();
    }

    /**
     * 处理元数据Json并返回对应结果。
     *
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private String metadataJson(Map<String, Object> metadata) {
        Map<String, Object> normalized = metadata == null ? Map.of() : canonicalMap(metadata, 0);
        String json = jsonMapper.writeValueAsString(normalized);
        if (json.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new ServiceException("制品元数据超过64KB", HttpStatus.BAD_REQUEST);
        }
        return json;
    }

    /**
     * 判断{@code onicalMap}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalMap(Map<String, Object> value, int depth) {
        if (depth > 16) {
            throw new ServiceException("制品元数据嵌套过深", HttpStatus.BAD_REQUEST);
        }
        TreeMap<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > 128 || isSecretKey(key)) {
                throw new ServiceException("制品元数据包含敏感字段", HttpStatus.BAD_REQUEST);
            }
            result.put(key, canonicalValue(entry.getValue(), depth + 1));
        }
        return new LinkedHashMap<>(result);
    }

    /**
     * 判断{@code onicalValue}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Object canonicalValue(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                if (!(key instanceof String text)) {
                    throw new ServiceException("制品元数据键必须是文本", HttpStatus.BAD_REQUEST);
                }
                typed.put(text, entry);
            });
            return canonicalMap(typed, depth);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> canonicalValue(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new ServiceException("制品元数据包含不支持的值", HttpStatus.BAD_REQUEST);
    }

    /**
     * 判断{@code SecretKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SECRET_PARTS.stream().anyMatch(normalized::contains);
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
}
