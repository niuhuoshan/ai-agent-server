package group.aitools.nhs.platform.task.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.domain.AgentTaskAccessRule;
import group.aitools.nhs.platform.task.domain.AgentTaskParticipant;
import group.aitools.nhs.platform.task.domain.AgentTaskResource;
import group.aitools.nhs.platform.task.domain.AgentTaskVersion;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.mapper.AgentTaskVersionMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.PutTaskAccessRuleRequest;
import group.aitools.nhs.platform.task.web.TaskAccessRuleView;
import group.aitools.nhs.platform.task.web.TaskDefinitionInput;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskParticipantView;
import group.aitools.nhs.platform.task.web.TaskResourceRequest;
import group.aitools.nhs.platform.task.web.TaskResourceView;
import group.aitools.nhs.platform.task.web.TaskVersionView;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.platform.task.web.UpdateTaskRequest;
import group.aitools.nhs.platform.workflow.service.WorkflowCatalogService;
import group.aitools.nhs.platform.workflow.service.WorkflowTaskBinding;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责任务相关的业务编排与领域规则处理。
 * Formal task definition, immutable versions, operation relations and ACLs. */
@Service
public class TaskApplicationService {

    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> EDITABLE_STATUSES = Set.of(
        "draft", "ready", "scheduled", "rework", "blocked", "cancelled"
    );
    private static final Set<String> PARTICIPANT_TYPES = Set.of(
        "assignee", "collaborator", "acceptor", "watcher"
    );
    private static final Set<String> ACL_SUBJECT_TYPES = Set.of(
        "user", "platform_role", "service_account"
    );
    private static final Set<String> ACL_ACTIONS = Set.of("view", "comment", "operate", "admin");
    private static final Set<String> ACL_EFFECTS = Set.of("allow", "deny");
    private static final Map<String, Set<String>> MANUAL_TRANSITIONS = Map.of(
        "draft", Set.of("ready", "cancelled", "archived"),
        "ready", Set.of("scheduled", "blocked", "cancelled", "archived"),
        "scheduled", Set.of("ready", "blocked", "cancelled", "archived"),
        "rework", Set.of("ready", "blocked", "cancelled", "archived"),
        "blocked", Set.of("ready", "cancelled", "archived"),
        "cancelled", Set.of("ready", "archived"),
        "completed", Set.of("archived")
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentTaskMapper taskMapper;
    private final AgentTaskVersionMapper versionMapper;
    private final TaskControlMapper controlMapper;
    private final AgentProjectMapper projectMapper;
    private final TaskQueryService taskQueryService;
    private final TaskVersionContentHasher contentHasher;
    private final JsonMapper jsonMapper;
    private final WorkflowCatalogService workflowCatalogService;

    /**
     * 创建 {@code TaskApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param taskMapper 任务Mapper参数
     * @param versionMapper 版本Mapper参数
     * @param controlMapper {@code controlMapper}参数
     * @param projectMapper 项目Mapper参数
     * @param taskQueryService 任务查询Service参数
     * @param contentHasher 待处理内容
     * @param jsonMapper {@code jsonMapper}参数
     * @param workflowCatalogService 工作流目录Service参数
     */
    @Autowired
    public TaskApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentTaskMapper taskMapper,
        AgentTaskVersionMapper versionMapper,
        TaskControlMapper controlMapper,
        AgentProjectMapper projectMapper,
        TaskQueryService taskQueryService,
        TaskVersionContentHasher contentHasher,
        JsonMapper jsonMapper,
        WorkflowCatalogService workflowCatalogService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.controlMapper = controlMapper;
        this.projectMapper = projectMapper;
        this.taskQueryService = taskQueryService;
        this.contentHasher = contentHasher;
        this.jsonMapper = jsonMapper;
        this.workflowCatalogService = workflowCatalogService;
    }

