package group.aitools.nhs.platform.project.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.project.web.CreateProjectRequest;
import group.aitools.nhs.platform.project.web.ProjectMemberView;
import group.aitools.nhs.platform.project.web.ProjectMutationResult;
import group.aitools.nhs.platform.project.web.ProjectView;
import group.aitools.nhs.platform.project.web.UpdateProjectRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
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
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责项目相关的业务编排与领域规则处理。
 * Project lifecycle, membership and policy management. */
@Service
public class ProjectApplicationService {

    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> STATUSES = Set.of("active", "suspended", "archived");
    private static final Set<String> MEMBER_ROLES = Set.of("manager", "member", "viewer");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentProjectMapper projectMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ProjectApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param projectMapper 项目Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ProjectApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentProjectMapper projectMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.projectMapper = projectMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ProjectView> list(String status, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "list", ResourceState.ACTIVE, Set.of()));
        String normalizedStatus = normalizeStatusFilter(status);
        return projectMapper.selectVisibleProjects(
            principal.id(), principal.hasRole(PlatformRole.PLATFORM_ADMIN), normalizedStatus, limit
        ).stream().map(project -> ProjectView.from(project, jsonMapper)).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param projectId 资源标识
     * @return 处理结果
     */
    public ProjectView get(Long projectId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentProject project = requireProject(projectId);
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "view", state(project), relations(project, principal)
        ));
        return ProjectView.from(project, jsonMapper);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectMutationResult create(CreateProjectRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "create", ResourceState.ACTIVE, Set.of()));
        PreparedProject prepared = prepare(
            request.name(), request.description(), request.defaultAgentVersionId(),
            request.workspacePolicy(), request.notificationPolicy(), request.tags()
        );
        authorizeDefaultAgent(principal, prepared.defaultAgentVersionId());
        String key = "P-" + ContentHashing.sha256(
            principal.id() + ":" + request.idempotencyKey().strip()
        ).substring(0, 32);
        String requestHash = creationHash(prepared);

        AgentProject existing = projectMapper.selectByKey(key);
        if (existing != null) {
            return replay(existing, principal.id(), requestHash);
        }

        LocalDateTime now = LocalDateTime.now();
        AgentProject project = new AgentProject();
        project.setId(idGenerator.nextId());
        project.setProjectKey(key);
        apply(project, prepared);
        project.setStatus("active");
        project.setOwnerId(principal.id());
        project.setCreateBy(principal.id());
        project.setCreateTime(now);
        project.setDelFlag("0");
        project.setExtraJson(jsonMapper.writeValueAsString(Map.of("creationRequestHash", requestHash)));
        if (projectMapper.insertProject(project) != 1) {
            AgentProject raced = projectMapper.selectByKey(key);
            if (raced == null) {
                throw conflict("项目幂等写入冲突");
            }
            return replay(raced, principal.id(), requestHash);
        }

        AgentProjectMember owner = member(project.getId(), principal.id(), "owner", principal.id(), now);
        if (projectMapper.insertMember(owner) != 1) {
            throw conflict("项目所有者关系创建失败");
        }
        return new ProjectMutationResult(ProjectView.from(project, jsonMapper), false);
    }

    /**
     * 更新{@code update}。
     *
     * @param projectId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectView update(Long projectId, UpdateProjectRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        lockProject(projectId);
        AgentProject project = requireProject(projectId);
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "admin", state(project), relations(project, principal)
        ));
        if ("archived".equals(project.getStatus())) {
            throw conflict("已归档项目不可修改");
        }
        PreparedProject prepared = prepare(
            request.name(), request.description(), request.defaultAgentVersionId(),
            request.workspacePolicy(), request.notificationPolicy(), request.tags()
        );
        authorizeDefaultAgent(principal, prepared.defaultAgentVersionId());
        apply(project, prepared);
        project.setUpdateBy(principal.id());
        project.setUpdateTime(LocalDateTime.now());
        if (projectMapper.updateProject(project) != 1) {
            throw conflict("项目状态在修改时发生变化");
        }
        return ProjectView.from(project, jsonMapper);
    }

    /**
     * 更新{@code Status}。
     *
     * @param projectId 资源标识
     * @param targetStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectView updateStatus(Long projectId, String targetStatus) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String target = normalizeStatus(targetStatus);
        lockProject(projectId);
        AgentProject project = requireProject(projectId);
        Set<BusinessRelation> relations = relations(project, principal);
        if (target.equals(project.getStatus())) {
            authorizationEnforcer.requireAllowed(principal, context(
                projectId, project.getProjectKey(), "view", state(project), relations
            ));
            return ProjectView.from(project, jsonMapper);
        }
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "admin", state(project), relations
        ));
        if ("archived".equals(project.getStatus())) {
            throw conflict("已归档项目不可恢复");
        }
        if (projectMapper.updateStatus(
            projectId, project.getStatus(), target, principal.id(), LocalDateTime.now()
        ) != 1) {
            throw conflict("项目状态已被并发修改");
        }
        project.setStatus(target);
        if ("archived".equals(target)) {
            project.setArchivedAt(LocalDateTime.now());
        }
        return ProjectView.from(project, jsonMapper);
    }

    /**
     * 处理{@code members}并返回对应结果。
     *
     * @param projectId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ProjectMemberView> members(Long projectId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentProject project = requireProject(projectId);
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "view", state(project), relations(project, principal)
        ));
        return projectMapper.selectActiveMembers(projectId, limit).stream()
            .map(ProjectMemberView::from).toList();
    }

    /**
     * 处理{@code putMember}并返回对应结果。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @param requestedRole requested角色参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectMemberView putMember(Long projectId, Long userId, String requestedRole) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String role = normalizeMemberRole(requestedRole);
        lockProject(projectId);
        AgentProject project = requireProject(projectId);
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "admin", state(project), relations(project, principal)
        ));
        if (project.getOwnerId().equals(userId)) {
            throw conflict("项目所有者角色不能通过成员接口修改");
        }
        AgentProjectMember existing = projectMapper.selectActiveMember(projectId, userId);
        if (existing != null) {
            if (role.equals(existing.getMemberRole())) {
                return ProjectMemberView.from(existing);
            }
            if (projectMapper.updateMemberRole(projectId, userId, role) != 1) {
                throw conflict("项目成员角色已被并发修改");
            }
            existing.setMemberRole(role);
            return ProjectMemberView.from(existing);
        }

        AgentProjectMember created = member(
            projectId, userId, role, principal.id(), LocalDateTime.now()
        );
        if (projectMapper.insertMember(created) != 1) {
            AgentProjectMember raced = projectMapper.selectActiveMember(projectId, userId);
            if (raced == null || !role.equals(raced.getMemberRole())) {
                throw conflict("项目成员写入冲突");
            }
            return ProjectMemberView.from(raced);
        }
        return ProjectMemberView.from(created);
    }

    /**
     * 删除{@code Member}。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long projectId, Long userId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        lockProject(projectId);
        AgentProject project = requireProject(projectId);
        authorizationEnforcer.requireAllowed(principal, context(
            projectId, project.getProjectKey(), "admin", state(project), relations(project, principal)
        ));
        if (project.getOwnerId().equals(userId)) {
            throw conflict("项目所有者不能被移除");
        }
        AgentProjectMember existing = projectMapper.selectActiveMember(projectId, userId);
        if (existing == null) {
            return;
        }
        if (projectMapper.removeMember(projectId, userId) != 1) {
            throw conflict("项目成员状态已被并发修改");
        }
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param name 名称
     * @param description {@code description}参数
     * @param defaultAgentVersionId 资源标识
     * @param workspacePolicy 工作空间策略参数
     * @param notificationPolicy 通知策略参数
     * @param tags {@code tags}参数
     * @return 处理结果
     */
    private PreparedProject prepare(
        String name,
        String description,
        Long defaultAgentVersionId,
        Map<String, Object> workspacePolicy,
        Map<String, Object> notificationPolicy,
        List<String> tags
    ) {
        String normalizedName = requiredText(name, "项目名称", 128);
        String normalizedDescription = optionalText(description, "项目描述", 12000);
        String workspaceJson = policyJson(workspacePolicy, "Workspace策略");
        String notificationJson = policyJson(notificationPolicy, "通知策略");
        String tagsJson = jsonMapper.writeValueAsString(normalizeTags(tags));
        return new PreparedProject(
            normalizedName, normalizedDescription, defaultAgentVersionId,
            workspaceJson, notificationJson, tagsJson
        );
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param project 项目参数
     * @param prepared {@code prepared}参数
     */
    private void apply(AgentProject project, PreparedProject prepared) {
        project.setName(prepared.name());
        project.setDescription(prepared.description());
        project.setDefaultAgentVersionId(prepared.defaultAgentVersionId());
        project.setWorkspacePolicyJson(prepared.workspacePolicyJson());
        project.setNotificationPolicyJson(prepared.notificationPolicyJson());
        project.setTagsJson(prepared.tagsJson());
    }

    /**
     * 处理{@code creationHash}并返回对应结果。
     *
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private String creationHash(PreparedProject prepared) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", prepared.name());
        value.put("description", prepared.description());
        value.put("defaultAgentVersionId", prepared.defaultAgentVersionId());
        value.put("workspacePolicy", jsonMapper.readValue(prepared.workspacePolicyJson(), MAP_TYPE));
        value.put("notificationPolicy", jsonMapper.readValue(prepared.notificationPolicyJson(), MAP_TYPE));
        value.put("tags", jsonMapper.readValue(prepared.tagsJson(), List.class));
        return ContentHashing.sha256(jsonMapper.writeValueAsString(value));
    }

    /**
     * 处理{@code replay}并返回对应结果。
     *
     * @param project 项目参数
     * @param ownerId 资源标识
     * @param requestHash {@code requestHash}参数
     * @return 处理结果
     */
    private ProjectMutationResult replay(AgentProject project, Long ownerId, String requestHash) {
        Map<String, Object> extra = project.getExtraJson() == null
            ? Map.of() : jsonMapper.readValue(project.getExtraJson(), MAP_TYPE);
        if (!ownerId.equals(project.getOwnerId()) || !requestHash.equals(extra.get("creationRequestHash"))) {
            throw conflict("同一项目幂等键不能用于不同请求");
        }
        return new ProjectMutationResult(ProjectView.from(project, jsonMapper), true);
    }

    /**
     * 处理{@code relations}并返回对应结果。
     *
     * @param project 项目参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> relations(AgentProject project, CurrentPrincipal principal) {
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
     * 处理{@code member}并返回对应结果。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @param role 角色参数
     * @param createdBy {@code createdBy}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentProjectMember member(
        Long projectId,
        Long userId,
        String role,
        Long createdBy,
        LocalDateTime now
    ) {
        AgentProjectMember member = new AgentProjectMember();
        member.setId(idGenerator.nextId());
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setPermissionJson("{}");
        member.setStatus("active");
        member.setJoinedAt(now);
        member.setCreatedBy(createdBy);
        member.setCreatedAt(now);
        return member;
    }

    /**
     * 处理authorizeDefault智能体相关逻辑。
     *
     * @param principal 当前操作主体
     * @param agentVersionId 资源标识
     */
    private void authorizeDefaultAgent(CurrentPrincipal principal, Long agentVersionId) {
        if (agentVersionId != null) {
            authorizationEnforcer.requireAllowed(principal, new PermissionContext(
                "agent_version", agentVersionId, null, "use", ResourceState.ACTIVE,
                true, Set.of(), null
            ));
        }
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param projectId 资源标识
     * @param projectKey 项目Key参数
     * @param action {@code action}参数
     * @param state {@code state}参数
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private PermissionContext context(
        Long projectId,
        String projectKey,
        String action,
        ResourceState state,
        Set<BusinessRelation> relations
    ) {
        return new PermissionContext(
            "project", projectId, projectKey, action, state, true, relations, null
        );
    }

    /**
     * 处理{@code state}并返回对应结果。
     *
     * @param project 项目参数
     * @return 处理结果
     */
    private ResourceState state(AgentProject project) {
        return "archived".equals(project.getStatus()) ? ResourceState.INACTIVE : ResourceState.ACTIVE;
    }

    /**
     * 校验项目，并在条件不满足时终止处理。
     *
     * @param projectId 资源标识
     * @return 处理结果
     */
    private AgentProject requireProject(Long projectId) {
        AgentProject project = projectMapper.selectProject(projectId);
        if (project == null) {
            throw new ServiceException("项目不存在", HttpStatus.NOT_FOUND);
        }
        return project;
    }

    /**
     * 处理lock项目相关逻辑。
     *
     * @param projectId 资源标识
     */
    private void lockProject(Long projectId) {
        if (projectMapper.lockProject(projectId) == null) {
            throw new ServiceException("项目不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 处理{@code normalizeStatusFilter}并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String normalizeStatusFilter(String status) {
        return status == null || status.isBlank() ? null : normalizeStatus(status);
    }

    /**
     * 处理{@code normalizeStatus}并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String normalizeStatus(String status) {
        String value = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw badRequest("项目状态无效");
        }
        return value;
    }

    /**
     * 处理normalizeMember角色并返回对应结果。
     *
     * @param role 角色参数
     * @return 处理结果
     */
    private String normalizeMemberRole(String role) {
        String value = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
        if (!MEMBER_ROLES.contains(value)) {
            throw badRequest("项目成员角色无效");
        }
        return value;
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
            throw badRequest("项目标签不能超过32个");
        }
        ArrayList<String> result = new ArrayList<>(tags.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = requiredText(tag, "项目标签", 64);
            if (!seen.add(normalized)) {
                throw badRequest("项目标签不能重复");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    /**
     * 处理策略Json并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String policyJson(Map<String, Object> value, String label) {
        Map<String, Object> normalized = value == null ? Map.of() : canonicalMap(value, 0, label);
        String json = jsonMapper.writeValueAsString(normalized);
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
        TreeMap<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank() || key.length() > 128 || isSecretKey(key)) {
                throw badRequest(label + "包含敏感或无效字段");
            }
            result.put(key, canonicalValue(item, depth + 1, label));
        });
        return new LinkedHashMap<>(result);
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
        return Set.of("secret", "password", "token", "apikey", "authorization", "credential", "privatekey")
            .stream().anyMatch(normalized::contains);
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
     * 封装Prepared项目相关的不可变数据。
     */
    private record PreparedProject(
        String name,
        String description,
        Long defaultAgentVersionId,
        String workspacePolicyJson,
        String notificationPolicyJson,
        String tagsJson
    ) {
    }
}
