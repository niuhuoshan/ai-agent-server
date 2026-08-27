package group.aitools.nhs.platform.memory.service;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 负责记忆范围授权相关的业务编排与领域规则处理。
 */
@Component
public class MemoryScopeAuthorizationService {

    private static final Set<String> SCOPES = Set.of("user", "project", "task");

    private final AuthorizationEnforcer authorizationEnforcer;
    private final TaskVisibilityService taskVisibilityService;
    private final AgentProjectMapper projectMapper;
    private final AgentTaskMapper taskMapper;
    private final TaskControlMapper taskControlMapper;

    /**
     * 创建 {@code MemoryScopeAuthorizationService} 实例并初始化所需依赖。
     *
     * @param authorizationEnforcer 授权Enforcer参数
     * @param taskVisibilityService 任务VisibilityService参数
     * @param projectMapper 项目Mapper参数
     * @param taskMapper 任务Mapper参数
     * @param taskControlMapper 任务ControlMapper参数
     */
    public MemoryScopeAuthorizationService(
        AuthorizationEnforcer authorizationEnforcer,
        TaskVisibilityService taskVisibilityService,
        AgentProjectMapper projectMapper,
        AgentTaskMapper taskMapper,
        TaskControlMapper taskControlMapper
    ) {
        this.authorizationEnforcer = authorizationEnforcer;
        this.taskVisibilityService = taskVisibilityService;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskControlMapper = taskControlMapper;
    }

