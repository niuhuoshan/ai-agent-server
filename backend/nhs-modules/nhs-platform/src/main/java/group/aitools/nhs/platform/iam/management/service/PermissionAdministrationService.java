package group.aitools.nhs.platform.iam.management.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.management.domain.PermissionCopyRecord;
import group.aitools.nhs.platform.iam.management.domain.PermissionProfile;
import group.aitools.nhs.platform.iam.management.domain.PermissionProfileEntry;
import group.aitools.nhs.platform.iam.management.domain.TemporaryGrant;
import group.aitools.nhs.platform.iam.management.domain.UserPermissionBinding;
import group.aitools.nhs.platform.iam.management.domain.UserPermissionOverride;
import group.aitools.nhs.platform.iam.management.mapper.PermissionAdministrationMapper;
import group.aitools.nhs.platform.iam.management.web.CopyPermissionRequest;
import group.aitools.nhs.platform.iam.management.web.CreatePermissionProfileRequest;
import group.aitools.nhs.platform.iam.management.web.CreatePermissionProfileVersionRequest;
import group.aitools.nhs.platform.iam.management.web.CreateTemporaryGrantRequest;
import group.aitools.nhs.platform.iam.management.web.PatchPermissionOverridesRequest;
import group.aitools.nhs.platform.iam.management.web.PermissionBindingView;
import group.aitools.nhs.platform.iam.management.web.PermissionCopyRecordView;
import group.aitools.nhs.platform.iam.management.web.PermissionCopyResult;
import group.aitools.nhs.platform.iam.management.web.PermissionDiffView;
import group.aitools.nhs.platform.iam.management.web.PermissionOverrideMutation;
import group.aitools.nhs.platform.iam.management.web.PermissionProfileView;
import group.aitools.nhs.platform.iam.management.web.PermissionRuleInput;
import group.aitools.nhs.platform.iam.management.web.PermissionRuleView;
import group.aitools.nhs.platform.iam.management.web.PermissionSummaryView;
import group.aitools.nhs.platform.iam.management.web.PutPermissionBindingRequest;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责权限Administration相关的业务编排与领域规则处理。
 * Management control plane for reusable profiles and per-user capability authorization. */
@Service
public class PermissionAdministrationService {

    private static final int MAX_POLICY_BYTES = 64 * 1024;
    private static final Set<String> RESOURCE_TYPES = Set.of(
        "agent", "agent_version", "model", "tool", "skill", "knowledge_base",
        "data_source", "dataset", "workflow", "connector", "sandbox",
        "api_application", "webhook", "cron"
    );
    private static final Map<String, Set<String>> RESOURCE_ACTIONS = Map.ofEntries(
        Map.entry("agent", Set.of("view", "use")),
        Map.entry("agent_version", Set.of("view", "use")),
        Map.entry("model", Set.of("view", "use")),
        Map.entry("tool", Set.of("invoke")),
        Map.entry("skill", Set.of("use", "approve", "reject")),
        Map.entry("knowledge_base", Set.of("read")),
        Map.entry("data_source", Set.of("read")),
        Map.entry("dataset", Set.of("read", "query", "export", "export_sensitive")),
        Map.entry("workflow", Set.of("use")),
        Map.entry("connector", Set.of("use")),
        Map.entry("sandbox", Set.of("use", "execute")),
        Map.entry("api_application", Set.of("use")),
        Map.entry("webhook", Set.of("invoke")),
        Map.entry("cron", Set.of("execute"))
    );
    private static final Set<String> EFFECTS = Set.of("allow", "deny", "approval_required");
    private static final Set<String> COPY_MODES = Set.of(
        "copy_base", "append_missing", "replace_base", "save_template"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final PermissionAdministrationMapper mapper;
    private final UserService userService;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code PermissionAdministrationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param userService 用户Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public PermissionAdministrationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        PermissionAdministrationMapper mapper,
        UserService userService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.userService = userService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code profiles}并返回对应结果。
     *
     * @param requestedStatus 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<PermissionProfileView> profiles(String requestedStatus, int limit) {
        requireIam("view", null, Set.of());
        String status = optionalEnum(requestedStatus, Set.of("draft", "published", "archived"), "权限包状态");
        return mapper.selectProfiles(status, limit).stream().map(this::profileView).toList();
    }

    /**
     * 处理配置档案并返回对应结果。
     *
     * @param profileId 资源标识
     * @return 处理结果
     */
    public PermissionProfileView profile(Long profileId) {
        requireIam("view", profileId, Set.of());
        return profileView(requireProfile(profileId));
    }