    /**
     * 创建 {@code TaskApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param taskMapper 任务Mapper参数
     * @param versionMapper 版本Mapper参数
     * @param controlMapper {@code controlMapper}参数
     * @param projectMapper 项目Mapper参数
     * @param taskQueryService 任务查询Service参数
     * @param contentHasher 待处理内容
     * @param jsonMapper {@code jsonMapper}参数
     */
    public TaskApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentTaskMapper taskMapper,
        AgentTaskVersionMapper versionMapper,
        TaskControlMapper controlMapper,
        AgentProjectMapper projectMapper,
        TaskQueryService taskQueryService,
        TaskVersionContentHasher contentHasher,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, authorizationEnforcer, idGenerator, taskMapper, versionMapper,
            controlMapper, projectMapper, taskQueryService, contentHasher, jsonMapper, null
        );
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskMutationResult create(CreateTaskRequest request) {
        return createAs(principalProvider.currentPrincipal(), request);
    }

    /**
 * 创建并保存{@code As}。
 * Creates a task as an already authenticated principal without consulting HTTP state. */
    @Transactional(rollbackFor = Exception.class)
    public TaskMutationResult createAs(CurrentPrincipal principal, CreateTaskRequest request) {
        return createInternal(
            Objects.requireNonNull(principal, "principal must not be null"),
            null,
            request.idempotencyKey(),
            request,
            null
        );
    }

    /**
 * 处理preview会话DraftHash并返回对应结果。
 * Validates an editable conversation draft without creating persistent task data. */
    public String previewConversationDraftHash(
        Long conversationId,
        ConvertConversationToTaskRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, "create", ResourceState.ACTIVE, Set.of()));
        String idempotencyKey = validIdempotencyKey(request.idempotencyKey());
        PreparedTask prepared = prepare(request);
        requireProjectAccess(principal, prepared.projectId());
        authorizeResources(principal, null, prepared.resources());
        return conversationDraftHash(principal.id(), conversationId, idempotencyKey, prepared);
    }

    /**
 * 创建并保存From会话。
 * Creates the same complete task aggregate as direct creation and binds its source conversation. */
    @Transactional(rollbackFor = Exception.class)
    public TaskMutationResult createFromConversation(
        Long conversationId,
        ConvertConversationToTaskRequest request
    ) {
        return createInternal(
            principalProvider.currentPrincipal(), conversationId, request.idempotencyKey(),
            request, request.draftHash()
        );
    }

    /**
     * 创建并保存{@code Internal}。
     *
     * @param principal 当前操作主体
     * @param sourceConversationId 资源标识
     * @param requestedIdempotencyKey {@code requestedIdempotencyKey}参数
     * @param request 请求参数
     * @param submittedDraftHash {@code submittedDraftHash}参数
     * @return 处理结果
     */
    private TaskMutationResult createInternal(
        CurrentPrincipal principal,
        Long sourceConversationId,
        String requestedIdempotencyKey,
        TaskDefinitionInput request,
        String submittedDraftHash
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", null, "create", "create", ResourceState.ACTIVE,
            principal.isHuman(), Set.of(), null
        ));
        String idempotencyKey = validIdempotencyKey(requestedIdempotencyKey);
        PreparedTask prepared = prepare(request);
        requireProjectAccess(principal, prepared.projectId());
        authorizeResources(principal, null, prepared.resources());
        if (sourceConversationId != null) {
            String expectedDraftHash = conversationDraftHash(
                principal.id(), sourceConversationId, idempotencyKey, prepared
            );
            if (submittedDraftHash == null || !submittedDraftHash.equals(expectedDraftHash)) {
                throw conflict("任务草稿已变化，请重新预览后确认");
            }
        }
        String ownerPrincipalType = principalType(principal);
        String taskKey = taskKey(ownerPrincipalType, principal.id(), sourceConversationId, idempotencyKey);
        String requestHash = creationHash(prepared);

        AgentTask existing = existingCreation(sourceConversationId, taskKey);
        if (existing != null) {
            return replay(existing, ownerPrincipalType, principal.id(), sourceConversationId, requestHash);
        }

        LocalDateTime now = LocalDateTime.now();
        AgentTask task = buildTask(
            principal.id(), ownerPrincipalType, taskKey, sourceConversationId, prepared, requestHash, now
        );
        if (taskMapper.insertIfAbsent(task) != 1) {
            AgentTask raced = existingCreation(sourceConversationId, taskKey);
            if (raced == null) {
                throw conflict("任务幂等写入冲突");
            }
            return replay(raced, ownerPrincipalType, principal.id(), sourceConversationId, requestHash);
        }

        if (principal.isHuman()) {
            insertOwner(task.getId(), principal.id(), now);
        }
        replaceCurrentResources(task.getId(), principal.id(), prepared.resources(), now);
        AgentTaskVersion version = buildVersion(task.getId(), 1, principal.id(), prepared, now);
        if (versionMapper.insertSnapshot(version) != 1
            || taskMapper.bindInitialVersion(task.getId(), version.getId(), principal.id()) != 1) {
            throw conflict("任务初始版本绑定失败");
        }
        task.setCurrentVersionId(version.getId());
        if ("restricted".equals(task.getVisibility())) {
            ensureOwnerAccess(task, principal.id(), now);
        }
        return new TaskMutationResult(TaskView.from(task, jsonMapper), version.getId(), false);
    }

    /**
     * 更新{@code update}。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskMutationResult update(Long taskId, UpdateTaskRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        if (!EDITABLE_STATUSES.contains(task.getStatus())) {
            throw conflict("当前任务状态不能修改定义：" + task.getStatus());
        }
        if (task.getCurrentVersionId() == null) {
            throw conflict("任务没有可更新的当前版本");
        }

        PreparedTask prepared = prepare(request);
        requireProjectAccess(principal, prepared.projectId());
        authorizeResources(principal, taskId, prepared.resources());
        int versionNo = versionMapper.selectNextVersionNo(taskId);
        LocalDateTime now = LocalDateTime.now();
        AgentTaskVersion version = buildVersion(taskId, versionNo, principal.id(), prepared, now);
        if (versionMapper.insertSnapshot(version) != 1) {
            throw conflict("任务新版本创建失败");
        }

        Long expectedVersionId = task.getCurrentVersionId();
        apply(task, prepared);
        task.setCurrentVersionId(version.getId());
        task.setUpdateBy(principal.id());
        task.setUpdateTime(now);
        if (taskMapper.updateDefinitionAndVersion(task, expectedVersionId) != 1) {
            throw conflict("任务定义已被并发修改或状态已变化");
        }
        replaceCurrentResources(taskId, principal.id(), prepared.resources(), now);
        if ("restricted".equals(task.getVisibility())) {
            ensureOwnerAccess(task, principal.id(), now);
        }
        return new TaskMutationResult(TaskView.from(task, jsonMapper), version.getId(), false);
    }

    /**
     * 更新{@code Status}。
     *
     * @param taskId 资源标识
     * @param requestedStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskView updateStatus(Long taskId, String requestedStatus) {
        return updateStatusAs(principalProvider.currentPrincipal(), taskId, requestedStatus);
    }

    /**
 * 更新{@code StatusAs}。
 * Applies a task transition using an already frozen and authenticated principal. */
    @Transactional(rollbackFor = Exception.class)
    public TaskView updateStatusAs(
        CurrentPrincipal principal,
        Long taskId,
        String requestedStatus
    ) {
        CurrentPrincipal requiredPrincipal = Objects.requireNonNull(
            principal, "principal must not be null"
        );
        String target = normalize(requestedStatus);
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        if (target.equals(task.getStatus())) {
            requireViewOperation(requiredPrincipal, task);
            return TaskView.from(task, jsonMapper);
        }
        requireAdmin(requiredPrincipal, task);
        if (!MANUAL_TRANSITIONS.getOrDefault(task.getStatus(), Set.of()).contains(target)) {
            throw conflict("不允许的任务状态转换：" + task.getStatus() + " -> " + target);
        }
        if (taskMapper.updateStatus(
            taskId, task.getStatus(), target, requiredPrincipal.id(), LocalDateTime.now()
        ) != 1) {
            throw conflict("任务状态已被并发修改");
        }
        task.setStatus(target);
        return TaskView.from(task, jsonMapper);
    }

    /**
 * 判断{@code celRecurringAs}是否满足要求。
 * Cancels only an L3 recurring definition, including while its latest run is finishing. */
    @Transactional(rollbackFor = Exception.class)
    public TaskView cancelRecurringAs(CurrentPrincipal principal, Long taskId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal requiredPrincipal = Objects.requireNonNull(
            principal, "principal must not be null"
        );
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(requiredPrincipal, task);
        if (!"L3_recurring_task".equals(task.getLifecycleLevel())) {
            throw conflict("只有周期任务可以通过该工具取消");
        }
        if ("cancelled".equals(task.getStatus())) {
            return TaskView.from(task, jsonMapper);
        }
        if ("archived".equals(task.getStatus())) {
            throw conflict("已归档任务不能取消");
        }
        if (taskMapper.updateStatus(
            taskId, task.getStatus(), "cancelled", requiredPrincipal.id(), LocalDateTime.now()
        ) != 1) {
            throw conflict("周期任务状态已被并发修改");
        }
        task.setStatus("cancelled");
        return TaskView.from(task, jsonMapper);
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<TaskVersionView> versions(Long taskId, int limit) {
        taskQueryService.get(taskId);
        return versionMapper.selectVersions(taskId, limit).stream()
            .map(version -> TaskVersionView.from(version, jsonMapper)).toList();
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param taskId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    public TaskVersionView version(Long taskId, Long versionId) {
        taskQueryService.get(taskId);
        AgentTaskVersion version = versionMapper.selectVersion(taskId, versionId);
        if (version == null) {
            throw new ServiceException("任务版本不存在", HttpStatus.NOT_FOUND);
        }
        return TaskVersionView.from(version, jsonMapper);
    }

    /**
     * 处理{@code participants}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<TaskParticipantView> participants(Long taskId, int limit) {
        taskQueryService.get(taskId);
        return controlMapper.selectParticipants(taskId, limit).stream()
            .map(TaskParticipantView::from).toList();
    }

    /**
     * 处理{@code putParticipant}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param requestedType 业务类型
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskParticipantView putParticipant(Long taskId, Long userId, String requestedType) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String type = normalizeParticipantType(requestedType);
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        if (task.getOwnerId().equals(userId)) {
            throw conflict("任务负责人不能通过参与人接口修改");
        }
        AgentTaskParticipant existing = controlMapper.selectParticipant(taskId, userId, type);
        if (existing != null) {
            return TaskParticipantView.from(existing);
        }
        AgentTaskParticipant participant = participant(
            taskId, userId, type, "manual", LocalDateTime.now()
        );
        if (controlMapper.insertParticipant(participant) != 1) {
            AgentTaskParticipant raced = controlMapper.selectParticipant(taskId, userId, type);
            if (raced == null) {
                throw conflict("任务参与人写入冲突");
            }
            return TaskParticipantView.from(raced);
        }
        return TaskParticipantView.from(participant);
    }

    /**
     * 删除{@code Participant}。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param requestedType 业务类型
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeParticipant(Long taskId, Long userId, String requestedType) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String type = normalizeParticipantType(requestedType);
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        if (task.getOwnerId().equals(userId)) {
            throw conflict("任务负责人不能被移除");
        }
        if (controlMapper.selectParticipant(taskId, userId, type) == null) {
            return;
        }
        if (controlMapper.removeParticipant(taskId, userId, type) != 1) {
            throw conflict("任务参与人状态已被并发修改");
        }
    }

    /**
     * 处理{@code resources}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 符合条件的数据集合
     */
    public List<TaskResourceView> resources(Long taskId) {
        taskQueryService.get(taskId);
        return controlMapper.selectResources(taskId).stream()
            .map(resource -> TaskResourceView.from(resource, jsonMapper)).toList();
    }

    /**
     * 处理{@code accessRules}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<TaskAccessRuleView> accessRules(Long taskId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        return controlMapper.selectAccessRules(taskId, limit).stream()
            .map(TaskAccessRuleView::from).toList();
    }

    /**
     * 处理{@code putAccessRule}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAccessRuleView putAccessRule(Long taskId, PutTaskAccessRuleRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        PreparedAccessRule prepared = prepareAccessRule(task, request);
        AgentTaskAccessRule existing = controlMapper.selectActiveAccessRule(
            taskId, prepared.subjectType(), prepared.subjectId(), prepared.subjectKey(), prepared.action()
        );
        if (existing != null
            && prepared.effect().equals(existing.getEffect())
            && Objects.equals(prepared.expiresAt(), existing.getExpiresAt())) {
            return TaskAccessRuleView.from(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        if (existing != null && controlMapper.revokeAccessRule(taskId, existing.getId(), now) != 1) {
            throw conflict("任务ACL已被并发修改");
        }
        AgentTaskAccessRule rule = new AgentTaskAccessRule();
        rule.setId(idGenerator.nextId());
        rule.setTaskId(taskId);
        rule.setSubjectType(prepared.subjectType());
        rule.setSubjectId(prepared.subjectId());
        rule.setSubjectKey(prepared.subjectKey());
        rule.setAction(prepared.action());
        rule.setEffect(prepared.effect());
        rule.setExpiresAt(prepared.expiresAt());
        rule.setCreatedBy(principal.id());
        rule.setCreatedAt(now);
        if (controlMapper.insertAccessRule(rule) != 1) {
            throw conflict("任务ACL写入冲突");
        }
        return TaskAccessRuleView.from(rule);
    }

    /**
     * 删除{@code AccessRule}。
     *
     * @param taskId 资源标识
     * @param ruleId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeAccessRule(Long taskId, Long ruleId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        lockTask(taskId);
        AgentTask task = requireTask(taskId);
        requireAdmin(principal, task);
        AgentTaskAccessRule rule = controlMapper.selectAccessRule(taskId, ruleId);
        if (rule == null) {
            return;
        }
        if (isOwnerAccessRule(task, rule)) {
            throw conflict("任务负责人基础ACL不能被移除");
        }
        if (controlMapper.revokeAccessRule(taskId, ruleId, LocalDateTime.now()) != 1) {
            throw conflict("任务ACL已被并发修改");
        }
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private PreparedTask prepare(TaskDefinitionInput request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String title = requiredText(request.title(), "任务标题", 255);
        String objective = requiredText(request.objective(), "任务目标", 12000);
        String background = optionalText(request.background(), "任务背景", 12000);
        WorkflowTaskBinding workflow;
        if (workflowCatalogService == null) {
            if ("multi_agent_template".equals(request.orchestrationMode())
                || request.workflowVersionId() != null
                || !request.workflowAgentVersions().isEmpty()) {
                throw conflict("固定工作流目录不可用");
            }
            workflow = WorkflowTaskBinding.none();
        } else {
            workflow = workflowCatalogService.validateTaskBinding(
                request.orchestrationMode(), request.workflowVersionId(), request.agentVersionId(),
                request.workflowAgentVersions()
            );
        }
        LinkedHashSet<Long> agentVersionIds = new LinkedHashSet<>();
        agentVersionIds.add(request.agentVersionId());
        agentVersionIds.addAll(workflow.agentVersions().values());
        List<PreparedResource> resources = prepareResources(agentVersionIds, request.resources());
        String contextJson = documentJson(request.contextSnapshot(), "任务上下文");
        String resourceJson = resourceJson(
            request.agentVersionId(), request.workflowVersionId(), workflow.agentVersions(), resources
        );
        String acceptanceJson = documentJson(request.acceptanceSnapshot(), "验收条件");
        String inputJson = documentJson(request.inputSnapshot(), "任务输入");
        String budgetJson = documentJson(request.budget(), "任务预算");
        String externalRefsJson = documentJson(request.externalRefs(), "外部引用");
        String tagsJson = jsonMapper.writeValueAsString(normalizeTags(request.tags()));
        return new PreparedTask(
            title, objective, background, request.projectId(), request.agentVersionId(),
            request.workflowVersionId(), workflow.agentVersions(), request.visibility(), request.category(),
            request.orchestrationMode(), request.lifecycleLevel(), request.riskLevel(),
            request.acceptanceMode(), request.importance(), request.urgency(), request.startAt(),
            contextJson, resourceJson, acceptanceJson, inputJson, budgetJson,
            externalRefsJson, tagsJson, resources
        );
    }

    /**
     * 构建任务。
     *
     * @param ownerId 资源标识
     * @param ownerPrincipalType 业务类型
     * @param taskKey 任务Key参数
     * @param sourceConversationId 资源标识
     * @param prepared {@code prepared}参数
     * @param requestHash {@code requestHash}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentTask buildTask(
        Long ownerId,
        String ownerPrincipalType,
        String taskKey,
        Long sourceConversationId,
        PreparedTask prepared,
        String requestHash,
        LocalDateTime now
    ) {
        AgentTask task = new AgentTask();
        task.setId(idGenerator.nextId());
        task.setTaskKey(taskKey);
        task.setSourceConversationId(sourceConversationId);
        task.setOwnerId(ownerId);
        task.setOwnerPrincipalType(ownerPrincipalType);
        task.setStatus("ready");
        task.setCreateBy(ownerId);
        task.setCreateTime(now);
        task.setDelFlag("0");
        task.setExtraJson(jsonMapper.writeValueAsString(Map.of("creationRequestHash", requestHash)));
        apply(task, prepared);
        return task;
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param task 任务参数
     * @param prepared {@code prepared}参数
     */
    private void apply(AgentTask task, PreparedTask prepared) {
        task.setProjectId(prepared.projectId());
        task.setTitle(prepared.title());
        task.setObjective(prepared.objective());
        task.setBackground(prepared.background());
        task.setContextSnapshotJson(prepared.contextJson());
        task.setVisibility(prepared.visibility());
        task.setCategory(prepared.category());
        task.setOrchestrationMode(prepared.orchestrationMode());
        task.setLifecycleLevel(prepared.lifecycleLevel());
        task.setRiskLevel(prepared.riskLevel());
        task.setImportance(prepared.importance());
        task.setUrgency(prepared.urgency());
        task.setQueuePriority(prepared.importance() * 2 + prepared.urgency());
        task.setStartAt(prepared.startAt());
        task.setAcceptanceMode(prepared.acceptanceMode());
        task.setAcceptanceConfigJson(prepared.acceptanceJson());
        task.setBudgetJson(prepared.budgetJson());
        task.setExternalRefsJson(prepared.externalRefsJson());
        task.setTagsJson(prepared.tagsJson());
    }

    /**
     * 构建版本。
     *
     * @param taskId 资源标识
     * @param versionNo 版本No参数
     * @param userId 资源标识
     * @param prepared {@code prepared}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentTaskVersion buildVersion(
        Long taskId,
        int versionNo,
        Long userId,
        PreparedTask prepared,
        LocalDateTime now
    ) {
        AgentTaskVersion version = new AgentTaskVersion();
        version.setId(idGenerator.nextId());
        version.setTaskId(taskId);
        version.setVersionNo(versionNo);
        version.setTitle(prepared.title());
        version.setObjective(prepared.objective());
        version.setAgentVersionId(prepared.agentVersionId());
        version.setWorkflowVersionId(prepared.workflowVersionId());
        version.setContextSnapshotJson(prepared.contextJson());
        version.setResourceSnapshotJson(prepared.resourceJson());
        version.setAcceptanceSnapshotJson(prepared.acceptanceJson());
        version.setInputSnapshotJson(prepared.inputJson());
        version.setContentHash(contentHasher.hash(
            prepared.title(), prepared.objective(), prepared.contextJson(), prepared.resourceJson(),
            prepared.acceptanceJson(), prepared.inputJson()
        ));
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        return version;
    }

    /**
     * 创建并保存{@code Owner}。
     *
     * @param taskId 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     */
    private void insertOwner(Long taskId, Long ownerId, LocalDateTime now) {
        AgentTaskParticipant owner = participant(taskId, ownerId, "owner", "system", now);
        if (controlMapper.insertParticipant(owner) != 1) {
            throw conflict("任务负责人关系创建失败");
        }
    }

    /**
     * 处理{@code participant}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param type 业务类型
     * @param source 数据源参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentTaskParticipant participant(
        Long taskId,
        Long userId,
        String type,
        String source,
        LocalDateTime now
    ) {
        AgentTaskParticipant participant = new AgentTaskParticipant();
        participant.setId(idGenerator.nextId());
        participant.setTaskId(taskId);
        participant.setUserId(userId);
        participant.setParticipantType(type);
        participant.setSource(source);
        participant.setStatus("active");
        participant.setCreatedAt(now);
        return participant;
    }

    /**
     * 处理replace当前Resources相关逻辑。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param resources {@code resources}参数
     * @param now {@code now}参数
     */
    private void replaceCurrentResources(
        Long taskId,
        Long userId,
        List<PreparedResource> resources,
        LocalDateTime now
    ) {
        controlMapper.deleteResources(taskId);
        for (PreparedResource prepared : resources) {
            AgentTaskResource resource = new AgentTaskResource();
            resource.setId(idGenerator.nextId());
            resource.setTaskId(taskId);
            resource.setResourceType(prepared.resourceType());
            resource.setResourceId(prepared.resourceId());
            resource.setPermission(prepared.permission());
            resource.setRequired(prepared.required());
            resource.setGrantSource(prepared.grantSource());
            resource.setGrantSnapshotJson(prepared.grantSnapshotJson());
            resource.setCreatedBy(userId);
            resource.setCreatedAt(now);
            if (controlMapper.insertResource(resource) != 1) {
                throw conflict("任务资源绑定失败");
            }
        }
    }

    /**
     * 处理{@code prepareResources}并返回对应结果。
     *
     * @param agentVersionIds 资源标识集合
     * @param requested {@code requested}参数
     * @return 符合条件的数据集合
     */
    private List<PreparedResource> prepareResources(
        Set<Long> agentVersionIds,
        List<TaskResourceRequest> requested
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (agentVersionIds == null || agentVersionIds.isEmpty()
            || agentVersionIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw badRequest("任务必须绑定Agent版本");
        }
        ArrayList<PreparedResource> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<Long> includedAgents = new LinkedHashSet<>();
        for (TaskResourceRequest resource : requested == null ? List.<TaskResourceRequest>of() : requested) {
            if (resource == null) {
                throw badRequest("任务资源不能为空");
            }
            if ("agent_version".equals(resource.resourceType())) {
                if (!agentVersionIds.contains(resource.resourceId()) || !"use".equals(resource.permission())) {
                    throw badRequest("Agent版本资源必须属于任务工作流并使用use权限");
                }
                includedAgents.add(resource.resourceId());
            }
            String key = resource.resourceType() + ':' + resource.resourceId() + ':' + resource.permission();
            if (!seen.add(key)) {
                throw badRequest("任务资源不能重复");
            }
            result.add(new PreparedResource(
                resource.resourceType(), resource.resourceId(), resource.permission(), resource.required(),
                resource.grantSource(), documentJson(resource.grantSnapshot(), "资源授权快照")
            ));
        }
        for (Long agentVersionId : agentVersionIds) {
            if (!includedAgents.contains(agentVersionId)) {
                result.add(new PreparedResource(
                    "agent_version", agentVersionId, "use", true, "user", "{}"
                ));
            }
        }
        requireSqlToolDatasets(result);
        result.sort(java.util.Comparator.comparing(PreparedResource::resourceType)
            .thenComparing(PreparedResource::resourceId)
            .thenComparing(PreparedResource::permission));
        return List.copyOf(result);
    }

    /**
     * 校验Sql工具Datasets，并在条件不满足时终止处理。
     *
     * @param resources {@code resources}参数
     */
    private void requireSqlToolDatasets(List<PreparedResource> resources) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Set<String> datasetGrants = resources.stream()
            .filter(resource -> "dataset".equals(resource.resourceType()))
            .filter(resource -> Set.of("query", "admin").contains(resource.permission()))
            .map(resource -> String.valueOf(resource.resourceId()))
            .collect(java.util.stream.Collectors.toSet());
        for (PreparedResource resource : resources) {
            if (!"tool".equals(resource.resourceType())) {
                continue;
            }
            String rawDatasetId = controlMapper.selectSqlToolDatasetId(resource.resourceId());
            if (rawDatasetId == null) {
                continue;
            }
            String datasetId = rawDatasetId.strip();
            try {
                if (Long.parseLong(datasetId) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                throw conflict("SQL 工具的数据集配置已损坏：" + resource.resourceId());
            }
            if (!datasetGrants.contains(datasetId)) {
                throw badRequest(
                    "任务使用 SQL 工具时必须同时授予其数据集 query 权限：" + datasetId
                );
            }
        }
    }

    /**
     * 处理资源Json并返回对应结果。
     *
     * @param agentVersionId 资源标识
     * @param workflowVersionId 资源标识
     * @param workflowAgentVersions 工作流智能体Versions参数
     * @param resources {@code resources}参数
     * @return 处理结果
     */
    private String resourceJson(
        Long agentVersionId,
        Long workflowVersionId,
        Map<String, Long> workflowAgentVersions,
        List<PreparedResource> resources
    ) {
        List<Map<String, Object>> entries = resources.stream().map(resource -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resourceType", resource.resourceType());
            entry.put("resourceId", resource.resourceId());
            entry.put("permission", resource.permission());
            entry.put("required", resource.required());
            entry.put("grantSource", resource.grantSource());
            entry.put("grantSnapshot", jsonMapper.readValue(resource.grantSnapshotJson(), MAP_TYPE));
            return Map.copyOf(entry);
        }).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("agentVersionId", agentVersionId);
        if (workflowVersionId != null) {
            snapshot.put("workflowVersionId", workflowVersionId);
            snapshot.put("workflowAgentVersions", new TreeMap<>(workflowAgentVersions));
        }
        snapshot.put("resources", entries);
        return limitedJson(snapshot, "任务资源快照");
    }

    /**
     * 处理{@code authorizeResources}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param resources {@code resources}参数
     */
    private void authorizeResources(
        CurrentPrincipal principal,
        Long taskId,
        List<PreparedResource> resources
    ) {
        for (PreparedResource resource : resources) {
            authorizationEnforcer.requireAllowed(principal, new PermissionContext(
                resource.resourceType(), resource.resourceId(), null,
                resourceAction(resource), ResourceState.ACTIVE, principal.isHuman(), Set.of(), taskId
            ));
        }
    }

    /**
     * 处理资源Action并返回对应结果。
     *
     * @param resource 资源参数
     * @return 处理结果
     */
    private String resourceAction(PreparedResource resource) {
        return switch (resource.resourceType()) {
            case "tool" -> "invoke";
            case "agent_version", "skill", "connector" -> "use";
            case "knowledge_base", "data_source", "dataset", "artifact" -> resource.permission();
            default -> throw badRequest("任务资源类型无效");
        };
    }

    /**
     * 校验项目Access，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param projectId 资源标识
     */
    private void requireProjectAccess(CurrentPrincipal principal, Long projectId) {
        if (projectId == null) {
            return;
        }
        AgentProject project = projectMapper.selectProject(projectId);
        if (project == null) {
            throw new ServiceException("项目不存在", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(project.getStatus())) {
            throw conflict("只有活动项目可以绑定任务");
        }
        Set<BusinessRelation> relations = projectRelations(project, principal);
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "project", projectId, project.getProjectKey(), "view", ResourceState.ACTIVE,
            principal.isHuman(), relations, null
        ));
    }

    /**
     * 处理项目Relations并返回对应结果。
     *
     * @param project 项目参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> projectRelations(AgentProject project, CurrentPrincipal principal) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        if (!principal.isHuman()) {
            return Set.of();
        }
        LinkedHashSet<BusinessRelation> relations = new LinkedHashSet<>();
        if (principal.id().equals(project.getOwnerId())) {
            relations.add(BusinessRelation.OWNER);
        }
        AgentProjectMember member = projectMapper.selectActiveMember(project.getId(), principal.id());
        if (member != null) {
            switch (member.getMemberRole()) {
                case "owner" -> relations.add(BusinessRelation.OWNER);
                case "manager" -> relations.add(BusinessRelation.PROJECT_ADMIN);
                case "member" -> relations.add(BusinessRelation.COLLABORATOR);
                case "viewer" -> relations.add(BusinessRelation.WATCHER);
                default -> throw conflict("项目成员角色数据无效");
            }
        }
        return Set.copyOf(relations);
    }

    /**
     * 校验{@code Admin}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param task 任务参数
     */
    private void requireAdmin(CurrentPrincipal principal, AgentTask task) {
        authorizationEnforcer.requireAllowed(principal, context(
            task.getId(), "admin", state(task), relations(task.getId(), principal)
        ));
    }

    /**
     * 校验View操作，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param task 任务参数
     */
    private void requireViewOperation(CurrentPrincipal principal, AgentTask task) {
        authorizationEnforcer.requireAllowed(principal, context(
            task.getId(), "view", state(task), relations(task.getId(), principal)
        ));
    }

    /**
     * 处理{@code relations}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> relations(Long taskId, CurrentPrincipal principal) {
        LinkedHashSet<BusinessRelation> result = new LinkedHashSet<>();
        for (String relation : controlMapper.selectRelations(
            taskId, principal.id(), principalType(principal)
        )) {
            try {
                result.add(BusinessRelation.valueOf(relation));
            } catch (IllegalArgumentException exception) {
                throw conflict("任务参与关系数据无效");
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param taskId 资源标识
     * @param action {@code action}参数
     * @param state {@code state}参数
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private PermissionContext context(
        Long taskId,
        String action,
        ResourceState state,
        Set<BusinessRelation> relations
    ) {
        return new PermissionContext("task", taskId, null, action, state, true, relations, taskId);
    }

    /**
     * 处理{@code state}并返回对应结果。
     *
     * @param task 任务参数
     * @return 处理结果
     */
    private ResourceState state(AgentTask task) {
        return "archived".equals(task.getStatus()) ? ResourceState.INACTIVE : ResourceState.ACTIVE;
    }

    /**
     * 处理lock任务相关逻辑。
     *
     * @param taskId 资源标识
     */
    private void lockTask(Long taskId) {
        if (controlMapper.lockTask(taskId) == null) {
            throw new ServiceException("任务不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 校验任务，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    private AgentTask requireTask(Long taskId) {
        AgentTask task = taskMapper.selectPlatformTaskById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    /**
     * 处理{@code replay}并返回对应结果。
     *
     * @param task 任务参数
     * @param ownerPrincipalType 业务类型
     * @param ownerId 资源标识
     * @param sourceConversationId 资源标识
     * @param requestHash {@code requestHash}参数
     * @return 处理结果
     */
    private TaskMutationResult replay(
        AgentTask task,
        String ownerPrincipalType,
        Long ownerId,
        Long sourceConversationId,
        String requestHash
    ) {
        Map<String, Object> extra = task.getExtraJson() == null
            ? Map.of() : jsonMapper.readValue(task.getExtraJson(), MAP_TYPE);
        if (!ownerId.equals(task.getOwnerId())
            || !ownerPrincipalType.equals(task.getOwnerPrincipalType())
            || !Objects.equals(sourceConversationId, task.getSourceConversationId())
            || !requestHash.equals(extra.get("creationRequestHash"))) {
            throw conflict("同一任务幂等键不能用于不同请求");
        }
        if (task.getCurrentVersionId() == null) {
            throw conflict("幂等任务没有完整的初始版本");
        }
        return new TaskMutationResult(
            TaskView.from(task, jsonMapper), task.getCurrentVersionId(), true
        );
    }

    /**
     * 处理{@code creationHash}并返回对应结果。
     *
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private String creationHash(PreparedTask prepared) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", prepared.title());
        value.put("objective", prepared.objective());
        value.put("background", prepared.background());
        value.put("projectId", prepared.projectId());
        value.put("agentVersionId", prepared.agentVersionId());
        value.put("workflowVersionId", prepared.workflowVersionId());
        value.put("workflowAgentVersions", new TreeMap<>(prepared.workflowAgentVersions()));
        value.put("visibility", prepared.visibility());
        value.put("category", prepared.category());
        value.put("orchestrationMode", prepared.orchestrationMode());
        value.put("lifecycleLevel", prepared.lifecycleLevel());
        value.put("riskLevel", prepared.riskLevel());
        value.put("acceptanceMode", prepared.acceptanceMode());
        value.put("importance", prepared.importance());
        value.put("urgency", prepared.urgency());
        value.put("startAt", prepared.startAt());
        value.put("context", jsonMapper.readValue(prepared.contextJson(), Object.class));
        value.put("resources", jsonMapper.readValue(prepared.resourceJson(), Object.class));
        value.put("acceptance", jsonMapper.readValue(prepared.acceptanceJson(), Object.class));
        value.put("input", jsonMapper.readValue(prepared.inputJson(), Object.class));
        value.put("budget", jsonMapper.readValue(prepared.budgetJson(), Object.class));
        value.put("externalRefs", jsonMapper.readValue(prepared.externalRefsJson(), Object.class));
        value.put("tags", jsonMapper.readValue(prepared.tagsJson(), Object.class));
        return ContentHashing.sha256(jsonMapper.writeValueAsString(value));
    }

    /**
     * 处理会话DraftHash并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private String conversationDraftHash(
        Long ownerId,
        Long conversationId,
        String idempotencyKey,
        PreparedTask prepared
    ) {
        return ContentHashing.sha256(
            ownerId + ":" + conversationId + ":" + idempotencyKey + ":" + creationHash(prepared)
        );
    }

    /**
     * 处理任务Key并返回对应结果。
     *
     * @param ownerPrincipalType 业务类型
     * @param ownerId 资源标识
     * @param sourceConversationId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    private String taskKey(
        String ownerPrincipalType,
        Long ownerId,
        Long sourceConversationId,
        String idempotencyKey
    ) {
        String source = sourceConversationId == null ? "direct" : sourceConversationId.toString();
        return "T-" + ContentHashing.sha256(
            ownerPrincipalType + ":" + ownerId + ":" + source + ":" + idempotencyKey
        ).substring(0, 32);
    }

    /**
     * 处理操作主体Type并返回对应结果。
     *
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private String principalType(CurrentPrincipal principal) {
        return principal.type().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code existingCreation}并返回对应结果。
     *
     * @param sourceConversationId 资源标识
     * @param taskKey 任务Key参数
     * @return 处理结果
     */
    private AgentTask existingCreation(Long sourceConversationId, String taskKey) {
        if (sourceConversationId != null) {
            AgentTask task = taskMapper.selectBySourceConversationId(sourceConversationId);
            if (task != null) {
                return task;
            }
        }
        return taskMapper.selectByTaskKey(taskKey);
    }

    /**
     * 处理{@code validIdempotencyKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String validIdempotencyKey(String value) {
        String idempotencyKey = requiredText(value, "任务幂等键", 128);
        if (!idempotencyKey.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("任务幂等键无效");
        }
        return idempotencyKey;
    }

    /**
     * 处理{@code prepareAccessRule}并返回对应结果。
     *
     * @param task 任务参数
     * @param request 请求参数
     * @return 处理结果
     */
    private PreparedAccessRule prepareAccessRule(AgentTask task, PutTaskAccessRuleRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String subjectType = normalize(request.subjectType());
        String action = normalize(request.action());
        String effect = normalize(request.effect());
        if (!ACL_SUBJECT_TYPES.contains(subjectType)
            || !ACL_ACTIONS.contains(action) || !ACL_EFFECTS.contains(effect)) {
            throw badRequest("任务ACL无效");
        }
        Long subjectId;
        String subjectKey;
        if ("platform_role".equals(subjectType)) {
            subjectId = null;
            subjectKey = requiredText(request.subjectKey(), "平台角色", 128);
            if (!Set.of("member", "approval_user", "platform_admin").contains(subjectKey)) {
                throw badRequest("平台角色无效");
            }
            if (request.subjectId() != null) {
                throw badRequest("平台角色ACL不能包含subjectId");
            }
        } else {
            if (request.subjectId() == null || request.subjectId() <= 0 || request.subjectKey() != null) {
                throw badRequest("用户或服务账号ACL必须只包含subjectId");
            }
            subjectId = request.subjectId();
            subjectKey = null;
        }
        if (ownerSubjectType(task).equals(subjectType) && task.getOwnerId().equals(subjectId)
            && "deny".equals(effect) && Set.of("view", "admin").contains(action)) {
            throw conflict("不能拒绝任务负责人的基础访问权限");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(LocalDateTime.now())) {
            throw badRequest("任务ACL过期时间必须在未来");
        }
        return new PreparedAccessRule(
            subjectType, subjectId, subjectKey, action, effect, request.expiresAt()
        );
    }

    /**
     * 校验{@code OwnerAccess}，并在条件不满足时终止处理。
     *
     * @param task 任务参数
     * @param createdBy {@code createdBy}参数
     * @param now {@code now}参数
     */
    private void ensureOwnerAccess(AgentTask task, Long createdBy, LocalDateTime now) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String subjectType = ownerSubjectType(task);
        for (String action : List.of("view", "admin")) {
            AgentTaskAccessRule existing = controlMapper.selectActiveAccessRule(
                task.getId(), subjectType, task.getOwnerId(), null, action
            );
            if (existing != null) {
                if (!"allow".equals(existing.getEffect())) {
                    throw conflict("任务负责人ACL与基础访问权限冲突");
                }
                continue;
            }
            AgentTaskAccessRule rule = new AgentTaskAccessRule();
            rule.setId(idGenerator.nextId());
            rule.setTaskId(task.getId());
            rule.setSubjectType(subjectType);
            rule.setSubjectId(task.getOwnerId());
            rule.setAction(action);
            rule.setEffect("allow");
            rule.setCreatedBy(createdBy);
            rule.setCreatedAt(now);
            if (controlMapper.insertAccessRule(rule) != 1) {
                throw conflict("任务负责人ACL创建失败");
            }
        }
    }

    /**
     * 判断{@code OwnerAccessRule}是否满足要求。
     *
     * @param task 任务参数
     * @param rule {@code rule}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isOwnerAccessRule(AgentTask task, AgentTaskAccessRule rule) {
        return ownerSubjectType(task).equals(rule.getSubjectType())
            && task.getOwnerId().equals(rule.getSubjectId())
            && Set.of("view", "admin").contains(rule.getAction());
    }

    /**
     * 处理{@code ownerSubjectType}并返回对应结果。
     *
     * @param task 任务参数
     * @return 处理结果
     */
    private String ownerSubjectType(AgentTask task) {
        return "service_account".equals(task.getOwnerPrincipalType()) ? "service_account" : "user";
    }

    /**
     * 处理文档Json并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String documentJson(Map<String, Object> value, String label) {
        Map<String, Object> normalized = value == null ? Map.of() : canonicalMap(value, 0, label);
        return limitedJson(normalized, label);
    }

    /**
     * 处理{@code limitedJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String limitedJson(Object value, String label) {
        String json = jsonMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw badRequest(label + "超过64KB");
        }
        return json;
    }

    /**
     * 判断{@code onicalMap}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> canonicalMap(Map<String, Object> value, int depth, String label) {
        if (depth > 16) {
            throw badRequest(label + "嵌套过深");
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank() || key.length() > 128 || isSecretKey(key)) {
                throw badRequest(label + "包含敏感或无效字段");
            }
            sorted.put(key, canonicalValue(item, depth + 1, label));
        });
        return new LinkedHashMap<>(sorted);
    }

    /**
     * 判断{@code onicalValue}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Object canonicalValue(Object value, int depth, String label) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (depth > 16) {
            throw badRequest(label + "嵌套过深");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw badRequest(label + "字段必须为文本");
                }
                nested.put(text, item);
            });
            return canonicalMap(nested, depth + 1, label);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> canonicalValue(item, depth + 1, label)).toList();
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw badRequest(label + "包含不支持的值");
    }

    /**
     * 判断{@code SecretKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (Set.of("secret", "password", "apikey", "authorization", "credential", "privatekey")
            .stream().anyMatch(normalized::contains)) {
            return true;
        }
        return Set.of(
            "token", "accesstoken", "refreshtoken", "authtoken", "bearertoken",
            "idtoken", "sessiontoken", "githubtoken", "gitlabtoken"
        ).contains(normalized);
    }

    /**
     * 处理{@code normalizeTags}并返回对应结果。
     *
     * @param tags {@code tags}参数
     * @return 符合条件的数据集合
     */
    private List<String> normalizeTags(List<String> tags) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (tags == null) {
            return List.of();
        }
        if (tags.size() > 32) {
            throw badRequest("任务标签不能超过32个");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = requiredText(tag, "任务标签", 64);
            if (!result.add(normalized)) {
                throw badRequest("任务标签不能重复");
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code normalizeParticipantType}并返回对应结果。
     *
     * @param type 业务类型
     * @return 处理结果
     */
    private String normalizeParticipantType(String type) {
        String value = normalize(type);
        if (!PARTICIPANT_TYPES.contains(value)) {
            throw badRequest("任务参与人类型无效");
        }
        return value;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String requiredText(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String optionalText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "无效");
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
     * 封装Prepared任务相关的不可变数据。
     */
    private record PreparedTask(
        String title,
        String objective,
        String background,
        Long projectId,
        Long agentVersionId,
        Long workflowVersionId,
        Map<String, Long> workflowAgentVersions,
        String visibility,
        String category,
        String orchestrationMode,
        String lifecycleLevel,
        String riskLevel,
        String acceptanceMode,
        int importance,
        int urgency,
        LocalDateTime startAt,
        String contextJson,
        String resourceJson,
        String acceptanceJson,
        String inputJson,
        String budgetJson,
        String externalRefsJson,
        String tagsJson,
        List<PreparedResource> resources
    ) {
    }

    /**
     * 封装Prepared资源相关的不可变数据。
     */
    private record PreparedResource(
        String resourceType,
        Long resourceId,
        String permission,
        boolean required,
        String grantSource,
        String grantSnapshotJson
    ) {
    }

    /**
     * 封装{@code PreparedAccessRule}相关的不可变数据。
     */
    private record PreparedAccessRule(
        String subjectType,
        Long subjectId,
        String subjectKey,
        String action,
        String effect,
        LocalDateTime expiresAt
    ) {
    }
}