    /**
     * 校验{@code View}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param userInterface 用户Interface参数
     */
    public void requireView(
        CurrentPrincipal principal, String scopeType, Long scopeId, boolean userInterface
    ) {
        AuthorizationDecision decision = viewDecision(principal, scopeType, scopeId, userInterface);
        if (!decision.allowed()) {
            throw new ServiceException("记忆作用域没有查看权限：" + decision.reasonCode(), HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验{@code Manage}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param userInterface 用户Interface参数
     */
    public void requireManage(
        CurrentPrincipal principal, String scopeType, Long scopeId, boolean userInterface
    ) {
        AuthorizationDecision decision = manageDecision(principal, scopeType, scopeId, userInterface);
        if (!decision.allowed()) {
            throw new ServiceException("记忆作用域没有管理权限：" + decision.reasonCode(), HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 判断{@code View}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean canView(CurrentPrincipal principal, String scopeType, Long scopeId) {
        try {
            return viewDecision(principal, scopeType, scopeId, false).allowed();
        } catch (ServiceException exception) {
            return false;
        }
    }

    /**
     * 判断{@code Manage}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean canManage(CurrentPrincipal principal, String scopeType, Long scopeId) {
        try {
            return manageDecision(principal, scopeType, scopeId, true).allowed();
        } catch (ServiceException exception) {
            return false;
        }
    }

    /**
     * 处理{@code viewDecision}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param userInterface 用户Interface参数
     * @return 处理结果
     */
    private AuthorizationDecision viewDecision(
        CurrentPrincipal principal, String scopeType, Long scopeId, boolean userInterface
    ) {
        validate(scopeType, scopeId);
        if ("user".equals(scopeType)) {
            return privateUserDecision(principal, scopeId);
        }
        if ("project".equals(scopeType)) {
            AgentProject project = requireProject(scopeId);
            return authorizationEnforcer.decide(principal, projectContext(
                project, principal, "view", userInterface
            ));
        }
        AgentTask task = requireTask(scopeId);
        TaskVisibility visibility = "restricted".equals(task.getVisibility())
            ? TaskVisibility.RESTRICTED : TaskVisibility.ENTERPRISE_SHARED;
        return taskVisibilityService.authorizeView(principal, scopeId, null, visibility);
    }

    /**
     * 处理{@code manageDecision}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param userInterface 用户Interface参数
     * @return 处理结果
     */
    private AuthorizationDecision manageDecision(
        CurrentPrincipal principal, String scopeType, Long scopeId, boolean userInterface
    ) {
        validate(scopeType, scopeId);
        if ("user".equals(scopeType)) {
            return privateUserDecision(principal, scopeId);
        }
        if ("project".equals(scopeType)) {
            AgentProject project = requireProject(scopeId);
            return authorizationEnforcer.decide(principal, projectContext(
                project, principal, "admin", userInterface
            ));
        }
        AgentTask task = requireTask(scopeId);
        Set<BusinessRelation> relations = taskControlMapper.selectRelations(
                scopeId, principal.id(), principal.type().name().toLowerCase(Locale.ROOT)
            )
            .stream().map(BusinessRelation::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return authorizationEnforcer.decide(principal, new PermissionContext(
            "task", scopeId, task.getTaskKey(), "admin", ResourceState.ACTIVE,
            userInterface, relations, scopeId
        ));
    }

    /**
     * 处理private用户Decision并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param scopeId 资源标识
     * @return 处理结果
     */
    private AuthorizationDecision privateUserDecision(CurrentPrincipal principal, Long scopeId) {
        boolean allowed = principal != null && principal.isHuman() && principal.id().equals(scopeId);
        return new AuthorizationDecision(
            allowed ? PermissionEffect.ALLOW : PermissionEffect.DENY,
            allowed ? "PRIVATE_USER_MEMORY_OWNER" : "PRIVATE_USER_MEMORY_FORBIDDEN",
            allowed ? "用户只能访问自己的个人记忆。" : "个人记忆不允许跨用户访问。",
            java.util.List.of()
        );
    }

    /**
     * 处理项目上下文并返回对应结果。
     *
     * @param project 项目参数
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param userInterface 用户Interface参数
     * @return 处理结果
     */
    private PermissionContext projectContext(
        AgentProject project,
        CurrentPrincipal principal,
        String action,
        boolean userInterface
    ) {
        return new PermissionContext(
            "project", project.getId(), project.getProjectKey(), action,
            "active".equals(project.getStatus()) ? ResourceState.ACTIVE : ResourceState.INACTIVE,
            userInterface, projectRelations(project, principal), null
        );
    }

    /**
     * 处理项目Relations并返回对应结果。
     *
     * @param project 项目参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> projectRelations(
        AgentProject project, CurrentPrincipal principal
    ) {
        Set<BusinessRelation> result = new LinkedHashSet<>();
        if (Objects.equals(project.getOwnerId(), principal.id())) {
            result.add(BusinessRelation.OWNER);
        }
        AgentProjectMember member = projectMapper.selectActiveMember(project.getId(), principal.id());
        if (member != null) {
            switch (member.getMemberRole()) {
                case "owner" -> result.add(BusinessRelation.OWNER);
                case "manager" -> result.add(BusinessRelation.PROJECT_ADMIN);
                case "member" -> result.add(BusinessRelation.COLLABORATOR);
                case "viewer" -> result.add(BusinessRelation.WATCHER);
                default -> throw new ServiceException("项目成员角色数据无效", HttpStatus.CONFLICT);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 校验项目，并在条件不满足时终止处理。
     *
     * @param scopeId 资源标识
     * @return 处理结果
     */
    private AgentProject requireProject(Long scopeId) {
        AgentProject project = projectMapper.selectProject(scopeId);
        if (project == null) {
            throw new ServiceException("项目记忆作用域不存在", HttpStatus.NOT_FOUND);
        }
        return project;
    }

    /**
     * 校验任务，并在条件不满足时终止处理。
     *
     * @param scopeId 资源标识
     * @return 处理结果
     */
    private AgentTask requireTask(Long scopeId) {
        AgentTask task = taskMapper.selectPlatformTaskById(scopeId);
        if (task == null) {
            throw new ServiceException("任务记忆作用域不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void validate(String scopeType, Long scopeId) {
        if (!SCOPES.contains(scopeType) || scopeId == null || scopeId <= 0) {
            throw new ServiceException("记忆作用域无效", HttpStatus.BAD_REQUEST);
        }
    }
}
