package group.aitools.nhs.platform.agent.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentVersionResourceMapper;
import group.aitools.nhs.platform.agent.persistence.row.AgentResourceSnapshotRow;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentConfigurationValidator.ValidatedBinding;
import group.aitools.nhs.platform.agent.web.AgentOnboardingRequest;
import group.aitools.nhs.platform.agent.web.AgentOnboardingResult;
import group.aitools.nhs.platform.agent.web.AgentReorderItemRequest;
import group.aitools.nhs.platform.agent.web.AgentVersionPublishResult;
import group.aitools.nhs.platform.agent.web.AgentVersionView;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.agent.web.CreateAgentRequest;
import group.aitools.nhs.platform.agent.web.SaveAgentVersionRequest;
import group.aitools.nhs.platform.agent.web.UpdateAgentRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 负责智能体相关的业务编排与领域规则处理。
 * Agent identity and immutable version lifecycle use cases. */
@Service
public class AgentApplicationService {

    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Pattern AGENT_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentDefinitionMapper definitionMapper;
    private final AgentDefinitionVersionMapper versionMapper;
    private final AgentVersionResourceMapper resourceMapper;
    private final AgentModelMapper modelMapper;
    private final AgentConfigurationValidator configurationValidator;
    private final AgentVersionContentHasher contentHasher;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code AgentApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param definitionMapper 定义Mapper参数
     * @param versionMapper 版本Mapper参数
     * @param resourceMapper 资源Mapper参数
     * @param modelMapper 模型Mapper参数
     * @param configurationValidator 配置Validator参数
     * @param contentHasher 待处理内容
     * @param jsonMapper {@code jsonMapper}参数
     */
    public AgentApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentDefinitionMapper definitionMapper,
        AgentDefinitionVersionMapper versionMapper,
        AgentVersionResourceMapper resourceMapper,
        AgentModelMapper modelMapper,
        AgentConfigurationValidator configurationValidator,
        AgentVersionContentHasher contentHasher,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.resourceMapper = resourceMapper;
        this.modelMapper = modelMapper;
        this.configurationValidator = configurationValidator;
        this.contentHasher = contentHasher;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param search {@code search}参数
     * @param includeArchived {@code includeArchived}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AgentView> list(String search, boolean includeArchived, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", null, null, "list"));
        return definitionMapper.selectDefinitions(normalizeSearch(search), includeArchived, limit).stream()
            .map(definition -> AgentView.from(definition, jsonMapper))
            .toList();
    }

