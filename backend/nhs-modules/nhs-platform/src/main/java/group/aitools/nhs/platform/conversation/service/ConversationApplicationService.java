package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.web.ConversationView;
import group.aitools.nhs.platform.conversation.web.ConversationMessageView;
import group.aitools.nhs.platform.conversation.web.CreateConversationRequest;
import group.aitools.nhs.platform.conversation.web.CreateConversationBranchRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.TaskConversionResult;
import group.aitools.nhs.platform.task.web.TaskDraftView;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责会话相关的业务编排与领域规则处理。
 * Owns private-conversation operations and the explicit conversion into a shared task. */
@Service
public class ConversationApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentConversationMapper conversationMapper;
    private final AgentProjectMapper projectMapper;
    private final AgentTaskMapper taskMapper;
    private final TaskApplicationService taskApplicationService;

    public ConversationApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentConversationMapper conversationMapper,
        AgentProjectMapper projectMapper,
        AgentTaskMapper taskMapper,
        TaskApplicationService taskApplicationService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.conversationMapper = conversationMapper;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskApplicationService = taskApplicationService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public ConversationView create(CreateConversationRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, uiContext("conversation", null, "create"));
        if (request.agentVersionId() != null) {
            authorizationEnforcer.requireAllowed(
                principal,
                uiContext("agent_version", request.agentVersionId(), "use")
            );
        }
        requireProjectAccess(principal, request.projectId());

        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setId(idGenerator.nextId());
        conversation.setUserId(principal.id());
        conversation.setProjectId(request.projectId());
        conversation.setAgentId(request.agentId());
        conversation.setAgentVersionId(request.agentVersionId());
        conversation.setBranchId("root-" + idGenerator.nextUuid());
        conversation.setPrincipalType("human");
        conversation.setTitle(normalizeTitle(request.title()));
        conversation.setVisibility("private");
        conversation.setStatus("active");
        conversation.setSessionKey("conv-" + idGenerator.nextUuid());
        conversation.setCreateBy(principal.id());
        conversation.setCreateTime(now);
        conversation.setDelFlag("0");
        conversationMapper.insert(conversation);
        return ConversationView.from(conversation);
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConversationView> list(int limit) {
        return list(null, limit);
    }

    /**
     * 查询{@code list}列表。
     *
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConversationView> list(String search, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, uiContext("conversation", null, "list"));
        String normalizedSearch = normalizeSearch(search);
        List<AgentConversation> conversations = normalizedSearch == null
            ? conversationMapper.selectRecentOwnedConversations(principal.id(), limit)
            : conversationMapper.searchOwnedConversations(principal.id(), normalizedSearch, limit);
        return conversations.stream()
            .map(ConversationView::from)
            .toList();
    }

    /**
     * 处理{@code messages}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param afterSequence 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConversationMessageView> messages(
        Long conversationId,
        int afterSequence,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = requireOwnedConversation(conversationId, principal);
        authorizationEnforcer.requireAllowed(
            principal,
            uiContext("conversation", conversationId, "view")
        );
        List<ConversationMessageView> messages = (conversation.getParentConversationId() == null
            ? conversationMapper.selectMessages(conversationId, afterSequence, limit)
            : conversationMapper.selectLineageMessages(conversationId, afterSequence, limit)).stream()
            .map(ConversationMessageView::from)
            .toList();
        return messages;
    }

    /**
 * 创建并保存{@code Branch}。
 * Creates a durable owner-bound branch without copying or deleting history. */
    @Transactional(rollbackFor = Exception.class)
    public BranchStart createBranch(Long conversationId, CreateConversationBranchRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, uiContext("conversation", conversationId, "branch"));
        AgentConversation parent = conversationMapper.lockOwnedConversation(conversationId, principal.id());
        if (parent == null) throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        if (!"active".equals(parent.getStatus())) {
            throw new ServiceException("归档会话不能创建分支", HttpStatus.CONFLICT);
        }
        if (conversationMapper.selectActiveTurnId(conversationId) != null) {
            throw new ServiceException("当前会话仍有回合在执行，无法创建分支", HttpStatus.CONFLICT);
        }
        var forkRow = conversationMapper.selectOwnedForkMessage(
            conversationId, request.forkMessageId(), principal.id()
        );
        if (forkRow == null || !"user".equals(forkRow.getRole())) {
            throw new ServiceException("只能从当前会话的用户消息创建分支", HttpStatus.BAD_REQUEST);
        }
        ConversationMessageView fork = ConversationMessageView.from(forkRow);
        int cutoff = Math.max(0, fork.sequenceNo() - 1);
        String branchId = group.aitools.nhs.platform.common.ContentHashing.sha256(
            "conversation-branch\0" + conversationId + "\0" + request.forkMessageId()
                + "\0" + request.idempotencyKey().strip()
        );
        AgentConversation existing = conversationMapper.selectOwnedBranch(principal.id(), branchId);
        if (existing != null) {
            if (!conversationId.equals(existing.getParentConversationId())
                || !request.forkMessageId().equals(existing.getForkMessageId())) {
                throw new ServiceException("分支幂等键已用于其他消息", HttpStatus.CONFLICT);
            }
            return new BranchStart(ConversationView.from(existing), fork.content(), true);
        }
        LocalDateTime now = LocalDateTime.now();
        AgentConversation branch = new AgentConversation();
        branch.setId(idGenerator.nextId());
        branch.setUserId(principal.id());
        branch.setProjectId(parent.getProjectId());
        branch.setAgentId(parent.getAgentId());
        branch.setAgentVersionId(parent.getAgentVersionId());
        branch.setBranchId(branchId);
        branch.setParentConversationId(conversationId);
        branch.setForkMessageId(request.forkMessageId());
        branch.setContextCutoffSequence(cutoff);
        branch.setPrincipalType("human");
        String parentTitle = parent.getTitle() == null || parent.getTitle().isBlank()
            ? "会话 #" + parent.getId() : parent.getTitle();
        branch.setTitle(normalizeTitle(branchTitle(parentTitle)));
        branch.setVisibility("private");
        branch.setStatus("active");
        branch.setSessionKey("conv-" + idGenerator.nextUuid());
        branch.setCreateBy(principal.id());
        branch.setCreateTime(now);
        branch.setDelFlag("0");
        if (conversationMapper.insert(branch) != 1) {
            AgentConversation raced = conversationMapper.selectOwnedBranch(principal.id(), branchId);
            if (raced == null) throw new ServiceException("会话分支创建冲突", HttpStatus.CONFLICT);
            branch = raced;
        }
        conversationMapper.copyResourceScope(conversationId, branch.getId(), principal.id());
        return new BranchStart(ConversationView.from(branch), fork.content(), false);
    }

    /**
     * 封装{@code BranchStart}相关的不可变数据。
     */
    public record BranchStart(ConversationView conversation, String input, boolean replayed) {
    }

    /**
     * 获取{@code get}。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    public ConversationView get(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return get(principal, conversationId);
    }

    /**
 * 获取{@code get}。
 * Owner-scoped conversation read for scheduled or background platform work. */
    public ConversationView get(CurrentPrincipal principal, Long conversationId) {
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("会话读取必须绑定有效用户主体", HttpStatus.FORBIDDEN);
        }
        AgentConversation conversation = requireOwnedConversation(conversationId, principal);
        authorizationEnforcer.requireAllowed(
            principal,
            uiContext("conversation", conversationId, "view")
        );
        return ConversationView.from(conversation);
    }

    /**
     * 将输入数据转换为To任务。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskConversionResult convertToTask(
        Long conversationId,
        ConvertConversationToTaskRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = conversationMapper.selectOwnedConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        if (conversation.getTaskId() != null) {
            return existingConversion(conversation.getTaskId());
        }

        TaskMutationResult result = taskApplicationService.createFromConversation(conversationId, request);
        Long taskId = result.task().id();
        if (conversationMapper.linkTaskIfAbsent(conversationId, principal.id(), taskId) != 1) {
            AgentConversation refreshed = conversationMapper.selectOwnedConversation(
                conversationId, principal.id()
            );
            if (refreshed == null || !taskId.equals(refreshed.getTaskId())) {
                throw new ServiceException("会话任务绑定已被并发修改", HttpStatus.CONFLICT);
            }
        }
        return new TaskConversionResult(taskId, result.taskVersionId(), result.replayed());
    }

    /**
     * 处理preview任务Draft并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    public TaskDraftView previewTaskDraft(
        Long conversationId,
        ConvertConversationToTaskRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = conversationMapper.selectOwnedConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        if (conversation.getTaskId() != null
            || taskMapper.selectBySourceConversationId(conversationId) != null) {
            throw new ServiceException("会话已经提交为正式任务", HttpStatus.CONFLICT);
        }

        String draftHash = taskApplicationService.previewConversationDraftHash(conversationId, request);
        return new TaskDraftView(conversationId, draftHash, request.withDraftHash(draftHash), true);
    }

    /**
     * 处理{@code existingConversion}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    private TaskConversionResult existingConversion(Long taskId) {
        AgentTask task = taskMapper.selectPlatformTaskById(taskId);
        if (task == null) {
            throw new ServiceException("会话关联的任务不存在", HttpStatus.CONFLICT);
        }
        return new TaskConversionResult(task.getId(), task.getCurrentVersionId(), true);
    }

    /**
     * 处理ui上下文并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @return 处理结果
     */
    private PermissionContext uiContext(String resourceType, Long resourceId, String action) {
        return new PermissionContext(
            resourceType, resourceId, null, action, ResourceState.ACTIVE, true, Set.of(), null
        );
    }

    /**
     * 校验Owned会话，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConversation requireOwnedConversation(Long conversationId, CurrentPrincipal principal) {
        AgentConversation conversation = conversationMapper.selectOwnedConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    /**
 * 校验项目Access，并在条件不满足时终止处理。
 * Project-scoped conversations must not be used as an unvalidated project data handle. */
    private void requireProjectAccess(CurrentPrincipal principal, Long projectId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (projectId == null) {
            return;
        }
        AgentProject project = projectMapper.selectProject(projectId);
        if (project == null) {
            throw projectNotFound();
        }
        Set<BusinessRelation> relations = projectRelations(project, principal);
        try {
            authorizationEnforcer.requireAllowed(principal, new PermissionContext(
                "project", projectId, project.getProjectKey(), "view", projectState(project),
                true, relations, null
            ));
        } catch (ServiceException exception) {
            if (Integer.valueOf(HttpStatus.FORBIDDEN).equals(exception.getCode())) {
                throw projectNotFound();
            }
            throw exception;
        }
        if (!"active".equals(project.getStatus())) {
            throw new ServiceException("只有活动项目可以创建会话", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理项目State并返回对应结果。
     *
     * @param project 项目参数
     * @return 处理结果
     */
    private ResourceState projectState(AgentProject project) {
        return "active".equals(project.getStatus()) ? ResourceState.ACTIVE : ResourceState.INACTIVE;
    }

    /**
     * 处理项目NotFound并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException projectNotFound() {
        return new ServiceException("项目不存在", HttpStatus.NOT_FOUND);
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
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        if (!principal.isHuman()) {
            return Set.of();
        }
        Set<BusinessRelation> relations = new LinkedHashSet<>();
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
                default -> throw new ServiceException("项目成员角色数据无效", HttpStatus.CONFLICT);
            }
        }
        return Set.copyOf(relations);
    }

    /**
     * 处理{@code normalizeTitle}并返回对应结果。
     *
     * @param title {@code title}参数
     * @return 处理结果
     */
    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "新会话";
        }
        String normalized = title.strip();
        if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
            throw new ServiceException("会话标题过长或包含非法字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code branchTitle}并返回对应结果。
     *
     * @param parentTitle {@code parentTitle}参数
     * @return 处理结果
     */
    private String branchTitle(String parentTitle) {
        String suffix = " · 分支";
        int maxParentLength = 255 - suffix.length();
        if (parentTitle.length() <= maxParentLength) {
            return parentTitle + suffix;
        }
        int end = maxParentLength;
        if (Character.isHighSurrogate(parentTitle.charAt(end - 1))) {
            end--;
        }
        return parentTitle.substring(0, end) + suffix;
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param search {@code search}参数
     * @return 处理结果
     */
    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String normalized = search.strip();
        if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
            throw new ServiceException("会话搜索词过长或包含非法字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

}
