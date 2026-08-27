package group.aitools.nhs.platform.artifact.service;

import group.aitools.nhs.platform.artifact.domain.AgentAcceptanceRecord;
import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import group.aitools.nhs.platform.artifact.mapper.ArtifactAcceptanceMapper;
import group.aitools.nhs.platform.artifact.persistence.row.AcceptanceTaskRow;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionRequest;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionResult;
import group.aitools.nhs.platform.artifact.web.AcceptanceView;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责验收相关的业务编排与领域规则处理。
 * Applies rule/human/combined acceptance and moves the task only after all gates pass. */
@Service
public class AcceptanceApplicationService {

    private static final Set<String> RESULTS = Set.of("passed", "rework", "rejected", "taken_over");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ArtifactAcceptanceMapper artifactMapper;
    private final TaskRunCommandMapper runMapper;
    private final TaskQueryService taskQueryService;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final NotificationApplicationService notificationService;

    /**
     * 创建 {@code AcceptanceApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param artifactMapper 制品Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param taskQueryService 任务查询Service参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param notificationService 通知Service参数
     */
    public AcceptanceApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ArtifactAcceptanceMapper artifactMapper,
        TaskRunCommandMapper runMapper,
        TaskQueryService taskQueryService,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NotificationApplicationService notificationService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.artifactMapper = artifactMapper;
        this.runMapper = runMapper;
        this.taskQueryService = taskQueryService;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.notificationService = notificationService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AcceptanceView> list(Long taskId, Long runId, int limit) {
        taskQueryService.get(taskId);
        if (artifactMapper.selectAcceptanceTask(taskId, runId) == null) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        return artifactMapper.selectAcceptances(taskId, runId, limit).stream()
            .map(record -> AcceptanceView.from(record, jsonMapper)).toList();
    }

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param input {@code input}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AcceptanceDecisionResult decide(
        Long taskId,
        Long runId,
        AcceptanceDecisionRequest input
    ) {
        return decideAs(principalProvider.currentPrincipal(), taskId, runId, input);
    }