    /**
     * 处理{@code allowed}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AgentView> allowed(int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return definitionMapper.selectActiveCandidates(limit).stream()
            .filter(definition -> authorizationEnforcer.decide(
                principal,
                context("agent_version", definition.getPublishedVersionId(), definition.getAgentKey(), "use")
            ).allowed())
            .map(definition -> AgentView.from(definition, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    public AgentView get(Long agentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", agentId, null, "view"));
        return AgentView.from(requireAgent(agentId), jsonMapper);
    }

    /**
 * 处理嵌入式会话Access并返回对应结果。
 *
     * Resolves the Nhs EmbedChat URL pin by numeric id or stable agent key.
     * Inactive and unpublished agents intentionally use the same 404 response;
     * an existing published agent denied by the current principal is 403.
     */
    public AgentView embedAccess(String agentKeyOrId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String key = agentKeyOrId == null ? "" : agentKeyOrId.strip();
        AgentDefinition definition = null;
        Long agentId = positiveLong(key);
        if (agentId != null) {
            definition = definitionMapper.selectDefinitionById(agentId);
        } else if (!key.isEmpty()) {
            definition = definitionMapper.selectDefinitionByKey(key);
        }
        if (definition == null || !"active".equals(definition.getStatus())
            || definition.getPublishedVersionId() == null) {
            throw new ServiceException("智能体不存在或已停用", HttpStatus.NOT_FOUND);
        }
        authorizationEnforcer.requireAllowed(
            principal,
            context("agent_version", definition.getPublishedVersionId(), definition.getAgentKey(), "use")
        );
        return AgentView.from(definition, jsonMapper);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentView create(CreateAgentRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String key = request.agentKey() == null ? "" : request.agentKey().strip();
        if (!AGENT_KEY.matcher(key).matches()) {
            throw badRequest("Agent 标识格式无效");
        }
        authorizationEnforcer.requireAllowed(principal, context("agent", null, key, "create"));
        AgentDefinition definition = new AgentDefinition();
        definition.setId(idGenerator.nextId());
        definition.setAgentKey(key);
        applyDefinition(
            definition,
            request.name(),
            request.description(),
            request.agentType(),
            request.engineType(),
            request.avatarUrl(),
            request.defaultAgent(),
            request.sortOrder(),
            request.engineConfig()
        );
        definition.setIsSystem(false);
        definition.setStatus("draft");
        definition.setOwnerId(principal.id());
        definition.setCreateBy(principal.id());
        definition.setCreateTime(LocalDateTime.now());
        definition.setDelFlag("0");
        definition.setExtraJson("{}");
        try {
            definitionMapper.insertDefinition(definition);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("Agent 标识已存在：" + key, HttpStatus.CONFLICT);
        }
        return AgentView.from(definition, jsonMapper);
    }

    /**
     * 处理{@code onboard}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentOnboardingResult onboard(AgentOnboardingRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (request == null || request.agent() == null || request.version() == null) {
            throw badRequest("Agent 引导请求不完整");
        }
        String onboardingKey = request.onboardingKey() == null ? "" : request.onboardingKey().strip();
        if (onboardingKey.length() < 8 || onboardingKey.length() > 64) {
            throw badRequest("Agent 引导幂等键长度必须在 8 到 64 之间");
        }
        String requestHash = ContentHashing.sha256(jsonMapper.writeValueAsString(request));
        definitionMapper.lockOnboardingKey(principal.id(), onboardingKey);
        Long existingAgentId = definitionMapper.selectOnboardingAgentId(principal.id(), onboardingKey);
        if (existingAgentId != null) {
            AgentDefinition existing = requireAgent(existingAgentId);
            Map<String, Object> metadata = parseMap(existing.getExtraJson());
            Long versionId = positiveLong(metadata.get("onboardingVersionId"));
            if (!requestHash.equals(metadata.get("onboardingRequestHash"))) {
                throw conflict("同一 Agent 引导幂等键不能用于不同请求");
            }
            if (versionId == null) {
                throw conflict("Agent 引导记录缺少首个版本，无法安全重放");
            }
            authorizationEnforcer.requireAllowed(
                principal, context("agent", existingAgentId, existing.getAgentKey(), "view")
            );
            AgentDefinitionVersion version = requireVersion(existingAgentId, versionId);
            String step = metadata.get("onboardingStep") instanceof String value && !value.isBlank()
                ? value : "resource";
            return new AgentOnboardingResult(
                AgentView.from(existing, jsonMapper), toView(version), step, true, false
            );
        }

        AgentView agent = create(request.agent());
        AgentVersionView version = createVersion(agent.id(), request.version());
        LocalDateTime now = LocalDateTime.now();
        if (definitionMapper.updateOnboardingMetadata(
            agent.id(), onboardingKey, version.id(), requestHash, "resource", principal.id(), now
        ) != 1) {
            throw conflict("Agent 引导状态写入失败");
        }
        AgentDefinition stored = requireAgent(agent.id());
        return new AgentOnboardingResult(
            AgentView.from(stored, jsonMapper), version, "resource", false, false
        );
    }

    /**
     * 更新{@code update}。
     *
     * @param agentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentView update(Long agentId, UpdateAgentRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", agentId, null, "update"));
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireAgent(agentId);
        if ("archived".equals(definition.getStatus())) {
            throw conflict("已归档 Agent 不可修改");
        }
        String nextEngineType = request.engineType() == null
            ? definition.getEngineType() : configurationValidator.engineType(request.engineType());
        if (!nextEngineType.equals(definition.getEngineType())
            && definitionMapper.countVersions(agentId) > 0) {
            throw conflict("已有版本的 Agent 不能切换执行引擎，请新建 Agent");
        }
        applyDefinition(
            definition,
            request.name(),
            request.description(),
            request.agentType(),
            nextEngineType,
            request.avatarUrl(),
            request.defaultAgent(),
            request.sortOrder(),
            request.engineConfig()
        );
        LocalDateTime now = LocalDateTime.now();
        definition.setUpdateBy(principal.id());
        definition.setUpdateTime(now);
        if (Boolean.TRUE.equals(definition.getIsDefault()) && "active".equals(definition.getStatus())) {
            definitionMapper.clearOtherDefaults(agentId, principal.id(), now);
        }
        if (definitionMapper.updateDefinition(definition) != 1) {
            throw new ServiceException("Agent 不存在或已归档", HttpStatus.NOT_FOUND);
        }
        return AgentView.from(definition, jsonMapper);
    }

    /**
     * 处理{@code reorder}相关逻辑。
     *
     * @param items {@code items}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void reorder(List<AgentReorderItemRequest> items) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (items == null || items.isEmpty() || items.size() > 200) {
            throw badRequest("Agent 排序项数量必须在 1 到 200 之间");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (AgentReorderItemRequest item : items) {
            if (item == null || item.id() == null || item.id() <= 0) {
                throw badRequest("Agent 排序项标识无效");
            }
            if (item.sortOrder() < -10_000 || item.sortOrder() > 10_000) {
                throw badRequest("Agent 排序值必须在 -10000 到 10000 之间");
            }
            if (!uniqueIds.add(item.id())) {
                throw badRequest("Agent 排序项不能重复：" + item.id());
            }
        }
        for (Long agentId : uniqueIds.stream().sorted().toList()) {
            definitionMapper.lockAgent(agentId);
            authorizationEnforcer.requireAllowed(
                principal, context("agent", agentId, null, "update")
            );
            requireAgent(agentId);
        }
        LocalDateTime now = LocalDateTime.now();
        for (AgentReorderItemRequest item : items) {
            if (definitionMapper.updateSortOrder(item.id(), item.sortOrder(), principal.id(), now) != 1) {
                throw conflict("Agent 排序已被并发修改：" + item.id());
            }
        }
    }

    /**
     * 更新{@code Status}。
     *
     * @param agentId 资源标识
     * @param targetStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentView updateStatus(Long agentId, String targetStatus) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", agentId, null, "update"));
        if (!Set.of("active", "disabled", "archived").contains(targetStatus)) {
            throw badRequest("Agent 状态无效");
        }
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireAgent(agentId);
        if ("archived".equals(definition.getStatus()) && !"archived".equals(targetStatus)) {
            throw conflict("已归档 Agent 不可重新启用");
        }
        if (Boolean.TRUE.equals(definition.getIsSystem()) && "archived".equals(targetStatus)) {
            throw conflict("系统 Agent 不可归档");
        }
        if ("active".equals(targetStatus) && definition.getPublishedVersionId() == null) {
            throw conflict("Agent 没有已发布版本，不能启用");
        }
        LocalDateTime now = LocalDateTime.now();
        if ("active".equals(targetStatus) && Boolean.TRUE.equals(definition.getIsDefault())) {
            definitionMapper.clearOtherDefaults(agentId, principal.id(), now);
        }
        if (!targetStatus.equals(definition.getStatus())) {
            definitionMapper.updateStatus(agentId, targetStatus, principal.id(), now);
        }
        return AgentView.from(requireAgent(agentId), jsonMapper);
    }

    /**
     * 删除{@code delete}。
     *
     * @param agentId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long agentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", agentId, null, "delete"));
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireAgent(agentId);
        if (Boolean.TRUE.equals(definition.getIsSystem())) {
            throw conflict("系统 Agent 不可删除");
        }
        if (!"draft".equals(definition.getStatus()) || definitionMapper.countVersions(agentId) > 0) {
            throw conflict("只有没有版本的草稿 Agent 可以删除；其他 Agent 请归档");
        }
        if (definitionMapper.softDeleteEmptyDraft(agentId, principal.id(), LocalDateTime.now()) != 1) {
            throw conflict("Agent 状态已变化，无法删除");
        }
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 符合条件的数据集合
     */
    public List<AgentVersionView> versions(Long agentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context("agent", agentId, null, "view"));
        requireAgent(agentId);
        return versionMapper.selectVersions(agentId).stream().map(this::toView).toList();
    }

