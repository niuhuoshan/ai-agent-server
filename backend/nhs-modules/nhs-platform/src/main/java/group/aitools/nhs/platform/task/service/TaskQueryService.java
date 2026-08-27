package group.aitools.nhs.platform.task.service;

import group.aitools.nhs.platform.audit.service.AuthorizationAuditService;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.platform.task.web.TaskVisibilityView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/**
 * 负责任务查询相关的业务编排与领域规则处理。
 * Visibility-aware task read service; execution permission is intentionally separate. */
@Service
public class TaskQueryService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final TaskVisibilityService taskVisibilityService;
    private final AuthorizationAuditService auditService;
    private final AgentTaskMapper taskMapper;
    private final JsonMapper jsonMapper;

    public TaskQueryService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        TaskVisibilityService taskVisibilityService,
        AuthorizationAuditService auditService,
        AgentTaskMapper taskMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.taskVisibilityService = taskVisibilityService;
        this.auditService = auditService;
        this.taskMapper = taskMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<TaskView> list(int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", null, null, "list", ResourceState.ACTIVE, true, Set.of(), null
        ));
        String principalType = principal.type() == PrincipalType.HUMAN ? "user" : "service_account";
        return taskMapper.selectVisibleTasks(
            principal.id(),
            principalType,
            principal.isHuman(),
            principal.hasRole(PlatformRole.MEMBER),
            principal.hasRole(PlatformRole.APPROVAL_USER),
            principal.hasRole(PlatformRole.PLATFORM_ADMIN),
            limit
        ).stream().map(task -> TaskView.from(task, jsonMapper)).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    public TaskView get(Long taskId) {
        AuthorizedTask authorized = authorizeView(taskId);
        return TaskView.from(authorized.task(), jsonMapper);
    }

    /**
     * 处理{@code visibility}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    public TaskVisibilityView visibility(Long taskId) {
        AuthorizedTask authorized = authorizeView(taskId);
        return TaskVisibilityView.from(
            taskId,
            authorized.task().getVisibility(),
            authorized.decision()
        );
    }

    /**
     * 处理{@code authorizeView}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    private AuthorizedTask authorizeView(Long taskId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentTask task = taskMapper.selectPlatformTaskById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在", HttpStatus.NOT_FOUND);
        }
        TaskVisibility visibility = "restricted".equals(task.getVisibility())
            ? TaskVisibility.RESTRICTED
            : TaskVisibility.ENTERPRISE_SHARED;
        AuthorizationDecision decision = taskVisibilityService.authorizeView(
            principal, taskId, null, visibility
        );
        PermissionContext context = new PermissionContext(
            "task", taskId, null, "view", ResourceState.ACTIVE, true, Set.of(), taskId
        );
        auditService.record(principal, context, decision);
        if (!decision.allowed()) {
            throw new ServiceException("任务不存在", HttpStatus.NOT_FOUND);
        }
        return new AuthorizedTask(task, decision);
    }

    /**
     * 封装Authorized任务相关的不可变数据。
     */
    private record AuthorizedTask(AgentTask task, AuthorizationDecision decision) {
    }
}
