package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.skill.domain.AgentSkill;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.mapper.SkillCatalogMapper;
import group.aitools.nhs.platform.skill.web.CreateSkillRequest;
import group.aitools.nhs.platform.skill.web.CreateSkillVersionRequest;
import group.aitools.nhs.platform.skill.web.SkillVersionView;
import group.aitools.nhs.platform.skill.web.SkillView;
import group.aitools.nhs.platform.skill.web.UpdateSkillStatusRequest;
import group.aitools.nhs.platform.skill.web.UpdateSkillRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 负责技能目录相关的业务编排与领域规则处理。
 * Personal, project and system Skill publication service. */
@Service
public class SkillCatalogService {

    private static final Pattern SKILL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Set<String> SCOPES = Set.of("system", "project", "user");
    private static final Set<String> MANIFEST_KEYS = Set.of(
        "summary", "tags", "parameters", "compatibleAgentTypes", "requiredToolKeys"
    );
    private static final Set<String> RUNTIME_KEYS = Set.of(
        "requiredToolIds", "requiredKnowledgeBaseIds", "maxContextBytes", "workspaceAccess",
        "dependencies"
    );
    private static final int MAX_CONTENT_BYTES = 32 * 1024;

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final SkillCatalogMapper mapper;
    private final SkillFileMapper fileMapper;
    private final AgentProjectMapper projectMapper;
    private final ConnectorConfigurationValidator documentValidator;
    private final JsonMapper jsonMapper;
    /**
 * 创建 {@code SkillCatalogService} 实例并初始化所需依赖。
 * Frozen runtime calls do not have an HTTP login context; keep their actor call-scoped. */
    private final ThreadLocal<CurrentPrincipal> runtimePrincipal = new ThreadLocal<>();

    public SkillCatalogService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        SkillCatalogMapper mapper,
        SkillFileMapper fileMapper,
        AgentProjectMapper projectMapper,
        ConnectorConfigurationValidator documentValidator,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.fileMapper = fileMapper;
        this.projectMapper = projectMapper;
        this.documentValidator = documentValidator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 执行As运行时操作主体相关的处理流程。
     *
     * @param principal 当前操作主体
     * @param operation 操作参数
     * @return 处理结果
     */
    public <T> T runAsRuntimePrincipal(CurrentPrincipal principal, Supplier<T> operation) {
        if (principal == null || operation == null) {
            throw new IllegalArgumentException("运行时主体和操作不能为空");
        }
        if (runtimePrincipal.get() != null) {
            throw new IllegalStateException("运行时 Skill 主体不能嵌套覆盖");
        }
        runtimePrincipal.set(principal);
        try {
            return operation.get();
        } finally {
            runtimePrincipal.remove();
        }
    }