    /**
     * 创建并保存版本。
     *
     * @param agentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView createVersion(Long agentId, SaveAgentVersionRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", null, null, "create")
        );
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireMutableAgent(agentId);
        PreparedVersion prepared = prepareVersion(definition, null, request, principal);
        AgentDefinitionVersion version = prepared.version();
        version.setId(idGenerator.nextId());
        version.setVersionNo(versionMapper.selectNextVersionNo(agentId));
        version.setCreatedBy(principal.id());
        version.setCreatedAt(LocalDateTime.now());
        version.setStatus("draft");
        version.setContentHash(contentHasher.hash(version, prepared.bindings()));
        versionMapper.insertVersion(version);
        insertBindings(version.getId(), prepared.bindings(), version.getCreatedAt());
        return toView(version);
    }

    /**
     * 更新版本。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView updateVersion(
        Long agentId,
        Long versionId,
        SaveAgentVersionRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", versionId, null, "update")
        );
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireMutableAgent(agentId);
        AgentDefinitionVersion existing = requireVersion(agentId, versionId);
        if (!"draft".equals(existing.getStatus())) {
            throw conflict("只有草稿 Agent 版本可以修改");
        }
        PreparedVersion prepared = prepareVersion(definition, existing, request, principal);
        AgentDefinitionVersion version = prepared.version();
        version.setContentHash(contentHasher.hash(version, prepared.bindings()));
        if (versionMapper.updateDraft(version) != 1) {
            throw conflict("Agent 版本已发布、归档或被并发修改");
        }
        deleteBindings(versionId);
        insertBindings(versionId, prepared.bindings(), LocalDateTime.now());
        return toView(version);
    }

    /**
     * 处理clone版本并返回对应结果。
     *
     * @param agentId 资源标识
     * @param sourceVersionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView cloneVersion(Long agentId, Long sourceVersionId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", sourceVersionId, null, "view")
        );
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", null, null, "create")
        );
        definitionMapper.lockAgent(agentId);
        requireMutableAgent(agentId);
        AgentDefinitionVersion source = requireVersion(agentId, sourceVersionId);
        List<AgentVersionBindingRow> bindings = resourceMapper.selectBindings(sourceVersionId);
        AgentDefinitionVersion version = copyVersionContent(source);
        version.setId(idGenerator.nextId());
        version.setVersionNo(versionMapper.selectNextVersionNo(agentId));
        version.setStatus("draft");
        version.setPublishedAt(null);
        version.setCreatedBy(principal.id());
        version.setCreatedAt(LocalDateTime.now());
        version.setContentHash(contentHasher.hash(version, bindings));
        versionMapper.insertVersion(version);
        insertBindings(version.getId(), bindings, version.getCreatedAt());
        return toView(version);
    }

    /**
     * 删除版本。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(Long agentId, Long versionId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", versionId, null, "delete")
        );
        definitionMapper.lockAgent(agentId);
        requireMutableAgent(agentId);
        AgentDefinitionVersion version = requireVersion(agentId, versionId);
        if (!"draft".equals(version.getStatus())) {
            throw conflict("只有草稿 Agent 版本可以删除；已发布和已归档版本作为审计记录保留");
        }
        deleteBindings(versionId);
        if (versionMapper.deleteDraft(agentId, versionId) != 1) {
            throw conflict("Agent 草稿版本已被发布、归档或并发删除");
        }
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionPublishResult publish(Long agentId, Long versionId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", versionId, null, "update")
        );
        definitionMapper.lockAgent(agentId);
        AgentDefinition definition = requireMutableAgent(agentId);
        AgentDefinitionVersion version = requireVersion(agentId, versionId);
        if ("published".equals(version.getStatus())) {
            return new AgentVersionPublishResult(toView(version), true);
        }
        if (!"draft".equals(version.getStatus())) {
            throw conflict("已归档 Agent 版本不能发布");
        }
        List<AgentVersionBindingRow> bindings = resourceMapper.selectBindings(versionId);
        validateCurrentResources(version, bindings, principal);
        String calculatedHash = contentHasher.hash(version, bindings);
        if (!calculatedHash.equals(version.getContentHash())) {
            throw conflict("Agent 草稿内容哈希不一致，拒绝发布");
        }
        versionMapper.archivePreviouslyPublished(agentId, versionId);
        LocalDateTime now = LocalDateTime.now();
        if (versionMapper.publishDraft(agentId, versionId, now) != 1) {
            throw conflict("Agent 版本已被并发修改");
        }
        if (Boolean.TRUE.equals(definition.getIsDefault())) {
            definitionMapper.clearOtherDefaults(agentId, principal.id(), now);
        }
        definitionMapper.updateStatus(agentId, "active", principal.id(), now);
        definitionMapper.updateOnboardingStep(agentId, "complete", principal.id(), now);
        version.setStatus("published");
        version.setPublishedAt(now);
        return new AgentVersionPublishResult(toView(version), false);
    }

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView archiveVersion(Long agentId, Long versionId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal, context("agent_version", versionId, null, "update")
        );
        definitionMapper.lockAgent(agentId);
        requireAgent(agentId);
        AgentDefinitionVersion version = requireVersion(agentId, versionId);
        if ("archived".equals(version.getStatus())) {
            return toView(version);
        }
        boolean wasPublished = "published".equals(version.getStatus());
        if (versionMapper.archiveVersion(agentId, versionId) != 1) {
            throw conflict("Agent 版本已被并发修改");
        }
        if (wasPublished) {
            definitionMapper.updateStatus(agentId, "disabled", principal.id(), LocalDateTime.now());
        }
        version.setStatus("archived");
        return toView(version);
    }

    /**
     * 处理{@code activeConfig}并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    public AgentVersionView activeConfig(Long agentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDefinition definition = requireAgent(agentId);
        if (!"active".equals(definition.getStatus()) || definition.getPublishedVersionId() == null) {
            throw new ServiceException("Agent 没有可用的已发布版本", HttpStatus.NOT_FOUND);
        }
        authorizationEnforcer.requireAllowed(
            principal,
            context("agent_version", definition.getPublishedVersionId(), definition.getAgentKey(), "use")
        );
        return toView(requireVersion(agentId, definition.getPublishedVersionId()));
    }

    /**
     * 处理prepare版本并返回对应结果。
     *
     * @param definition 定义参数
     * @param existing {@code existing}参数
     * @param request 请求参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private PreparedVersion prepareVersion(
        AgentDefinition definition,
        AgentDefinitionVersion existing,
        SaveAgentVersionRequest request,
        CurrentPrincipal principal
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (request.modelId() == null || request.modelId() <= 0) {
            throw badRequest("Agent 主模型不能为空");
        }
        Map<Long, AgentModel> models = lockAndLoadModels(
            request.modelId(), request.synthesisModelId(), principal
        );
        Map<String, Object> runtime = new TreeMap<>(
            configurationValidator.runtimeConfig(request.runtimeConfig())
        );
        runtime.put("engineType", definition.getEngineType());
        runtime.put("engineConfigSnapshot", parseMap(definition.getEngineConfigJson()));
        if (request.modelId() != null) {
            runtime.put("modelSnapshot", modelSnapshot(models.get(request.modelId())));
        }
        if (request.synthesisModelId() != null) {
            runtime.put("synthesisModelSnapshot", modelSnapshot(models.get(request.synthesisModelId())));
        }

        List<AgentVersionBindingRow> bindings = new ArrayList<>();
        bindings.addAll(prepareBindings(
            "tool", configurationValidator.bindings("tool", request.tools()), principal
        ));
        bindings.addAll(prepareBindings(
            "skill", configurationValidator.bindings("skill", request.skills()), principal
        ));
        bindings.addAll(prepareBindings(
            "knowledge_base",
            configurationValidator.bindings("knowledge_base", request.knowledgeBases()),
            principal
        ));

        AgentDefinitionVersion version = existing == null ? new AgentDefinitionVersion() : copy(existing);
        version.setAgentId(definition.getId());
        version.setSystemPrompt(configurationValidator.systemPrompt(request.systemPrompt()));
        version.setModelId(request.modelId());
        version.setSynthesisModelId(request.synthesisModelId());
        version.setRuntimeConfigJson(serialize(runtime, "Agent 运行配置"));
        version.setWelcomeConfigJson(serialize(
            configurationValidator.welcomeConfig(request.welcomeConfig()), "Agent 欢迎配置"
        ));
        version.setRoutingTagsJson(serialize(
            configurationValidator.routingTags(request.routingTags()), "Agent 路由标签"
        ));
        return new PreparedVersion(version, List.copyOf(bindings));
    }

    /**
     * 处理{@code lockAndLoadModels}并返回对应结果。
     *
     * @param modelId 资源标识
     * @param synthesisModelId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private Map<Long, AgentModel> lockAndLoadModels(
        Long modelId,
        Long synthesisModelId,
        CurrentPrincipal principal
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Set<Long> ids = new LinkedHashSet<>();
        if (modelId != null) {
            ids.add(modelId);
        }
        if (synthesisModelId != null) {
            ids.add(synthesisModelId);
        }
        List<Long> ordered = ids.stream().sorted().toList();
        Map<Long, AgentModel> result = new HashMap<>();
        for (Long id : ordered) {
            modelMapper.lockModel(id);
            AgentModel model = modelMapper.selectModelById(id);
            if (model == null || !"active".equals(model.getStatus())) {
                throw conflict("Agent 引用的模型不存在或未启用：" + id);
            }
            if (!Set.of("chat", "multimodal").contains(model.getModelType())) {
                throw conflict("Agent 只能使用对话或多模态模型：" + id);
            }
            authorizationEnforcer.requireAllowed(
                principal, context("model", id, model.getModelKey(), "use")
            );
            result.put(id, model);
        }
        return result;
    }

    /**
     * 处理{@code prepareBindings}并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param bindings {@code bindings}参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private List<AgentVersionBindingRow> prepareBindings(
        String resourceType,
        List<ValidatedBinding> bindings,
        CurrentPrincipal principal
    ) {
        List<AgentVersionBindingRow> result = new ArrayList<>(bindings.size());
        for (ValidatedBinding binding : bindings) {
            authorizationEnforcer.requireAllowed(
                principal,
                context(resourceType, binding.resourceId(), null, binding.permission())
            );
            AgentResourceSnapshotRow resource = selectResource(resourceType, binding.resourceId());
            if (resource == null) {
                throw conflict("Agent 绑定资源不存在、未发布或未启用：" + resourceType + ":" + binding.resourceId());
            }
            Map<String, Object> config = new TreeMap<>();
            config.put("binding", binding.config());
            config.put("resourceSnapshot", parseMap(resource.getSnapshotJson()));
            AgentVersionBindingRow row = new AgentVersionBindingRow();
            row.setResourceType(resourceType);
            row.setResourceId(binding.resourceId());
            row.setPermission(binding.permission());
            row.setConfigJson(serialize(config, "Agent 资源快照"));
            result.add(row);
        }
        return result;
    }

    /**
     * 校验当前Resources，并在条件不满足时终止处理。
     *
     * @param version 版本参数
     * @param bindings {@code bindings}参数
     * @param principal 当前操作主体
     */
    private void validateCurrentResources(
        AgentDefinitionVersion version,
        List<AgentVersionBindingRow> bindings,
        CurrentPrincipal principal
    ) {
        lockAndLoadModels(version.getModelId(), version.getSynthesisModelId(), principal);
        for (AgentVersionBindingRow binding : bindings) {
            authorizationEnforcer.requireAllowed(
                principal,
                context(binding.getResourceType(), binding.getResourceId(), null, binding.getPermission())
            );
            if (selectResource(binding.getResourceType(), binding.getResourceId()) == null) {
                throw conflict(
                    "Agent 绑定资源在发布前已停用："
                        + binding.getResourceType() + ":" + binding.getResourceId()
                );
            }
        }
    }