    /**
     * 创建并保存配置档案。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionProfileView createProfile(CreatePermissionProfileRequest request) {
        CurrentPrincipal principal = requireIam("create", null, Set.of());
        String key = requiredText(request.profileKey(), "权限包标识", 128);
        if (!key.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("权限包标识无效");
        }
        List<PreparedRule> rules = prepareRules(request.entries());
        PermissionProfile profile = insertProfile(
            key, requiredText(request.name(), "权限包名称", 128),
            optionalText(request.description(), 2000), request.profileType(), 1,
            rules, principal.id()
        );
        return profileView(profile);
    }

    /**
     * 创建并保存配置档案版本。
     *
     * @param sourceProfileId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionProfileView createProfileVersion(
        Long sourceProfileId,
        CreatePermissionProfileVersionRequest request
    ) {
        CurrentPrincipal principal = requireIam("create", sourceProfileId, Set.of());
        PermissionProfile source = mapper.lockProfile(sourceProfileId);
        if (source == null) {
            throw notFound("权限包不存在");
        }
        int version = mapper.selectLatestVersion(source.getProfileKey()) + 1;
        PermissionProfile created = insertProfile(
            source.getProfileKey(), requiredText(request.name(), "权限包名称", 128),
            optionalText(request.description(), 2000), source.getProfileType(), version,
            prepareRules(request.entries()), principal.id()
        );
        return profileView(created);
    }

    /**
     * 更新配置档案Status。
     *
     * @param profileId 资源标识
     * @param requestedStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionProfileView updateProfileStatus(Long profileId, String requestedStatus) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireIam("manage", profileId, Set.of());
        PermissionProfile profile = mapper.lockProfile(profileId);
        if (profile == null) {
            throw notFound("权限包不存在");
        }
        String target = requiredEnum(
            requestedStatus, Set.of("published", "archived"), "权限包状态"
        );
        if (target.equals(profile.getStatus())) {
            return profileView(profile);
        }
        boolean allowed = ("draft".equals(profile.getStatus()) && Set.of("published", "archived").contains(target))
            || ("published".equals(profile.getStatus()) && "archived".equals(target));
        if (!allowed) {
            throw conflict("不允许的权限包状态转换：" + profile.getStatus() + " -> " + target);
        }
        if ("published".equals(target) && mapper.selectProfileEntries(profileId).isEmpty()) {
            throw conflict("空权限包不能发布");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateProfileStatus(profileId, profile.getStatus(), target, now) != 1) {
            throw conflict("权限包状态已被并发修改");
        }
        profile.setStatus(target);
        profile.setUpdatedAt(now);
        return profileView(profile);
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    public PermissionSummaryView summary(Long userId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Set<BusinessRelation> relations = Objects.equals(principal.id(), userId)
            ? Set.of(BusinessRelation.OWNER) : Set.of();
        authorizationEnforcer.requireAllowed(principal, iamContext(userId, "view", relations));
        requireUser(userId);
        return buildSummary(userId);
    }

    /**
     * 处理{@code putBinding}并返回对应结果。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionBindingView putBinding(Long userId, PutPermissionBindingRequest request) {
        CurrentPrincipal principal = requireIam("assign", userId, Set.of());
        requireUser(userId);
        mapper.lockUserPermissions(userId);
        PreparedBinding prepared = prepareBinding(request);
        UserPermissionBinding current = mapper.selectActiveBinding(userId);
        if (sameBinding(current, prepared)) {
            return bindingView(current);
        }
        LocalDateTime now = LocalDateTime.now();
        if (current != null && mapper.replaceBinding(current.getId(), now) != 1) {
            throw conflict("用户权限绑定已被并发修改");
        }
        UserPermissionBinding binding = binding(
            userId, prepared, null, principal.id(), now
        );
        if (mapper.insertBinding(binding) != 1) {
            throw conflict("用户权限绑定写入冲突");
        }
        return bindingView(binding);
    }

    /**
     * 处理{@code patchOverrides}并返回对应结果。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionSummaryView patchOverrides(
        Long userId,
        PatchPermissionOverridesRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = requireIam("manage", userId, Set.of());
        requireUser(userId);
        mapper.lockUserPermissions(userId);
        for (PermissionOverrideMutation mutation : request.mutations()) {
            PreparedRule rule = prepareRule(mutation.rule());
            UserPermissionOverride current = mapper.selectActiveOverride(
                userId, rule.resourceType(), rule.resourceId(), rule.resourceKey(), rule.action()
            );
            if ("revoke".equals(mutation.operation())) {
                if (current != null && mapper.revokeOverride(current.getId()) != 1) {
                    throw conflict("用户权限覆盖项已被并发修改");
                }
                continue;
            }
            LocalDateTime expiresAt = mutation.expiresAt();
            if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
                throw badRequest("权限覆盖项过期时间必须晚于当前时间");
            }
            if (sameOverride(current, rule, expiresAt)) {
                continue;
            }
            if (current != null && mapper.revokeOverride(current.getId()) != 1) {
                throw conflict("用户权限覆盖项已被并发修改");
            }
            UserPermissionOverride override = override(userId, rule, expiresAt, principal.id());
            if (mapper.insertOverride(override) != 1) {
                throw conflict("用户权限覆盖项写入冲突");
            }
        }
        return buildSummary(userId);
    }

    /**
     * 创建并保存{@code TemporaryGrant}。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionRuleView createTemporaryGrant(
        Long userId,
        CreateTemporaryGrantRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireIam("assign", userId, Set.of());
        requireUser(userId);
        PreparedRule rule = prepareRule(request.rule());
        if (!Set.of("allow", "approval_required").contains(rule.effect())) {
            throw badRequest("临时授权只支持allow或approval_required");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!request.expiresAt().isAfter(now)) {
            throw badRequest("临时授权过期时间必须晚于当前时间");
        }
        if (request.expiresAt().isAfter(now.plusDays(365))) {
            throw badRequest("临时授权有效期不能超过365天");
        }
        TemporaryGrant grant = new TemporaryGrant();
        grant.setId(idGenerator.nextId());
        grant.setUserId(userId);
        apply(grant, rule);
        grant.setReason(requiredText(request.reason(), "临时授权原因", 1000));
        grant.setApprovalId(request.approvalId());
        grant.setExpiresAt(request.expiresAt());
        grant.setCreatedBy(principal.id());
        grant.setCreatedAt(now);
        if (mapper.insertTemporaryGrant(grant) != 1) {
            throw conflict("临时授权写入失败");
        }
        return grantView(grant);
    }

    /**
     * 处理{@code revokeTemporaryGrant}相关逻辑。
     *
     * @param userId 资源标识
     * @param grantId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeTemporaryGrant(Long userId, Long grantId) {
        requireIam("revoke", userId, Set.of());
        requireUser(userId);
        mapper.revokeTemporaryGrant(userId, grantId, LocalDateTime.now());
    }

    /**
     * 处理{@code diff}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param sourceUserId 资源标识
     * @return 处理结果
     */
    public PermissionDiffView diff(Long targetUserId, Long sourceUserId) {
        requireIam("view", targetUserId, Set.of());
        requireDistinctUsers(sourceUserId, targetUserId);
        RuleSet source = stableRules(sourceUserId, true);
        RuleSet target = stableRules(targetUserId, false);
        List<PermissionRuleView> missing = new ArrayList<>();
        List<PermissionRuleView> changed = new ArrayList<>();
        source.included().forEach((key, rule) -> {
            PreparedRule targetRule = target.included().get(key);
            if (targetRule == null) {
                missing.add(ruleView(rule));
            } else if (!sameCapability(rule, targetRule)) {
                changed.add(ruleView(rule));
            }
        });
        List<PermissionRuleView> targetOnly = target.included().entrySet().stream()
            .filter(entry -> !source.included().containsKey(entry.getKey()))
            .map(Map.Entry::getValue).map(this::ruleView).toList();
        return new PermissionDiffView(
            sourceUserId, targetUserId, sorted(missing), sorted(targetOnly), sorted(changed),
            sorted(source.excluded().stream().map(this::ruleView).toList())
        );
    }