    /**
 * 处理{@code decideAs}并返回对应结果。
 * Records acceptance as an already authenticated principal without consulting HTTP state. */
    @Transactional(rollbackFor = Exception.class)
    public AcceptanceDecisionResult decideAs(
        CurrentPrincipal principal,
        Long taskId,
        Long runId,
        AcceptanceDecisionRequest input
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Objects.requireNonNull(principal, "principal must not be null");
        String reviewerPrincipalType = principal.type().name().toLowerCase(Locale.ROOT);
        String result = normalizeResult(input);
        String key = normalizeKey(input);
        List<Long> artifactIds = normalizeArtifactIds(input.artifactIds());
        String comment = normalizeComment(input.comment());
        Map<String, Object> ruleResult = canonicalMap(input.ruleResult(), 0);
        String ruleJson = jsonMapper.writeValueAsString(ruleResult);
        String keyHash = ContentHashing.sha256("acceptance:" + taskId + ":" + runId + ":" + key);
        String requestHash = ContentHashing.sha256(jsonMapper.writeValueAsString(Map.of(
            "artifactIds", artifactIds,
            "result", result,
            "comment", comment == null ? "" : comment,
            "ruleResult", ruleResult
        )));

        runMapper.lockTask(taskId);
        AgentAcceptanceRecord existing = artifactMapper.selectAcceptanceByKey(keyHash);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())
                || !principal.id().equals(existing.getReviewerId())
                || !reviewerPrincipalType.equals(existing.getReviewerPrincipalType())) {
                throw conflict("同一验收幂等键不能用于不同请求或其他审核人");
            }
            return new AcceptanceDecisionResult(
                AcceptanceView.from(existing, jsonMapper), currentTaskStatus(taskId, runId), true
            );
        }

        AcceptanceTaskRow task = artifactMapper.selectAcceptanceTask(taskId, runId);
        if (task == null) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        Set<BusinessRelation> relations = runMapper.selectRelations(
                taskId, principal.id(), reviewerPrincipalType
            ).stream()
            .map(BusinessRelation::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", taskId, null, "accept", ResourceState.ACTIVE,
            principal.isHuman(), relations, taskId
        ));

        if (!runId.equals(task.getLatestRunId())) {
            throw conflict("只能验收任务的最新运行");
        }
        validateState(task, result);
        List<AgentArtifact> artifacts = artifactMapper.selectAvailableArtifacts(taskId, runId, artifactIds);
        if (artifacts.size() != artifactIds.size()) {
            throw conflict("验收引用的制品不存在、未就绪或不属于当前运行");
        }
        validateRequiredArtifacts(task.getAcceptanceSnapshotJson(), artifacts);
        validateRules(task.getAcceptanceMode(), result, ruleResult);
        if ("passed".equals(result) && artifactMapper.countPendingApprovals(taskId, runId) > 0) {
            throw conflict("仍有未处理的高风险审批，不能完成任务");
        }

        AgentAcceptanceRecord record = new AgentAcceptanceRecord();
        record.setId(idGenerator.nextId());
        record.setTaskId(taskId);
        record.setRunId(runId);
        record.setArtifactIdsJson(jsonMapper.writeValueAsString(artifactIds));
        record.setAcceptanceType(task.getAcceptanceMode());
        record.setResult(result);
        record.setRuleResultJson(ruleJson);
        record.setComment(comment);
        record.setReviewerId(principal.id());
        record.setReviewerPrincipalType(reviewerPrincipalType);
        record.setReworkNo(("rework".equals(result) || "taken_over".equals(result))
            ? artifactMapper.countReworks(taskId) + 1 : artifactMapper.countReworks(taskId));
        record.setCreatedAt(LocalDateTime.now());
        record.setIdempotencyKeyHash(keyHash);
        record.setRequestHash(requestHash);
        if (artifactMapper.insertAcceptance(record) != 1) {
            throw conflict("验收决策已由其他请求提交");
        }

        String targetStatus = switch (result) {
            case "passed" -> "completed";
            case "rework", "taken_over" -> "rework";
            case "rejected" -> "blocked";
            default -> throw new IllegalStateException("unreachable acceptance result");
        };
        List<String> expected = switch (targetStatus) {
            case "completed" -> List.of("verifying");
            case "rework" -> List.of("verifying", "blocked");
            default -> List.of("verifying", "blocked");
        };
        if (artifactMapper.transitionTask(taskId, runId, expected, targetStatus, principal.id()) != 1) {
            throw conflict("任务状态在验收时发生变化");
        }
        notificationService.publishTaskOwner(taskId, new NotificationMessage(
            "acceptance:" + result + ":" + record.getId(),
            "acceptance",
            "passed".equals(result) ? "success" : ("rejected".equals(result) ? "error" : "warning"),
            acceptanceTitle(result),
            "任务状态已更新为 " + targetStatus,
            "acceptance",
            record.getId()
        ));
        return new AcceptanceDecisionResult(
            AcceptanceView.from(record, jsonMapper), targetStatus, false
        );
    }

    /**
     * 处理验收Title并返回对应结果。
     *
     * @param result 结果参数
     * @return 处理结果
     */
    private String acceptanceTitle(String result) {
        return switch (result) {
            case "passed" -> "任务验收已通过";
            case "rework" -> "任务验收要求返工";
            case "rejected" -> "任务验收已拒绝";
            case "taken_over" -> "任务验收已人工接管";
            default -> throw new IllegalStateException("unreachable acceptance result");
        };
    }

    /**
     * 校验{@code State}，并在条件不满足时终止处理。
     *
     * @param task 任务参数
     * @param result 结果参数
     */
    private void validateState(AcceptanceTaskRow task, String result) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("passed".equals(result)) {
            if (!"verifying".equals(task.getTaskStatus()) || !"succeeded".equals(task.getRunStatus())) {
                throw conflict("只有运行成功且处于待验收状态的任务才能通过验收");
            }
            return;
        }
        if (!Set.of("verifying", "blocked").contains(task.getTaskStatus())) {
            throw conflict("当前任务状态不能提交该验收结果：" + task.getTaskStatus());
        }
        if (!Set.of("succeeded", "failed", "cancelled").contains(task.getRunStatus())) {
            throw conflict("运行尚未形成可验收结果");
        }
    }

    /**
     * 校验{@code RequiredArtifacts}，并在条件不满足时终止处理。
     *
     * @param snapshotJson 快照Json参数
     * @param artifacts {@code artifacts}参数
     */
    private void validateRequiredArtifacts(String snapshotJson, List<AgentArtifact> artifacts) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = jsonMapper.readValue(snapshotJson, MAP_TYPE);
        } catch (RuntimeException exception) {
            throw conflict("验收条件快照无效");
        }
        Object required = snapshot.get("requiredArtifactTypes");
        if (!(required instanceof List<?> values) || values.isEmpty()) {
            return;
        }
        Set<String> actual = artifacts.stream().map(AgentArtifact::getArtifactType).collect(java.util.stream.Collectors.toSet());
        for (Object item : values) {
            if (!(item instanceof String type) || !actual.contains(type)) {
                throw conflict("缺少验收要求的制品类型");
            }
        }
    }

    /**
     * 校验{@code Rules}，并在条件不满足时终止处理。
     *
     * @param mode {@code mode}参数
     * @param result 结果参数
     * @param ruleResult rule结果参数
     */
    private void validateRules(String mode, String result, Map<String, Object> ruleResult) {
        if (!Set.of("rule", "combined").contains(mode) || !"passed".equals(result)) {
            return;
        }
        if (!Boolean.TRUE.equals(ruleResult.get("passed"))) {
            throw conflict("规则验收未通过，不能提交完成");
        }
    }

    /**
     * 处理当前任务Status并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    private String currentTaskStatus(Long taskId, Long runId) {
        AcceptanceTaskRow task = artifactMapper.selectAcceptanceTask(taskId, runId);
        return task == null ? "unknown" : task.getTaskStatus();
    }

    /**
     * 处理normalize结果并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private String normalizeResult(AcceptanceDecisionRequest input) {
        String value = input == null || input.result() == null ? "" : input.result().strip().toLowerCase(Locale.ROOT);
        if (!RESULTS.contains(value)) {
            throw new ServiceException("验收结果无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code normalizeKey}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private String normalizeKey(AcceptanceDecisionRequest input) {
        String value = input == null || input.idempotencyKey() == null ? "" : input.idempotencyKey().strip();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new ServiceException("验收幂等键格式无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理normalize制品Ids并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private List<Long> normalizeArtifactIds(List<Long> values) {
        if (values == null || values.isEmpty() || values.size() > 100
            || values.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("验收必须引用有效制品", HttpStatus.BAD_REQUEST);
        }
        List<Long> ids = values.stream().distinct().sorted().toList();
        if (ids.size() != values.size()) {
            throw new ServiceException("验收制品不能重复引用", HttpStatus.BAD_REQUEST);
        }
        return ids;
    }

    /**
     * 处理{@code normalizeComment}并返回对应结果。
     *
     * @param comment {@code comment}参数
     * @return 处理结果
     */
    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String value = comment.strip();
        if (value.length() > 4000 || value.indexOf('\0') >= 0) {
            throw new ServiceException("验收意见无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 判断{@code onicalMap}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Map<String, Object> canonicalMap(Map<String, Object> value, int depth) {
        if (value == null) {
            return Map.of();
        }
        if (depth > 16) {
            throw new ServiceException("规则验收结果嵌套过深", HttpStatus.BAD_REQUEST);
        }
        TreeMap<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank() || key.length() > 128 || isSecretKey(key)) {
                throw new ServiceException("规则验收结果包含敏感字段", HttpStatus.BAD_REQUEST);
            }
            result.put(key, canonicalValue(item, depth + 1));
        });
        String json = jsonMapper.writeValueAsString(result);
        if (json.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new ServiceException("规则验收结果超过64KB", HttpStatus.BAD_REQUEST);
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
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (depth > 16) {
            throw new ServiceException("规则验收结果嵌套过深", HttpStatus.BAD_REQUEST);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new ServiceException("规则验收结果键必须是文本", HttpStatus.BAD_REQUEST);
                }
                nested.put(text, item);
            });
            return canonicalMap(nested, depth + 1);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> canonicalValue(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new ServiceException("规则验收结果包含不支持的值", HttpStatus.BAD_REQUEST);
    }

    /**
     * 判断{@code SecretKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return Set.of("secret", "password", "token", "apikey", "authorization", "credential")
            .stream().anyMatch(normalized::contains);
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