    /**
     * 获取资源。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @return 处理结果
     */
    private AgentResourceSnapshotRow selectResource(String resourceType, Long resourceId) {
        return switch (resourceType) {
            case "tool" -> resourceMapper.selectToolSnapshot(resourceId);
            case "skill" -> resourceMapper.selectSkillSnapshot(resourceId);
            case "knowledge_base" -> resourceMapper.selectKnowledgeSnapshot(resourceId);
            default -> throw conflict("Agent 版本包含未知资源类型");
        };
    }

    /**
     * 处理模型快照并返回对应结果。
     *
     * @param model 模型参数
     * @return 处理结果
     */
    private Map<String, Object> modelSnapshot(AgentModel model) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("modelId", model.getId());
        snapshot.put("modelKey", model.getModelKey());
        snapshot.put("provider", model.getProviderType());
        snapshot.put("modelName", model.getModelName());
        snapshot.put("modelType", model.getModelType());
        snapshot.put("endpointUrl", model.getEndpointUrl());
        snapshot.put("credentialRef", "db:model:" + model.getId());
        if (model.getContextSize() != null) {
            snapshot.put("contextSize", model.getContextSize());
        }
        if (model.getMaxOutputTokens() != null) {
            snapshot.put("maxOutputTokens", model.getMaxOutputTokens());
        }
        snapshot.put("reasoningConfig", parseMap(model.getReasoningConfigJson()));
        snapshot.put("capabilities", parseMap(model.getCapabilityJson()));
        return snapshot;
    }

    /**
     * 创建并保存{@code Bindings}。
     *
     * @param versionId 资源标识
     * @param bindings {@code bindings}参数
     * @param createdAt {@code createdAt}参数
     */
    private void insertBindings(
        Long versionId,
        List<AgentVersionBindingRow> bindings,
        LocalDateTime createdAt
    ) {
        for (AgentVersionBindingRow binding : bindings) {
            Long id = idGenerator.nextId();
            binding.setId(id);
            switch (binding.getResourceType()) {
                case "tool" -> resourceMapper.insertToolBinding(
                    id, versionId, binding.getResourceId(), binding.getPermission(),
                    binding.getConfigJson(), createdAt
                );
                case "skill" -> resourceMapper.insertSkillBinding(
                    id, versionId, binding.getResourceId(), binding.getPermission(),
                    binding.getConfigJson(), createdAt
                );
                case "knowledge_base" -> resourceMapper.insertKnowledgeBinding(
                    id, versionId, binding.getResourceId(), binding.getPermission(),
                    binding.getConfigJson(), createdAt
                );
                default -> throw new IllegalStateException("unsupported binding resource type");
            }
        }
    }

    /**
     * 删除{@code Bindings}。
     *
     * @param versionId 资源标识
     */
    private void deleteBindings(Long versionId) {
        resourceMapper.deleteToolBindings(versionId);
        resourceMapper.deleteSkillBindings(versionId);
        resourceMapper.deleteKnowledgeBindings(versionId);
    }

    /**
     * 将输入数据转换为{@code View}。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    private AgentVersionView toView(AgentDefinitionVersion version) {
        return AgentVersionView.from(version, resourceMapper.selectBindings(version.getId()), jsonMapper);
    }

    /**
     * 校验智能体，并在条件不满足时终止处理。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    private AgentDefinition requireAgent(Long agentId) {
        AgentDefinition definition = definitionMapper.selectDefinitionById(agentId);
        if (definition == null) {
            throw new ServiceException("Agent 不存在", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    /**
     * 校验Mutable智能体，并在条件不满足时终止处理。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    private AgentDefinition requireMutableAgent(Long agentId) {
        AgentDefinition definition = requireAgent(agentId);
        if ("archived".equals(definition.getStatus())) {
            throw conflict("已归档 Agent 不可变更版本");
        }
        return definition;
    }

    /**
     * 校验版本，并在条件不满足时终止处理。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private AgentDefinitionVersion requireVersion(Long agentId, Long versionId) {
        AgentDefinitionVersion version = versionMapper.selectVersion(agentId, versionId);
        if (version == null) {
            throw new ServiceException("Agent 版本不存在", HttpStatus.NOT_FOUND);
        }
        return version;
    }

    /**
     * 处理apply定义相关逻辑。
     *
     * @param definition 定义参数
     * @param name 名称
     * @param description {@code description}参数
     * @param agentType 业务类型
     * @param engineType 业务类型
     * @param avatarUrl {@code avatarUrl}参数
     * @param defaultAgent default智能体参数
     * @param sortOrder {@code sortOrder}参数
     * @param engineConfig {@code engineConfig}参数
     */
    private void applyDefinition(
        AgentDefinition definition,
        String name,
        String description,
        String agentType,
        String engineType,
        String avatarUrl,
        boolean defaultAgent,
        int sortOrder,
        Map<String, Object> engineConfig
    ) {
        if (name == null || name.isBlank() || name.strip().length() > 128) {
            throw badRequest("Agent 名称无效");
        }
        if (description != null && description.length() > 4000) {
            throw badRequest("Agent 描述超过长度限制");
        }
        if (sortOrder < -10_000 || sortOrder > 10_000) {
            throw badRequest("Agent 排序值超出范围");
        }
        definition.setName(name.strip());
        definition.setDescription(description == null || description.isBlank() ? null : description.strip());
        definition.setAgentType(configurationValidator.agentType(agentType));
        definition.setEngineType(configurationValidator.engineType(engineType));
        definition.setAvatarUrl(configurationValidator.avatarUrl(avatarUrl));
        definition.setIsDefault(defaultAgent);
        definition.setSortOrder(sortOrder);
        definition.setEngineConfigJson(serialize(
            configurationValidator.engineConfig(definition.getEngineType(), engineConfig), "Agent 引擎配置"
        ));
    }

    /**
     * 处理{@code copy}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private AgentDefinitionVersion copy(AgentDefinitionVersion source) {
        AgentDefinitionVersion target = new AgentDefinitionVersion();
        target.setId(source.getId());
        target.setAgentId(source.getAgentId());
        target.setVersionNo(source.getVersionNo());
        target.setStatus(source.getStatus());
        target.setPublishedAt(source.getPublishedAt());
        target.setCreatedBy(source.getCreatedBy());
        target.setCreatedAt(source.getCreatedAt());
        return target;
    }

    /**
     * 处理copy版本Content并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private AgentDefinitionVersion copyVersionContent(AgentDefinitionVersion source) {
        AgentDefinitionVersion target = new AgentDefinitionVersion();
        target.setAgentId(source.getAgentId());
        target.setSystemPrompt(source.getSystemPrompt());
        target.setModelId(source.getModelId());
        target.setSynthesisModelId(source.getSynthesisModelId());
        target.setRuntimeConfigJson(source.getRuntimeConfigJson());
        target.setWelcomeConfigJson(source.getWelcomeConfigJson());
        target.setRoutingTagsJson(source.getRoutingTagsJson());
        return target;
    }

    /**
     * 处理{@code serialize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String serialize(Object value, String label) {
        String json = jsonMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw badRequest(label + "超过 64KB 限制");
        }
        return json;
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMap(String value) {
        return value == null || value.isBlank() ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value) {
        if (value instanceof Number number && number.longValue() > 0
            && number.doubleValue() == number.longValue()) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private PermissionContext context(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action
    ) {
        return new PermissionContext(
            resourceType, resourceId, resourceKey, action,
            ResourceState.ACTIVE, true, Set.of(), null
        );
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
        if (normalized.length() > 128) {
            throw badRequest("Agent 搜索条件超过 128 字符限制");
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
     * 封装Prepared版本相关的不可变数据。
     */
    private record PreparedVersion(
        AgentDefinitionVersion version,
        List<AgentVersionBindingRow> bindings
    ) {
    }
}