    /**
     * 处理{@code copy}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionCopyResult copy(Long targetUserId, CopyPermissionRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireIam("assign", targetUserId, Set.of());
        String mode = requiredEnum(request.copyMode(), COPY_MODES, "权限复制模式");
        String idempotencyKey = requiredText(request.idempotencyKey(), "权限复制幂等键", 128);
        requireDistinctUsers(request.sourceUserId(), targetUserId);
        mapper.lockCopyIdempotency(idempotencyKey);
        String requestHash = copyRequestHash(targetUserId, request);
        PermissionCopyRecord existing = mapper.selectCopyRecordByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return replayCopy(existing, requestHash);
        }

        RuleSet source = stableRules(request.sourceUserId(), true);
        if (source.included().isEmpty()) {
            throw conflict("参考用户没有可复制的稳定权限");
        }
        mapper.lockUserPermissions(targetUserId);
        UserPermissionBinding before = mapper.selectActiveBinding(targetUserId);
        RuleSet targetBase = baseRuleSet(targetUserId, false);
        long recordId = idGenerator.nextId();
        Long afterBindingId = before == null ? null : before.getId();
        Long createdProfileId = null;
        Integer createdProfileVersion = null;
        int retained = 0;
        int added;

        if ("save_template".equals(mode)) {
            String templateKey = requiredText(request.templateKey(), "模板标识", 128);
            if (!templateKey.matches("[A-Za-z0-9._:-]+")) {
                throw badRequest("模板标识无效");
            }
            String templateName = requiredText(request.templateName(), "模板名称", 128);
            int version = mapper.selectLatestVersion(templateKey) + 1;
            PermissionProfile profile = insertProfile(
                templateKey, templateName, "由参考用户 " + request.sourceUserId() + " 保存",
                "custom", version, List.copyOf(source.included().values()), principal.id()
            );
            createdProfileId = profile.getId();
            createdProfileVersion = profile.getVersionNo();
            added = source.included().size();
        } else {
            LinkedHashMap<RuleKey, PreparedRule> result = new LinkedHashMap<>();
            if ("append_missing".equals(mode)) {
                result.putAll(targetBase.included());
                retained = result.size();
                source.included().forEach(result::putIfAbsent);
                added = result.size() - retained;
            } else {
                result.putAll(source.included());
                added = result.size();
            }
            LocalDateTime now = LocalDateTime.now();
            if (before != null && mapper.replaceBinding(before.getId(), now) != 1) {
                throw conflict("目标用户权限绑定已被并发修改");
            }
            PreparedBinding prepared = new PreparedBinding(
                "snapshot", null, null,
                snapshotJson("copy:" + recordId, List.copyOf(result.values()))
            );
            UserPermissionBinding after = binding(
                targetUserId, prepared, request.sourceUserId(), principal.id(), now
            );
            if (mapper.insertBinding(after) != 1) {
                throw conflict("目标用户权限快照写入冲突");
            }
            afterBindingId = after.getId();
        }

        Map<String, Object> diff = copyDiff(
            requestHash, added, retained, createdProfileId, createdProfileVersion
        );
        PermissionCopyRecord record = new PermissionCopyRecord();
        record.setId(recordId);
        record.setSourceUserId(request.sourceUserId());
        record.setTargetUserId(targetUserId);
        if (mapper.selectActiveBinding(request.sourceUserId()) != null) {
            UserPermissionBinding sourceBinding = mapper.selectActiveBinding(request.sourceUserId());
            record.setSourceProfileId(sourceBinding.getProfileId());
            record.setSourceProfileVersion(sourceBinding.getProfileVersion());
        }
        record.setCopyMode(mode);
        record.setBeforeBindingId(before == null ? null : before.getId());
        record.setAfterBindingId(afterBindingId);
        record.setDiffJson(jsonMapper.writeValueAsString(diff));
        record.setExcludedJson(jsonMapper.writeValueAsString(Map.of(
            "rules", source.excluded().stream().map(this::ruleDocument).toList()
        )));
        record.setIdempotencyKey(idempotencyKey);
        record.setCreatedBy(principal.id());
        record.setCreatedAt(LocalDateTime.now());
        if (mapper.insertCopyRecord(record) != 1) {
            throw conflict("权限复制幂等写入冲突");
        }
        return new PermissionCopyResult(
            recordId, request.sourceUserId(), targetUserId, mode,
            record.getBeforeBindingId(), afterBindingId, createdProfileId, createdProfileVersion,
            added, retained, sorted(source.excluded().stream().map(this::ruleView).toList()), false
        );
    }

    /**
     * 处理{@code copyRecords}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<PermissionCopyRecordView> copyRecords(int limit) {
        requireIam("view", null, Set.of());
        return mapper.selectCopyRecords(limit).stream().map(this::copyRecordView).toList();
    }

    /**
     * 创建并保存配置档案。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param description {@code description}参数
     * @param profileType 业务类型
     * @param version 版本参数
     * @param rules {@code rules}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    private PermissionProfile insertProfile(
        String key,
        String name,
        String description,
        String profileType,
        int version,
        List<PreparedRule> rules,
        Long userId
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!Set.of("system", "custom").contains(profileType)) {
            throw badRequest("权限包类型无效");
        }
        LocalDateTime now = LocalDateTime.now();
        PermissionProfile profile = new PermissionProfile();
        profile.setId(idGenerator.nextId());
        profile.setProfileKey(key);
        profile.setName(name);
        profile.setDescription(description);
        profile.setProfileType(profileType);
        profile.setVersionNo(version);
        profile.setStatus("draft");
        profile.setCreatedBy(userId);
        profile.setCreatedAt(now);
        profile.setDelFlag("0");
        if (mapper.insertProfile(profile) != 1) {
            throw conflict("权限包标识或版本已存在");
        }
        for (PreparedRule rule : rules) {
            PermissionProfileEntry entry = new PermissionProfileEntry();
            entry.setId(idGenerator.nextId());
            entry.setProfileId(profile.getId());
            apply(entry, rule);
            entry.setCreatedAt(now);
            if (mapper.insertProfileEntry(entry) != 1) {
                throw conflict("权限包规则重复");
            }
        }
        return profile;
    }

    /**
     * 处理{@code prepareBinding}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private PreparedBinding prepareBinding(PutPermissionBindingRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("profile".equals(request.bindingType())) {
            if (request.profileId() == null || request.profileVersion() == null
                || !request.snapshotRules().isEmpty()) {
                throw badRequest("权限包绑定必须提供profileId和profileVersion且不能携带快照规则");
            }
            PermissionProfile profile = mapper.selectProfile(request.profileId());
            if (profile == null || !request.profileVersion().equals(profile.getVersionNo())) {
                throw notFound("指定权限包版本不存在");
            }
            if (!"published".equals(profile.getStatus())) {
                throw conflict("只能绑定已发布的权限包版本");
            }
            return new PreparedBinding("profile", profile.getId(), profile.getVersionNo(), null);
        }
        if (!"snapshot".equals(request.bindingType()) || request.profileId() != null
            || request.profileVersion() != null || request.snapshotRules().isEmpty()) {
            throw badRequest("快照绑定必须只提供非空snapshotRules");
        }
        return new PreparedBinding(
            "snapshot", null, null, snapshotJson("manual", prepareRules(request.snapshotRules()))
        );
    }

    /**
     * 处理{@code binding}并返回对应结果。
     *
     * @param userId 资源标识
     * @param prepared {@code prepared}参数
     * @param sourceUserId 资源标识
     * @param createdBy {@code createdBy}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private UserPermissionBinding binding(
        Long userId,
        PreparedBinding prepared,
        Long sourceUserId,
        Long createdBy,
        LocalDateTime now
    ) {
        UserPermissionBinding binding = new UserPermissionBinding();
        binding.setId(idGenerator.nextId());
        binding.setUserId(userId);
        binding.setProfileId(prepared.profileId());
        binding.setProfileVersion(prepared.profileVersion());
        binding.setBindingType(prepared.bindingType());
        binding.setSnapshotJson(prepared.snapshotJson());
        binding.setSourceUserId(sourceUserId);
        binding.setStatus("active");
        binding.setCreatedBy(createdBy);
        binding.setCreatedAt(now);
        return binding;
    }

    /**
     * 处理{@code sameBinding}并返回对应结果。
     *
     * @param current 当前参数
     * @param prepared {@code prepared}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameBinding(UserPermissionBinding current, PreparedBinding prepared) {
        return current != null
            && prepared.bindingType().equals(current.getBindingType())
            && Objects.equals(prepared.profileId(), current.getProfileId())
            && Objects.equals(prepared.profileVersion(), current.getProfileVersion())
            && jsonEquivalent(prepared.snapshotJson(), current.getSnapshotJson());
    }

    /**
     * 处理{@code override}并返回对应结果。
     *
     * @param userId 资源标识
     * @param rule {@code rule}参数
     * @param expiresAt {@code expiresAt}参数
     * @param createdBy {@code createdBy}参数
     * @return 处理结果
     */
    private UserPermissionOverride override(
        Long userId,
        PreparedRule rule,
        LocalDateTime expiresAt,
        Long createdBy
    ) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setId(idGenerator.nextId());
        override.setUserId(userId);
        apply(override, rule);
        override.setReason(rule.reason());
        override.setStatus("active");
        override.setExpiresAt(expiresAt);
        override.setCreatedBy(createdBy);
        override.setCreatedAt(LocalDateTime.now());
        return override;
    }

    /**
     * 处理{@code sameOverride}并返回对应结果。
     *
     * @param current 当前参数
     * @param rule {@code rule}参数
     * @param expiresAt {@code expiresAt}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameOverride(
        UserPermissionOverride current,
        PreparedRule rule,
        LocalDateTime expiresAt
    ) {
        return current != null
            && rule.effect().equals(current.getEffect())
            && jsonEquivalent(rule.policyJson(), current.getPolicyJson())
            && Objects.equals(rule.reason(), current.getReason())
            && Objects.equals(expiresAt, current.getExpiresAt());
    }

    /**
     * 构建{@code Summary}。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private PermissionSummaryView buildSummary(Long userId) {
        UserPermissionBinding binding = mapper.selectActiveBinding(userId);
        List<PermissionRuleView> base = binding == null
            ? List.of() : bindingRules(binding).stream().map(this::ruleView).toList();
        LocalDateTime now = LocalDateTime.now();
        List<PermissionRuleView> overrides = mapper.selectActiveOverrides(userId).stream()
            .filter(value -> value.getExpiresAt() == null || value.getExpiresAt().isAfter(now))
            .map(this::overrideView).toList();
        List<PermissionRuleView> grants = mapper.selectEffectiveTemporaryGrants(userId).stream()
            .map(this::grantView).toList();
        return new PermissionSummaryView(
            userId, binding == null ? null : bindingView(binding),
            sorted(base), sorted(overrides), sorted(grants)
        );
    }

    /**
     * 处理{@code stableRules}并返回对应结果。
     *
     * @param userId 资源标识
     * @param excludeNonCopyable {@code excludeNonCopyable}参数
     * @return 处理结果
     */
    private RuleSet stableRules(Long userId, boolean excludeNonCopyable) {
        requireUser(userId);
        RuleSet base = baseRuleSet(userId, excludeNonCopyable);
        LinkedHashMap<RuleKey, PreparedRule> included = new LinkedHashMap<>(base.included());
        ArrayList<PreparedRule> excluded = new ArrayList<>(base.excluded());
        LocalDateTime now = LocalDateTime.now();
        mapper.selectActiveOverrides(userId).stream()
            .filter(value -> value.getExpiresAt() == null || value.getExpiresAt().isAfter(now))
            .map(this::prepared).forEach(rule -> addRule(
                included, excluded, rule, excludeNonCopyable
            ));
        return new RuleSet(sortedMap(included), List.copyOf(excluded));
    }

    /**
     * 处理{@code baseRuleSet}并返回对应结果。
     *
     * @param userId 资源标识
     * @param excludeNonCopyable {@code excludeNonCopyable}参数
     * @return 处理结果
     */
    private RuleSet baseRuleSet(Long userId, boolean excludeNonCopyable) {
        requireUser(userId);
        LinkedHashMap<RuleKey, PreparedRule> included = new LinkedHashMap<>();
        ArrayList<PreparedRule> excluded = new ArrayList<>();
        UserPermissionBinding binding = mapper.selectActiveBinding(userId);
        if (binding != null) {
            bindingRules(binding).forEach(rule -> addRule(
                included, excluded, rule, excludeNonCopyable
            ));
        }
        return new RuleSet(sortedMap(included), List.copyOf(excluded));
    }

    /**
     * 创建并保存{@code Rule}。
     *
     * @param included {@code included}参数
     * @param excluded {@code excluded}参数
     * @param rule {@code rule}参数
     * @param excludeNonCopyable {@code excludeNonCopyable}参数
     */
    private void addRule(
        Map<RuleKey, PreparedRule> included,
        List<PreparedRule> excluded,
        PreparedRule rule,
        boolean excludeNonCopyable
    ) {
        if (excludeNonCopyable && !copyable(rule)) {
            excluded.add(rule);
        } else {
            included.merge(rule.key(), rule, this::strongerRule);
        }
    }

    /**
     * 处理{@code copyable}并返回对应结果。
     *
     * @param rule {@code rule}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean copyable(PreparedRule rule) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!RESOURCE_TYPES.contains(rule.resourceType())) {
            return false;
        }
        if (rule.resourceId() != null && !"active".equals(resourceState(rule.resourceType(), rule.resourceId()))) {
            return false;
        }
        if ("knowledge_base".equals(rule.resourceType())) {
            return rule.resourceId() != null
                && mapper.countPrivateKnowledgeBase(rule.resourceId()) == 0;
        }
        if ("skill".equals(rule.resourceType())) {
            return rule.resourceId() != null
                && mapper.countPrivateUserSkill(rule.resourceId()) == 0;
        }
        return true;
    }

    /**
     * 处理{@code strongerRule}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 处理结果
     */
    private PreparedRule strongerRule(PreparedRule left, PreparedRule right) {
        int leftRank = effectRank(left.effect());
        int rightRank = effectRank(right.effect());
        return rightRank >= leftRank ? right : left;
    }

    /**
     * 处理{@code effectRank}并返回对应结果。
     *
     * @param effect {@code effect}参数
     * @return 处理结果
     */
    private int effectRank(String effect) {
        return switch (effect) {
            case "deny" -> 3;
            case "approval_required" -> 2;
            default -> 1;
        };
    }

    /**
     * 处理{@code bindingRules}并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @return 符合条件的数据集合
     */
    private List<PreparedRule> bindingRules(UserPermissionBinding binding) {
        if ("profile".equals(binding.getBindingType())) {
            PermissionProfile profile = mapper.selectProfile(binding.getProfileId());
            if (profile == null || !binding.getProfileVersion().equals(profile.getVersionNo())
                || !"published".equals(profile.getStatus())) {
                return List.of();
            }
            return mapper.selectProfileEntries(profile.getId()).stream().map(this::prepared).toList();
        }
        return parseSnapshot(binding.getSnapshotJson());
    }

    /**
     * 处理parse快照并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<PreparedRule> parseSnapshot(String json) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (json == null || json.isBlank()) {
            throw conflict("用户权限快照为空");
        }
        try {
            SnapshotDocument document = jsonMapper.readValue(json, SnapshotDocument.class);
            if (document == null || document.rules() == null) {
                throw conflict("用户权限快照格式无效");
            }
            return document.rules().stream().map(rule -> prepareRule(new PermissionRuleInput(
                rule.resourceType(), rule.resourceId(), rule.resourceKey(), rule.action(),
                rule.effect(), rule.policy(), rule.reason()
            ))).toList();
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("用户权限快照格式无效");
        }
    }

    /**
     * 处理快照Json并返回对应结果。
     *
     * @param version 版本参数
     * @param rules {@code rules}参数
     * @return 处理结果
     */
    private String snapshotJson(String version, List<PreparedRule> rules) {
        SnapshotDocument document = new SnapshotDocument(
            version, rules.stream().map(rule -> new SnapshotRule(
                rule.resourceType(), rule.resourceId(), rule.resourceKey(), rule.action(),
                rule.effect(), jsonMapper.readValue(rule.policyJson(), MAP_TYPE), rule.reason()
            )).toList()
        );
        return jsonMapper.writeValueAsString(document);
    }

    /**
     * 处理{@code prepareRules}并返回对应结果。
     *
     * @param inputs {@code inputs}参数
     * @return 符合条件的数据集合
     */
    private List<PreparedRule> prepareRules(List<PermissionRuleInput> inputs) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (inputs == null || inputs.isEmpty()) {
            throw badRequest("权限规则不能为空");
        }
        if (inputs.size() > 512) {
            throw badRequest("权限规则不能超过512条");
        }
        LinkedHashMap<RuleKey, PreparedRule> rules = new LinkedHashMap<>();
        for (PermissionRuleInput input : inputs) {
            PreparedRule rule = prepareRule(input);
            if (rules.putIfAbsent(rule.key(), rule) != null) {
                throw badRequest("权限规则目标和动作不能重复");
            }
        }
        return List.copyOf(rules.values());
    }

    /**
     * 处理{@code prepareRule}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private PreparedRule prepareRule(PermissionRuleInput input) {
        if (input == null) {
            throw badRequest("权限规则不能为空");
        }
        String type = requiredEnum(input.resourceType(), RESOURCE_TYPES, "权限资源类型");
        String action = requiredEnum(input.action(), RESOURCE_ACTIONS.get(type), "权限动作");
        String effect = requiredEnum(input.effect(), EFFECTS, "权限效果");
        Long resourceId = input.resourceId();
        String resourceKey = optionalText(input.resourceKey(), 255);
        if ((resourceId == null) == (resourceKey == null)) {
            throw badRequest("权限规则必须且只能提供resourceId或resourceKey");
        }
        if (resourceKey != null && isSensitiveReference(resourceKey)) {
            throw badRequest("权限资源标识不能包含凭证引用");
        }
        String policyJson = policyJson(input.policy());
        return new PreparedRule(
            null, type, resourceId, resourceKey, action, effect, policyJson,
            optionalText(input.reason(), 1000), "active", null
        );
    }

    /**
     * 处理策略Json并返回对应结果。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    private String policyJson(Map<String, Object> policy) {
        Map<String, Object> canonical = canonicalMap(policy == null ? Map.of() : policy, 0);
        String json = jsonMapper.writeValueAsString(canonical);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_POLICY_BYTES) {
            throw badRequest("权限策略超过64KB");
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
    private Map<String, Object> canonicalMap(Map<String, Object> value, int depth) {
        if (depth > 16) {
            throw badRequest("权限策略嵌套过深");
        }
        TreeMap<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank() || key.length() > 128 || isSensitiveReference(key)) {
                throw badRequest("权限策略包含敏感或无效字段");
            }
            result.put(key, canonicalValue(item, depth + 1));
        });
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
            throw badRequest("权限策略嵌套过深");
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw badRequest("权限策略字段必须为文本");
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
        throw badRequest("权限策略包含不支持的值");
    }

    /**
     * 判断{@code SensitiveReference}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSensitiveReference(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return Set.of("secret", "password", "apikey", "authorization", "credential", "privatekey")
            .stream().anyMatch(normalized::contains)
            || Set.of("token", "accesstoken", "refreshtoken", "authtoken", "bearertoken", "sessiontoken")
            .contains(normalized);
    }

    /**
     * 处理配置档案View并返回对应结果。
     *
     * @param profile 配置档案参数
     * @return 处理结果
     */
    private PermissionProfileView profileView(PermissionProfile profile) {
        List<PermissionRuleView> entries = mapper.selectProfileEntries(profile.getId()).stream()
            .map(this::entryView).toList();
        return new PermissionProfileView(
            profile.getId(), profile.getProfileKey(), profile.getName(), profile.getDescription(),
            profile.getProfileType(), profile.getVersionNo(), profile.getStatus(),
            profile.getCreatedBy(), profile.getCreatedAt(), sorted(entries)
        );
    }

    /**
     * 处理{@code bindingView}并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @return 处理结果
     */
    private PermissionBindingView bindingView(UserPermissionBinding binding) {
        List<PermissionRuleView> rules = "snapshot".equals(binding.getBindingType())
            ? parseSnapshot(binding.getSnapshotJson()).stream().map(this::ruleView).toList()
            : List.of();
        return new PermissionBindingView(
            binding.getId(), binding.getUserId(), binding.getBindingType(), binding.getProfileId(),
            binding.getProfileVersion(), binding.getSourceUserId(), binding.getStatus(),
            binding.getCreatedBy(), binding.getCreatedAt(), sorted(rules)
        );
    }

    /**
     * 处理{@code entryView}并返回对应结果。
     *
     * @param entry {@code entry}参数
     * @return 处理结果
     */
    private PermissionRuleView entryView(PermissionProfileEntry entry) {
        return new PermissionRuleView(
            entry.getId(), entry.getResourceType(), entry.getResourceId(), entry.getResourceKey(),
            entry.getAction(), entry.getEffect(), map(entry.getPolicyJson()), null, "active", null,
            resourceState(entry.getResourceType(), entry.getResourceId())
        );
    }

    /**
     * 处理{@code overrideView}并返回对应结果。
     *
     * @param override {@code override}参数
     * @return 处理结果
     */
    private PermissionRuleView overrideView(UserPermissionOverride override) {
        return new PermissionRuleView(
            override.getId(), override.getResourceType(), override.getResourceId(), override.getResourceKey(),
            override.getAction(), override.getEffect(), map(override.getPolicyJson()), override.getReason(),
            override.getStatus(), override.getExpiresAt(),
            resourceState(override.getResourceType(), override.getResourceId())
        );
    }

    /**
     * 处理{@code grantView}并返回对应结果。
     *
     * @param grant {@code grant}参数
     * @return 处理结果
     */
    private PermissionRuleView grantView(TemporaryGrant grant) {
        return new PermissionRuleView(
            grant.getId(), grant.getResourceType(), grant.getResourceId(), grant.getResourceKey(),
            grant.getAction(), grant.getEffect(), map(grant.getPolicyJson()), grant.getReason(),
            grant.getRevokedAt() == null ? "active" : "revoked", grant.getExpiresAt(),
            resourceState(grant.getResourceType(), grant.getResourceId())
        );
    }

    /**
     * 处理{@code ruleView}并返回对应结果。
     *
     * @param rule {@code rule}参数
     * @return 处理结果
     */
    private PermissionRuleView ruleView(PreparedRule rule) {
        return new PermissionRuleView(
            rule.id(), rule.resourceType(), rule.resourceId(), rule.resourceKey(), rule.action(),
            rule.effect(), map(rule.policyJson()), rule.reason(), rule.status(), rule.expiresAt(),
            resourceState(rule.resourceType(), rule.resourceId())
        );
    }

    /**
     * 处理资源State并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @return 处理结果
     */
    private String resourceState(String resourceType, Long resourceId) {
        if (resourceId == null) {
            return "unresolved";
        }
        String state = mapper.selectResourceState(resourceType, resourceId);
        return Set.of("active", "inactive", "missing").contains(state) ? state : "missing";
    }

    /**
     * 处理{@code copyRecordView}并返回对应结果。
     *
     * @param record {@code record}参数
     * @return 处理结果
     */
    private PermissionCopyRecordView copyRecordView(PermissionCopyRecord record) {
        return new PermissionCopyRecordView(
            record.getId(), record.getSourceUserId(), record.getTargetUserId(),
            record.getSourceProfileId(), record.getSourceProfileVersion(), record.getCopyMode(),
            record.getBeforeBindingId(), record.getAfterBindingId(), map(record.getDiffJson()),
            map(record.getExcludedJson()), record.getIdempotencyKey(), record.getCreatedBy(),
            record.getCreatedAt()
        );
    }

    /**
     * 处理{@code replayCopy}并返回对应结果。
     *
     * @param record {@code record}参数
     * @param requestHash {@code requestHash}参数
     * @return 处理结果
     */
    private PermissionCopyResult replayCopy(PermissionCopyRecord record, String requestHash) {
        Map<String, Object> diff = map(record.getDiffJson());
        if (!requestHash.equals(diff.get("requestHash"))) {
            throw conflict("同一权限复制幂等键不能用于不同请求");
        }
        Map<String, Object> excluded = map(record.getExcludedJson());
        List<PermissionRuleView> excludedRules = parseRuleDocuments(excluded.get("rules"));
        return new PermissionCopyResult(
            record.getId(), record.getSourceUserId(), record.getTargetUserId(), record.getCopyMode(),
            record.getBeforeBindingId(), record.getAfterBindingId(), longValue(diff.get("createdProfileId")),
            integerValue(diff.get("createdProfileVersion")), integerValue(diff.get("addedRuleCount"), 0),
            integerValue(diff.get("retainedRuleCount"), 0), excludedRules, true
        );
    }

    /**
     * 处理{@code parseRuleDocuments}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<PermissionRuleView> parseRuleDocuments(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<PermissionRuleView> result = new ArrayList<>();
        for (Object item : list) {
            PermissionRuleInput input = jsonMapper.convertValue(item, PermissionRuleInput.class);
            result.add(ruleView(prepareRule(input)));
        }
        return sorted(result);
    }

    /**
     * 处理{@code copyDiff}并返回对应结果。
     *
     * @param requestHash {@code requestHash}参数
     * @param added {@code added}参数
     * @param retained {@code retained}参数
     * @param createdProfileId 资源标识
     * @param createdProfileVersion created配置档案版本参数
     * @return 处理结果
     */
    private Map<String, Object> copyDiff(
        String requestHash,
        int added,
        int retained,
        Long createdProfileId,
        Integer createdProfileVersion
    ) {
        LinkedHashMap<String, Object> diff = new LinkedHashMap<>();
        diff.put("requestHash", requestHash);
        diff.put("addedRuleCount", added);
        diff.put("retainedRuleCount", retained);
        if (createdProfileId != null) {
            diff.put("createdProfileId", createdProfileId);
            diff.put("createdProfileVersion", createdProfileVersion);
        }
        return diff;
    }

    /**
     * 处理{@code copyRequestHash}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    private String copyRequestHash(Long targetUserId, CopyPermissionRequest request) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("targetUserId", targetUserId);
        value.put("sourceUserId", request.sourceUserId());
        value.put("copyMode", request.copyMode());
        value.put("templateKey", request.templateKey());
        value.put("templateName", request.templateName());
        return ContentHashing.sha256(jsonMapper.writeValueAsString(value));
    }

    /**
     * 处理rule文档并返回对应结果。
     *
     * @param rule {@code rule}参数
     * @return 处理结果
     */
    private Map<String, Object> ruleDocument(PreparedRule rule) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("resourceType", rule.resourceType());
        if (rule.resourceId() != null) {
            value.put("resourceId", rule.resourceId());
        }
        if (rule.resourceKey() != null) {
            value.put("resourceKey", rule.resourceKey());
        }
        value.put("action", rule.action());
        value.put("effect", rule.effect());
        value.put("policy", map(rule.policyJson()));
        if (rule.reason() != null) {
            value.put("reason", rule.reason());
        }
        return value;
    }

    /**
     * 处理{@code prepared}并返回对应结果。
     *
     * @param entry {@code entry}参数
     * @return 处理结果
     */
    private PreparedRule prepared(PermissionProfileEntry entry) {
        return new PreparedRule(
            entry.getId(), entry.getResourceType(), entry.getResourceId(), entry.getResourceKey(),
            entry.getAction(), entry.getEffect(), canonicalJson(entry.getPolicyJson()),
            null, "active", null
        );
    }

    /**
     * 处理{@code prepared}并返回对应结果。
     *
     * @param override {@code override}参数
     * @return 处理结果
     */
    private PreparedRule prepared(UserPermissionOverride override) {
        return new PreparedRule(
            override.getId(), override.getResourceType(), override.getResourceId(), override.getResourceKey(),
            override.getAction(), override.getEffect(), canonicalJson(override.getPolicyJson()),
            override.getReason(), override.getStatus(), override.getExpiresAt()
        );
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param entry {@code entry}参数
     * @param rule {@code rule}参数
     */
    private void apply(PermissionProfileEntry entry, PreparedRule rule) {
        entry.setResourceType(rule.resourceType());
        entry.setResourceId(rule.resourceId());
        entry.setResourceKey(rule.resourceKey());
        entry.setAction(rule.action());
        entry.setEffect(rule.effect());
        entry.setPolicyJson(rule.policyJson());
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param override {@code override}参数
     * @param rule {@code rule}参数
     */
    private void apply(UserPermissionOverride override, PreparedRule rule) {
        override.setResourceType(rule.resourceType());
        override.setResourceId(rule.resourceId());
        override.setResourceKey(rule.resourceKey());
        override.setAction(rule.action());
        override.setEffect(rule.effect());
        override.setPolicyJson(rule.policyJson());
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param grant {@code grant}参数
     * @param rule {@code rule}参数
     */
    private void apply(TemporaryGrant grant, PreparedRule rule) {
        grant.setResourceType(rule.resourceType());
        grant.setResourceId(rule.resourceId());
        grant.setResourceKey(rule.resourceKey());
        grant.setAction(rule.action());
        grant.setEffect(rule.effect());
        grant.setPolicyJson(rule.policyJson());
    }

    /**
     * 处理{@code sameCapability}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameCapability(PreparedRule left, PreparedRule right) {
        return left.effect().equals(right.effect()) && left.policyJson().equals(right.policyJson());
    }

    /**
     * 处理{@code sorted}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private List<PermissionRuleView> sorted(List<PermissionRuleView> values) {
        return values.stream().sorted(Comparator
            .comparing(PermissionRuleView::resourceType)
            .thenComparing(value -> value.resourceId() == null ? 0L : value.resourceId())
            .thenComparing(value -> value.resourceKey() == null ? "" : value.resourceKey())
            .thenComparing(PermissionRuleView::action)).toList();
    }

    /**
     * 处理{@code sortedMap}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private LinkedHashMap<RuleKey, PreparedRule> sortedMap(Map<RuleKey, PreparedRule> values) {
        LinkedHashMap<RuleKey, PreparedRule> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            result.put(entry.getKey(), entry.getValue())
        );
        return result;
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private Map<String, Object> map(String json) {
        return json == null || json.isBlank() ? Map.of() : jsonMapper.readValue(json, MAP_TYPE);
    }

    /**
     * 判断{@code onicalJson}是否满足要求。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private String canonicalJson(String json) {
        return json == null || json.isBlank()
            ? null : jsonMapper.writeValueAsString(jsonMapper.readValue(json, Object.class));
    }

    /**
     * 处理{@code jsonEquivalent}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean jsonEquivalent(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null || right.isBlank();
        }
        if (right == null || right.isBlank()) {
            return false;
        }
        return Objects.equals(
            jsonMapper.readValue(left, Object.class),
            jsonMapper.readValue(right, Object.class)
        );
    }

    /**
     * 处理{@code longValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 处理{@code integerValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * 处理{@code integerValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 处理结果
     */
    private int integerValue(Object value, int defaultValue) {
        Integer parsed = integerValue(value);
        return parsed == null ? defaultValue : parsed;
    }

    /**
     * 校验配置档案，并在条件不满足时终止处理。
     *
     * @param profileId 资源标识
     * @return 处理结果
     */
    private PermissionProfile requireProfile(Long profileId) {
        PermissionProfile profile = mapper.selectProfile(profileId);
        if (profile == null) {
            throw notFound("权限包不存在");
        }
        return profile;
    }

    /**
     * 校验用户，并在条件不满足时终止处理。
     *
     * @param userId 资源标识
     */
    private void requireUser(Long userId) {
        if (userId == null || userId <= 0 || userService.selectById(userId) == null) {
            throw notFound("用户不存在");
        }
    }

    /**
     * 校验{@code DistinctUsers}，并在条件不满足时终止处理。
     *
     * @param sourceUserId 资源标识
     * @param targetUserId 资源标识
     */
    private void requireDistinctUsers(Long sourceUserId, Long targetUserId) {
        requireUser(sourceUserId);
        requireUser(targetUserId);
        if (sourceUserId.equals(targetUserId)) {
            throw badRequest("参考用户和目标用户不能相同");
        }
    }

    /**
     * 校验{@code Iam}，并在条件不满足时终止处理。
     *
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private CurrentPrincipal requireIam(
        String action,
        Long resourceId,
        Set<BusinessRelation> relations
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, iamContext(resourceId, action, relations));
        return principal;
    }

    /**
     * 处理iam上下文并返回对应结果。
     *
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private PermissionContext iamContext(
        Long resourceId,
        String action,
        Set<BusinessRelation> relations
    ) {
        return new PermissionContext(
            "iam", resourceId, null, action, ResourceState.ACTIVE, true, relations, null
        );
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
        if (value == null || value.isBlank()) {
            throw badRequest(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(label + "过长");
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
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest("文本字段过长");
        }
        return normalized;
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = requiredText(value, label, 64).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalEnum}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? null : requiredEnum(value, allowed, label);
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
     * 处理{@code notFound}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    /**
     * 封装{@code PreparedBinding}相关的不可变数据。
     */
    private record PreparedBinding(
        String bindingType,
        Long profileId,
        Integer profileVersion,
        String snapshotJson
    ) {
    }

    /**
     * 封装{@code PreparedRule}相关的不可变数据。
     */
    private record PreparedRule(
        Long id,
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        String effect,
        String policyJson,
        String reason,
        String status,
        LocalDateTime expiresAt
    ) {

        /**
         * 处理{@code key}并返回对应结果。
         *
         * @return 处理结果
         */
        RuleKey key() {
            return new RuleKey(resourceType, resourceId, resourceKey, action);
        }
    }

    /**
     * 封装{@code RuleKey}相关的不可变数据。
     */
    private record RuleKey(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action
    ) implements Comparable<RuleKey> {

        /**
         * 处理{@code compareTo}并返回对应结果。
         *
         * @param other {@code other}参数
         * @return 处理结果
         */
        @Override
        public int compareTo(RuleKey other) {
            int type = resourceType.compareTo(other.resourceType);
            if (type != 0) {
                return type;
            }
            int id = Long.compare(resourceId == null ? 0L : resourceId, other.resourceId == null ? 0L : other.resourceId);
            if (id != 0) {
                return id;
            }
            int key = Objects.toString(resourceKey, "").compareTo(Objects.toString(other.resourceKey, ""));
            return key != 0 ? key : action.compareTo(other.action);
        }
    }

    /**
     * 封装{@code RuleSet}相关的不可变数据。
     */
    private record RuleSet(
        LinkedHashMap<RuleKey, PreparedRule> included,
        List<PreparedRule> excluded
    ) {
    }

    /**
     * 封装快照文档相关的不可变数据。
     */
    private record SnapshotDocument(String version, List<SnapshotRule> rules) {
    }

    /**
     * 封装快照Rule相关的不可变数据。
     */
    private record SnapshotRule(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        String effect,
        Map<String, Object> policy,
        String reason
    ) {
    }
}