    /**
     * 查询{@code list}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<SkillView> list(
        String scopeType,
        Long scopeId,
        String search,
        boolean includeInactive,
        int limit
    ) {
        CurrentPrincipal principal = currentPrincipal();
        String scope = optionalScope(scopeType);
        validateScopeFilter(scope, scopeId);
        return mapper.selectVisibleSkills(
                principal.id(), principal.hasRole(PlatformRole.PLATFORM_ADMIN), scope, scopeId,
                normalizeSearch(search), includeInactive, limit
            ).stream()
            .filter(skill -> can(principal, skill, "view"))
            .map(skill -> SkillView.from(skill, jsonMapper))
            .toList();
    }

    /**
     * 处理{@code available}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<SkillView> available(String scopeType, Long scopeId, String search, int limit) {
        CurrentPrincipal principal = currentPrincipal();
        String scope = optionalScope(scopeType);
        validateScopeFilter(scope, scopeId);
        return mapper.selectVisibleSkills(
                principal.id(), principal.hasRole(PlatformRole.PLATFORM_ADMIN), scope, scopeId,
                normalizeSearch(search), false, limit
            ).stream()
            .filter(skill -> canUse(principal, skill))
            .map(skill -> SkillView.from(skill, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    public SkillView get(Long skillId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "view");
        return SkillView.from(skill, jsonMapper);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillView create(CreateSkillRequest request) {
        CurrentPrincipal principal = currentPrincipal();
        String key = normalizeKey(request.skillKey());
        PreparedScope scope = prepareScope(principal, request.scopeType(), request.scopeId());
        authorizationEnforcer.requireAllowed(
            principal, context(null, key, "create", scope.relations())
        );
        LocalDateTime now = LocalDateTime.now();
        AgentSkill skill = new AgentSkill();
        skill.setId(idGenerator.nextId());
        skill.setSkillKey(key);
        skill.setName(requiredText(request.name(), 128, "技能名称"));
        skill.setDescription(optionalText(request.description(), 12000));
        skill.setScopeType(scope.scopeType());
        skill.setScopeId(scope.scopeId());
        skill.setOwnerId(principal.id());
        skill.setStatus("draft");
        skill.setRevisionNo(1L);
        skill.setCreateBy(principal.id());
        skill.setCreateTime(now);
        skill.setDelFlag("0");
        skill.setExtraJson("{}");
        AgentSkillVersion version = version(
            skill.getId(), 1, request.content(), request.manifest(),
            request.runtimeRequirements(), principal.id(), now
        );
        try {
            mapper.insertSkill(skill);
            mapper.insertVersion(version);
            seedSkillMarkdown(version, principal.id(), now);
        } catch (DuplicateKeyException exception) {
            throw conflict("技能标识已存在：" + key);
        }
        return SkillView.from(skill, jsonMapper);
    }

    /**
     * 更新{@code update}。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillView update(Long skillId, UpdateSkillRequest request) {
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "update");
        requireRevision(skill, request.expectedRevision());
        if ("archived".equals(skill.getStatus())) {
            throw conflict("已归档技能不可修改");
        }
        skill.setName(requiredText(request.name(), 128, "技能名称"));
        skill.setDescription(optionalText(request.description(), 12000));
        skill.setUpdateBy(principal.id());
        skill.setUpdateTime(LocalDateTime.now());
        if (mapper.updateSkill(skill) != 1) {
            throw conflict("技能已被其他请求修改");
        }
        skill.setRevisionNo(skill.getRevisionNo() + 1);
        return SkillView.from(skill, jsonMapper);
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 符合条件的数据集合
     */
    public List<SkillVersionView> versions(Long skillId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "view");
        return mapper.selectVersions(skillId).stream()
            .map(version -> SkillVersionView.from(version, jsonMapper))
            .toList();
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    public SkillVersionView version(Long skillId, Long versionId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "view");
        return SkillVersionView.from(requireVersion(skillId, versionId), jsonMapper);
    }

    /**
     * 创建并保存版本。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionView createVersion(Long skillId, CreateSkillVersionRequest request) {
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "update");
        requireRevision(skill, request.expectedRevision());
        if ("archived".equals(skill.getStatus())) {
            throw conflict("已归档技能不能创建版本");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentSkillVersion version = version(
            skillId, mapper.selectNextVersionNo(skillId), request.content(), request.manifest(),
            request.runtimeRequirements(), principal.id(), now
        );
        if (mapper.touchSkill(skillId, skill.getRevisionNo(), principal.id(), now) != 1) {
            throw conflict("技能已被其他请求修改");
        }
        mapper.insertVersion(version);
        seedSkillMarkdown(version, principal.id(), now);
        return SkillVersionView.from(version, jsonMapper);
    }

    /**
 * 处理clone版本并返回对应结果。
 * Copies any immutable or editable version into a new draft, including its complete file bundle. */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionView cloneVersion(Long skillId, Long sourceVersionId, Long expectedRevision) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "update");
        requireRevision(skill, expectedRevision);
        if ("archived".equals(skill.getStatus())) {
            throw conflict("已归档技能不能复制版本");
        }
        AgentSkillVersion source = requireVersion(skillId, sourceVersionId);
        if (!hash(source).equals(source.getContentHash())) {
            throw conflict("源技能版本内容哈希不一致，拒绝复制");
        }
        List<AgentSkillFile> sourceFiles = fileMapper.selectFiles(skillId, sourceVersionId);
        String bundleHash = sourceFiles.isEmpty() ? null : fileBundleHash(skillId, sourceVersionId);
        if (source.getFileBundleHash() != null && !source.getFileBundleHash().equals(bundleHash)) {
            throw conflict("源技能版本文件包哈希不一致，拒绝复制");
        }

        LocalDateTime now = LocalDateTime.now();
        AgentSkillVersion draft = cloneDraft(source, mapper.selectNextVersionNo(skillId), principal.id(), now);
        draft.setFileBundleHash(bundleHash);
        if (mapper.touchSkill(skillId, skill.getRevisionNo(), principal.id(), now) != 1) {
            throw conflict("技能已被其他请求修改");
        }
        mapper.insertVersion(draft);
        if (sourceFiles.isEmpty()) {
            seedSkillMarkdown(draft, principal.id(), now);
        } else {
            sourceFiles.forEach(file -> fileMapper.upsert(cloneFile(file, draft.getId(), principal.id(), now)));
        }
        return SkillVersionView.from(draft, jsonMapper);
    }

    /**
     * 删除版本。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(Long skillId, Long versionId, Long expectedRevision) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "delete");
        requireRevision(skill, expectedRevision);
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if (!"draft".equals(version.getStatus()) || version.getPublishedAt() != null) {
            throw conflict("只有未发布的草稿 Skill 版本可以删除；发布和归档记录必须保留");
        }
        if (mapper.countBlockingVersionPublicationReferences(versionId) > 0) {
            throw conflict("Skill 草稿版本正在审核或已形成公开快照，不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.touchSkill(skillId, skill.getRevisionNo(), principal.id(), now) != 1) {
            throw conflict("技能已被其他请求修改");
        }
        fileMapper.deleteVersionFiles(skillId, versionId);
        if (mapper.deleteDraftVersion(skillId, versionId) != 1) {
            throw conflict("Skill 草稿版本已被发布、归档或并发删除");
        }
    }

    /**
     * 更新{@code Status}。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillView updateStatus(Long skillId, UpdateSkillStatusRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "update");
        requireRevision(skill, request.expectedRevision());
        String expected = lifecycleStatus(request.expectedStatus(), "原技能状态");
        String status = lifecycleStatus(request.status(), "技能状态");
        if (!expected.equals(skill.getStatus())) {
            throw conflict("技能状态已被其他请求修改");
        }
        if (expected.equals(status)) {
            return SkillView.from(skill, jsonMapper);
        }
        if ("active".equals(status) && skill.getPublishedVersionId() == null) {
            throw conflict("Skill 没有已发布版本，不能启用");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateSkillStatus(skillId, skill.getRevisionNo(), status, principal.id(), now) != 1) {
            throw conflict("技能状态已被其他请求修改");
        }
        skill.setStatus(status);
        skill.setRevisionNo(skill.getRevisionNo() + 1);
        skill.setUpdateBy(principal.id());
        skill.setUpdateTime(now);
        return SkillView.from(skill, jsonMapper);
    }

    /**
 * 删除{@code delete}。
 * Removes an unpublished Skill family only when no live consumer or publication needs it. */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long skillId, Long expectedRevision) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "delete");
        requireRevision(skill, expectedRevision);
        if (mapper.countPublishedVersions(skillId) > 0) {
            throw conflict("Skill 存在已发布版本，不能删除；可先停用以阻止继续使用");
        }
        if (mapper.countActiveReferences(skillId) > 0) {
            throw conflict("Skill 仍被草稿或已发布 Agent 版本引用，不能删除");
        }
        if (mapper.countBlockingPublicationReferences(skillId) > 0) {
            throw conflict("Skill 存在待审核或已公开的发布申请，不能删除");
        }
        fileMapper.deleteSkillFiles(skillId);
        mapper.deleteUnpublishedVersions(skillId);
        if (mapper.softDeleteUnpublishedSkill(
            skillId, skill.getRevisionNo(), principal.id(), LocalDateTime.now()
        ) != 1) {
            throw conflict("Skill 已被发布、归档或并发删除");
        }
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionView publish(Long skillId, Long versionId, Long expectedRevision) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "publish");
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if ("published".equals(version.getStatus())) {
            return SkillVersionView.from(version, jsonMapper);
        }
        requireRevision(skill, expectedRevision);
        if (!"draft".equals(version.getStatus())) {
            throw conflict("只有草稿技能版本可以发布");
        }
        if (!hash(version).equals(version.getContentHash())) {
            throw conflict("技能版本内容哈希不一致，拒绝发布");
        }
        AgentSkillFile skillFile = fileMapper.selectFile(skillId, versionId, "SKILL.md");
        if (skillFile == null || skillFile.getContent() == null || skillFile.getContent().isBlank()) {
            throw badRequest("技能版本必须包含非空的 SKILL.md 文件");
        }
        String bundleHash = fileBundleHash(skillId, versionId);
        if (version.getFileBundleHash() == null) {
            fileMapper.refreshBundleHash(skillId, versionId, bundleHash);
            version.setFileBundleHash(bundleHash);
        } else if (!bundleHash.equals(version.getFileBundleHash())) {
            throw conflict("技能文件包哈希不一致，拒绝发布");
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.archivePreviouslyPublished(skillId, versionId);
        if (mapper.publishDraft(skillId, versionId, now) != 1
            || mapper.updateSkillStatus(
                skillId, skill.getRevisionNo(), "active", principal.id(), now
            ) != 1) {
            throw conflict("技能发布状态发生并发变化");
        }
        version.setStatus("published");
        version.setPublishedAt(now);
        return SkillVersionView.from(version, jsonMapper);
    }

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionView archiveVersion(Long skillId, Long versionId, Long expectedRevision) {
        CurrentPrincipal principal = currentPrincipal();
        mapper.lockSkill(skillId);
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "archive");
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if ("archived".equals(version.getStatus())) {
            return SkillVersionView.from(version, jsonMapper);
        }
        requireRevision(skill, expectedRevision);
        boolean published = "published".equals(version.getStatus());
        LocalDateTime now = LocalDateTime.now();
        if (mapper.archiveVersion(skillId, versionId) != 1) {
            throw conflict("技能版本状态发生并发变化");
        }
        String rootStatus = published ? "disabled" : skill.getStatus();
        if (mapper.updateSkillStatus(
            skillId, skill.getRevisionNo(), rootStatus, principal.id(), now
        ) != 1) {
            throw conflict("技能状态发生并发变化");
        }
        version.setStatus("archived");
        return SkillVersionView.from(version, jsonMapper);
    }

    /**
 * 校验文件Access，并在条件不满足时终止处理。
 *
     * Validates access to a Skill version file. Files are mutable only while
     * the owning version is a draft; published and archived bundles are read
     * only and remain available to principals that can view the Skill.
     */
    public void requireFileAccess(Long skillId, Long versionId, boolean write) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, write ? "update" : "view");
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if (write && (!"draft".equals(version.getStatus()) || "archived".equals(skill.getStatus()))) {
            throw conflict("已发布或已归档的技能版本不可修改，请创建新版本");
        }
    }

    /**
 * 校验运行时Access，并在条件不满足时终止处理。
 *
     * Dependency installation changes the local runtime cache and therefore requires the same
     * owner/project-admin/update permission as editing a Skill.  Published versions remain
     * immutable; this check only authorizes the explicit cache operation.
     */
    public void requireRuntimeAccess(Long skillId, Long versionId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        require(principal, skill, "update");
        requireVersion(skillId, versionId);
    }

    /**
 * 处理latestEditable版本Id并返回对应结果。
 * Returns the newest draft version used by the personal Skill aliases. */
    public Long latestEditableVersionId(Long skillId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        requirePersonalOwner(principal, skill);
        require(principal, skill, "update");
        Long versionId = mapper.selectLatestEditableVersionId(skillId);
        if (versionId == null) {
            throw conflict("个人技能没有可编辑的草稿版本，请先创建新版本");
        }
        return versionId;
    }

    /**
     * 处理latest版本Id并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    public Long latestVersionId(Long skillId) {
        CurrentPrincipal principal = currentPrincipal();
        AgentSkill skill = requireSkill(skillId);
        requirePersonalOwner(principal, skill);
        require(principal, skill, "view");
        Long versionId = mapper.selectLatestVersionId(skillId);
        if (versionId == null) {
            throw new ServiceException("技能版本不存在", HttpStatus.NOT_FOUND);
        }
        return versionId;
    }

    /**
 * 处理synchronize技能Markdown并返回对应结果。
 * Synchronizes the editable SKILL.md file with the draft version's runtime content. */
    @Transactional(rollbackFor = Exception.class)
    public String synchronizeSkillMarkdown(Long skillId, Long versionId, String markdown) {
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if (!"draft".equals(version.getStatus())) {
            throw conflict("已发布或已归档的技能版本不可修改，请创建新版本");
        }
        String normalized = content(markdown);
        version.setContent(normalized);
        if (mapper.updateDraftContent(skillId, versionId, normalized, hash(version)) != 1) {
            throw conflict("技能主文件已被其他请求修改");
        }
        return normalized;
    }

    /**
     * 校验{@code PersonalOwner}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param skill 技能参数
     */
    private void requirePersonalOwner(CurrentPrincipal principal, AgentSkill skill) {
        if (!"user".equals(skill.getScopeType()) || !principal.id().equals(skill.getOwnerId())) {
            throw new ServiceException("只能操作当前用户的个人技能", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionNo 版本No参数
     * @param content 待处理内容
     * @param manifest {@code manifest}参数
     * @param runtimeRequirements 运行时Requirements参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentSkillVersion version(
        Long skillId,
        int versionNo,
        String content,
        Map<String, Object> manifest,
        Map<String, Object> runtimeRequirements,
        Long actorId,
        LocalDateTime now
    ) {
        String normalizedContent = content(content);
        Map<String, Object> normalizedManifest = documentValidator.document(
            manifest, MANIFEST_KEYS, "技能 Manifest"
        );
        Map<String, Object> normalizedRuntime = documentValidator.document(
            runtimeRequirements, RUNTIME_KEYS, "技能运行要求"
        );
        if (normalizedRuntime.containsKey("dependencies")) {
            Map<String, Object> checkedDependencies = SkillDependencySpec.normalize(
                normalizedRuntime.get("dependencies")
            );
            normalizedRuntime = new java.util.LinkedHashMap<>(normalizedRuntime);
            normalizedRuntime.put("dependencies", checkedDependencies);
            normalizedRuntime = Map.copyOf(normalizedRuntime);
        }
        AgentSkillVersion version = new AgentSkillVersion();
        version.setId(idGenerator.nextId());
        version.setSkillId(skillId);
        version.setVersionNo(versionNo);
        version.setContent(normalizedContent);
        version.setManifestJson(documentValidator.boundedJson(normalizedManifest, "技能 Manifest"));
        version.setRuntimeRequirementsJson(
            documentValidator.boundedJson(normalizedRuntime, "技能运行要求")
        );
        version.setStatus("draft");
        version.setCreatedBy(actorId);
        version.setCreatedAt(now);
        version.setContentHash(hash(version));
        return version;
    }

    /**
     * 处理{@code cloneDraft}并返回对应结果。
     *
     * @param source 数据源参数
     * @param versionNo 版本No参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentSkillVersion cloneDraft(
        AgentSkillVersion source,
        int versionNo,
        Long actorId,
        LocalDateTime now
    ) {
        AgentSkillVersion draft = new AgentSkillVersion();
        draft.setId(idGenerator.nextId());
        draft.setSkillId(source.getSkillId());
        draft.setVersionNo(versionNo);
        draft.setContent(source.getContent());
        draft.setContentHash(source.getContentHash());
        draft.setManifestJson(source.getManifestJson());
        draft.setRuntimeRequirementsJson(source.getRuntimeRequirementsJson());
        draft.setStatus("draft");
        draft.setCreatedBy(actorId);
        draft.setCreatedAt(now);
        return draft;
    }

    /**
     * 处理clone文件并返回对应结果。
     *
     * @param source 数据源参数
     * @param versionId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentSkillFile cloneFile(AgentSkillFile source, Long versionId, Long actorId, LocalDateTime now) {
        AgentSkillFile file = new AgentSkillFile();
        file.setId(idGenerator.nextId());
        file.setSkillId(source.getSkillId());
        file.setVersionId(versionId);
        file.setPath(source.getPath());
        file.setFileKind(source.getFileKind());
        file.setContent(source.getContent());
        file.setContentBytes(source.getContentBytes() == null ? null : source.getContentBytes().clone());
        file.setContentEncoding(source.getContentEncoding());
        file.setContentHash(source.getContentHash());
        file.setSizeBytes(source.getSizeBytes());
        file.setCreateBy(actorId);
        file.setCreateTime(now);
        file.setDelFlag("0");
        return file;
    }

    /**
 * 处理seed技能Markdown相关逻辑。
 * Keep the editor's primary instruction and the file bundle in sync. */
    private void seedSkillMarkdown(AgentSkillVersion version, Long actorId, LocalDateTime now) {
        AgentSkillFile file = new AgentSkillFile();
        file.setId(idGenerator.nextId());
        file.setSkillId(version.getSkillId());
        file.setVersionId(version.getId());
        file.setPath("SKILL.md");
        file.setFileKind("file");
        file.setContent(version.getContent());
        file.setContentBytes(null);
        file.setContentEncoding("utf8");
        file.setContentHash(ContentHashing.sha256(version.getContent()));
        file.setSizeBytes(version.getContent().getBytes(StandardCharsets.UTF_8).length);
        file.setCreateBy(actorId);
        file.setCreateTime(now);
        file.setDelFlag("0");
        fileMapper.upsert(file);
        String bundleHash = ContentHashing.sha256("SKILL.md\nfile\n" + file.getContentHash());
        fileMapper.refreshBundleHash(version.getSkillId(), version.getId(), bundleHash);
        version.setFileBundleHash(bundleHash);
    }

    /**
     * 判断{@code h}是否满足要求。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    private String hash(AgentSkillVersion version) {
        return ContentHashing.sha256(
            version.getContent() + "\n" + version.getManifestJson() + "\n"
                + version.getRuntimeRequirementsJson()
        );
    }

    /**
     * 处理文件BundleHash并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private String fileBundleHash(Long skillId, Long versionId) {
        String material = fileMapper.selectFiles(skillId, versionId).stream()
            .filter(file -> "0".equals(file.getDelFlag()))
            .sorted(Comparator.comparing(AgentSkillFile::getPath))
            .map(file -> file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash())
            .collect(Collectors.joining("\n"));
        return ContentHashing.sha256(material);
    }

    /**
     * 处理{@code lifecycleStatus}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String lifecycleStatus(String value, String label) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("active", "disabled").contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理prepare范围并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    private PreparedScope prepareScope(CurrentPrincipal principal, String scopeType, Long scopeId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String scope = requiredScope(scopeType);
        if ("system".equals(scope)) {
            if (scopeId != null) {
                throw badRequest("系统技能不能配置 scopeId");
            }
            return new PreparedScope(scope, null, Set.of());
        }
        if (scopeId == null) {
            throw badRequest("个人或项目技能必须配置 scopeId");
        }
        if ("user".equals(scope)) {
            if (!principal.id().equals(scopeId)) {
                throw new ServiceException("个人技能只能创建在当前用户作用域", HttpStatus.FORBIDDEN);
            }
            return new PreparedScope(scope, scopeId, Set.of(BusinessRelation.OWNER));
        }
        AgentProject project = projectMapper.selectProject(scopeId);
        if (project == null || !"active".equals(project.getStatus())) {
            throw conflict("项目不存在或未启用");
        }
        return new PreparedScope(scope, scopeId, projectRelations(project, principal));
    }

    /**
     * 处理{@code relations}并返回对应结果。
     *
     * @param skill 技能参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> relations(AgentSkill skill, CurrentPrincipal principal) {
        if ("user".equals(skill.getScopeType()) && principal.id().equals(skill.getOwnerId())) {
            return Set.of(BusinessRelation.OWNER);
        }
        if ("project".equals(skill.getScopeType())) {
            AgentProject project = projectMapper.selectProject(skill.getScopeId());
            return project == null ? Set.of() : projectRelations(project, principal);
        }
        return Set.of();
    }

    /**
     * 处理项目Relations并返回对应结果。
     *
     * @param project 项目参数
     * @param principal 当前操作主体
     * @return 符合条件的数据集合
     */
    private Set<BusinessRelation> projectRelations(AgentProject project, CurrentPrincipal principal) {
        if (principal.id().equals(project.getOwnerId())) {
            return Set.of(BusinessRelation.OWNER, BusinessRelation.PROJECT_ADMIN);
        }
        AgentProjectMember member = projectMapper.selectActiveMember(project.getId(), principal.id());
        if (member == null) {
            return Set.of();
        }
        if (Set.of("owner", "manager").contains(member.getMemberRole())) {
            return Set.of(BusinessRelation.PROJECT_ADMIN);
        }
        return "viewer".equals(member.getMemberRole())
            ? Set.of(BusinessRelation.WATCHER) : Set.of(BusinessRelation.COLLABORATOR);
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param skill 技能参数
     * @param action {@code action}参数
     */
    private void require(CurrentPrincipal principal, AgentSkill skill, String action) {
        authorizationEnforcer.requireAllowed(
            principal, context(skill.getId(), skill.getSkillKey(), action, relations(skill, principal))
        );
    }

    /**
     * 判断{@code can}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param skill 技能参数
     * @param action {@code action}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean can(CurrentPrincipal principal, AgentSkill skill, String action) {
        return authorizationEnforcer.decide(
            principal, context(skill.getId(), skill.getSkillKey(), action, relations(skill, principal))
        ).allowed();
    }

    /**
     * 判断{@code Use}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param skill 技能参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canUse(CurrentPrincipal principal, AgentSkill skill) {
        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal, context(skill.getId(), skill.getSkillKey(), "use", relations(skill, principal))
        );
        return decision.effect() == PermissionEffect.ALLOW
            || decision.effect() == PermissionEffect.APPROVAL_REQUIRED;
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param id 资源标识
     * @param key {@code key}参数
     * @param action {@code action}参数
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private PermissionContext context(
        Long id,
        String key,
        String action,
        Set<BusinessRelation> relations
    ) {
        return new PermissionContext(
            "skill", id, key, action, ResourceState.ACTIVE, true, relations, null
        );
    }

    /**
     * 校验技能，并在条件不满足时终止处理。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    private AgentSkill requireSkill(Long skillId) {
        AgentSkill skill = mapper.selectSkill(skillId);
        if (skill == null) {
            throw new ServiceException("技能不存在", HttpStatus.NOT_FOUND);
        }
        return skill;
    }

    /**
     * 校验版本，并在条件不满足时终止处理。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private AgentSkillVersion requireVersion(Long skillId, Long versionId) {
        AgentSkillVersion version = mapper.selectVersion(skillId, versionId);
        if (version == null) {
            throw new ServiceException("技能版本不存在", HttpStatus.NOT_FOUND);
        }
        return version;
    }

    /**
     * 校验{@code Revision}，并在条件不满足时终止处理。
     *
     * @param skill 技能参数
     * @param expectedRevision {@code expectedRevision}参数
     */
    private void requireRevision(AgentSkill skill, Long expectedRevision) {
        if (!expectedRevision.equals(skill.getRevisionNo())) {
            throw conflict("技能已被其他请求修改");
        }
    }

    /**
     * 处理{@code content}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String content(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw badRequest("技能内容不能为空或包含非法字符");
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw badRequest("技能内容超过 32KB 限制");
        }
        return normalized;
    }

    /**
     * 校验范围Filter，并在条件不满足时终止处理。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void validateScopeFilter(String scopeType, Long scopeId) {
        if (scopeId != null && scopeType == null) {
            throw badRequest("按 scopeId 筛选时必须提供 scopeType");
        }
        if ("system".equals(scopeType) && scopeId != null) {
            throw badRequest("系统技能没有 scopeId");
        }
    }

    /**
     * 处理{@code normalizeKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeKey(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!SKILL_KEY.matcher(normalized).matches()) {
            throw badRequest("技能标识格式无效");
        }
        return normalized;
    }

    /**
     * 校验范围，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredScope(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(normalized)) {
            throw badRequest("技能作用域无效");
        }
        return normalized;
    }

    /**
     * 处理optional范围并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalScope(String value) {
        return value == null || value.isBlank() ? null : requiredScope(value);
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        return value == null || value.isBlank() ? null : requiredText(value, 128, "搜索条件");
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maxLength, String label) {
        String normalized = optionalText(value, maxLength);
        if (normalized == null) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest("文本内容无效或超过长度限制");
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
     * 处理当前操作主体并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal currentPrincipal() {
        CurrentPrincipal principal = runtimePrincipal.get();
        return principal == null ? principalProvider.currentPrincipal() : principal;
    }

    /**
     * 封装Prepared范围相关的不可变数据。
     */
    private record PreparedScope(
        String scopeType,
        Long scopeId,
        Set<BusinessRelation> relations
    ) {
    }
}
