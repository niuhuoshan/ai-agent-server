package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.identity.domain.ApiApplication;
import group.aitools.nhs.platform.identity.domain.ApiCredential;
import group.aitools.nhs.platform.identity.domain.ServiceAccount;
import group.aitools.nhs.platform.identity.domain.ServiceAccountGrant;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.CredentialSecretGenerator.GeneratedCredential;
import group.aitools.nhs.platform.identity.web.ApiApplicationView;
import group.aitools.nhs.platform.identity.web.ApiCredentialView;
import group.aitools.nhs.platform.identity.web.CreateApiApplicationRequest;
import group.aitools.nhs.platform.identity.web.CreateServiceAccountRequest;
import group.aitools.nhs.platform.identity.web.CreateServiceAccountGrantRequest;
import group.aitools.nhs.platform.identity.web.IssueApiCredentialRequest;
import group.aitools.nhs.platform.identity.web.IssuedApiCredentialView;
import group.aitools.nhs.platform.identity.web.ServiceAccountView;
import group.aitools.nhs.platform.identity.web.ServiceAccountGrantView;
import group.aitools.nhs.platform.identity.web.UpdateApiApplicationRequest;
import group.aitools.nhs.platform.identity.web.UpdateServiceAccountRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
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
 * 负责Machine身份相关的业务编排与领域规则处理。
 * Administrative lifecycle for isolated machine principals and one-time API credentials. */
@Service
public class MachineIdentityApplicationService {

    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> STATUSES = Set.of("active", "disabled", "expired", "revoked");
    private static final Set<String> MUTABLE_STATUSES = Set.of("active", "disabled");
    private static final Set<String> APP_TYPES = Set.of("embed", "open_api", "webhook", "internal");
    private static final Set<String> SCOPES = Set.of(
        "agents:use", "chat:invoke", "tasks:create", "tasks:read", "tasks:run", "events:read",
        "artifacts:read", "acceptance:write", "webhooks:invoke", "cron:execute", "mcp:invoke"
    );
    private static final Set<String> GRANT_EFFECTS = Set.of("allow", "deny");
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final MachineIdentityMapper mapper;
    private final UserService userService;
    private final CredentialSecretGenerator secretGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code MachineIdentityApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param userService 用户Service参数
     * @param secretGenerator {@code secretGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public MachineIdentityApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        MachineIdentityMapper mapper,
        UserService userService,
        CredentialSecretGenerator secretGenerator,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.userService = userService;
        this.secretGenerator = secretGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code serviceAccounts}并返回对应结果。
     *
     * @param requestedStatus 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ServiceAccountView> serviceAccounts(String requestedStatus, int limit) {
        requireManage("view", null);
        String status = optionalEnum(requestedStatus, STATUSES, "服务账号状态");
        return mapper.selectServiceAccounts(status, limit).stream().map(this::accountView).toList();
    }

    /**
     * 处理service账户并返回对应结果。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    public ServiceAccountView serviceAccount(Long accountId) {
        requireManage("view", accountId);
        return accountView(requireAccount(accountId));
    }

    /**
     * 处理service账户Grants并返回对应结果。
     *
     * @param accountId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ServiceAccountGrantView> serviceAccountGrants(Long accountId, int limit) {
        requireManage("view", accountId);
        requireAccount(accountId);
        return mapper.selectServiceAccountGrants(accountId, limit).stream()
            .map(this::grantView).toList();
    }

    /**
     * 创建并保存Service账户Grant。
     *
     * @param accountId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceAccountGrantView createServiceAccountGrant(
        Long accountId,
        CreateServiceAccountGrantRequest request
    ) {
        CurrentPrincipal principal = requireManage("assign", accountId);
        ServiceAccount account = lockAccount(accountId);
        requireActive(account.getStatus(), account.getExpiresAt(), "服务账号");
        String resourceType = capabilityName(request.resourceType(), "资源类型");
        String resourceKey = optionalCapabilityKey(request.resourceKey());
        if ((request.resourceId() == null || request.resourceId() <= 0) && resourceKey == null) {
            throw badRequest("服务账号授权必须指定资源ID或资源标识");
        }
        if (request.resourceId() != null && request.resourceId() <= 0) {
            throw badRequest("服务账号授权资源ID无效");
        }
        validateFutureExpiry(request.expiresAt(), "服务账号授权");
        ServiceAccountGrant grant = new ServiceAccountGrant();
        grant.setId(idGenerator.nextId());
        grant.setServiceAccountId(accountId);
        grant.setResourceType(resourceType);
        grant.setResourceId(request.resourceId());
        grant.setResourceKey(resourceKey);
        grant.setAction(capabilityName(request.action(), "授权动作"));
        grant.setEffect(requiredEnum(request.effect(), GRANT_EFFECTS, "授权效果"));
        grant.setReason(requiredText(request.reason(), "授权原因", 1000));
        grant.setExpiresAt(request.expiresAt());
        grant.setCreatedBy(principal.id());
        grant.setCreatedAt(LocalDateTime.now());
        if (mapper.insertServiceAccountGrant(grant) != 1) {
            throw conflict("相同服务账号授权已存在");
        }
        return grantView(grant);
    }

    /**
     * 处理revokeService账户Grant相关逻辑。
     *
     * @param accountId 资源标识
     * @param grantId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeServiceAccountGrant(Long accountId, Long grantId) {
        requireManage("revoke", accountId);
        requireAccount(accountId);
        ServiceAccountGrant grant = mapper.selectServiceAccountGrant(accountId, grantId);
        if (grant == null) {
            throw notFound("服务账号授权不存在");
        }
        if (grant.getRevokedAt() == null
            && mapper.revokeServiceAccountGrant(accountId, grantId, LocalDateTime.now()) != 1) {
            throw conflict("服务账号授权已被并发修改");
        }
    }

    /**
     * 创建并保存Service账户。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceAccountView createServiceAccount(CreateServiceAccountRequest request) {
        CurrentPrincipal principal = requireManage("create", null);
        validateOwner(request.ownerId());
        validateFutureExpiry(request.expiresAt(), "服务账号");
        ServiceAccount account = new ServiceAccount();
        account.setId(idGenerator.nextId());
        account.setAccountKey(requiredKey(request.accountKey(), "服务账号标识"));
        account.setName(requiredText(request.name(), "服务账号名称", 128));
        account.setDescription(optionalText(request.description(), 2000));
        account.setOwnerId(request.ownerId());
        account.setStatus("active");
        account.setExpiresAt(request.expiresAt());
        account.setMetadataJson(documentJson(request.metadata(), "服务账号元数据"));
        account.setCreateBy(principal.id());
        account.setCreateTime(LocalDateTime.now());
        account.setDelFlag("0");
        account.setExtraJson("{}");
        if (mapper.insertServiceAccount(account) != 1) {
            throw conflict("服务账号标识已存在");
        }
        return accountView(account);
    }

    /**
     * 更新Service账户。
     *
     * @param accountId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceAccountView updateServiceAccount(
        Long accountId,
        UpdateServiceAccountRequest request
    ) {
        CurrentPrincipal principal = requireManage("manage", accountId);
        ServiceAccount account = lockAccount(accountId);
        if ("revoked".equals(account.getStatus())) {
            throw conflict("已撤销的服务账号不能修改");
        }
        validateOwner(request.ownerId());
        validateFutureExpiry(request.expiresAt(), "服务账号");
        account.setName(requiredText(request.name(), "服务账号名称", 128));
        account.setDescription(optionalText(request.description(), 2000));
        account.setOwnerId(request.ownerId());
        account.setExpiresAt(request.expiresAt());
        account.setMetadataJson(documentJson(request.metadata(), "服务账号元数据"));
        account.setUpdateBy(principal.id());
        account.setUpdateTime(LocalDateTime.now());
        if (mapper.updateServiceAccount(account) != 1) {
            throw conflict("服务账号已被并发修改");
        }
        return accountView(account);
    }

    /**
     * 更新Service账户Status。
     *
     * @param accountId 资源标识
     * @param requestedStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceAccountView updateServiceAccountStatus(Long accountId, String requestedStatus) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireManage("manage", accountId);
        ServiceAccount account = lockAccount(accountId);
        String target = requiredEnum(
            requestedStatus, Set.of("active", "disabled", "revoked"), "服务账号状态"
        );
        if (target.equals(account.getStatus())) {
            return accountView(account);
        }
        if (!allowedStatusTransition(account.getStatus(), target)) {
            throw conflict("不允许的服务账号状态转换：" + account.getStatus() + " -> " + target);
        }
        if ("active".equals(target) && isExpired(account.getExpiresAt())) {
            throw conflict("已过期的服务账号不能启用");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateServiceAccountStatus(
            accountId, account.getStatus(), target, principal.id(), now
        ) != 1) {
            throw conflict("服务账号状态已被并发修改");
        }
        account.setStatus(target);
        account.setUpdateBy(principal.id());
        account.setUpdateTime(now);
        return accountView(account);
    }

    /**
     * 处理接口Applications并返回对应结果。
     *
     * @param requestedStatus 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ApiApplicationView> apiApplications(String requestedStatus, int limit) {
        requireManage("view", null);
        String status = optionalEnum(requestedStatus, STATUSES, "API应用状态");
        return mapper.selectApiApplications(status, limit).stream().map(this::applicationView).toList();
    }

    /**
     * 处理接口应用并返回对应结果。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    public ApiApplicationView apiApplication(Long applicationId) {
        requireManage("view", applicationId);
        return applicationView(requireApplication(applicationId));
    }

    /**
     * 创建并保存接口应用。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiApplicationView createApiApplication(CreateApiApplicationRequest request) {
        CurrentPrincipal principal = requireManage("create", null);
        String appType = requiredEnum(request.appType(), APP_TYPES, "API应用类型");
        validateOwner(request.ownerId());
        validateFutureExpiry(request.expiresAt(), "API应用");
        ApiApplication application = new ApiApplication();
        application.setId(idGenerator.nextId());
        application.setAppKey(requiredKey(request.appKey(), "API应用标识"));
        application.setName(requiredText(request.name(), "API应用名称", 128));
        application.setAppType(appType);
        application.setStatus("active");
        application.setOwnerId(request.ownerId());
        application.setCallbackUrl(callbackUrl(request.callbackUrl(), appType));
        application.setScopeJson(scopeJson(normalizeScopes(request.scopes())));
        application.setExtraJson(applicationConfigJson(appType, request.config()));
        application.setExpiresAt(request.expiresAt());
        application.setCreateBy(principal.id());
        application.setCreateTime(LocalDateTime.now());
        application.setDelFlag("0");
        if (mapper.insertApiApplication(application) != 1) {
            throw conflict("API应用标识已存在");
        }
        return applicationView(application);
    }

    /**
     * 更新接口应用。
     *
     * @param applicationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiApplicationView updateApiApplication(
        Long applicationId,
        UpdateApiApplicationRequest request
    ) {
        CurrentPrincipal principal = requireManage("manage", applicationId);
        ApiApplication application = lockApplication(applicationId);
        if ("revoked".equals(application.getStatus())) {
            throw conflict("已撤销的API应用不能修改");
        }
        validateOwner(request.ownerId());
        validateFutureExpiry(request.expiresAt(), "API应用");
        application.setName(requiredText(request.name(), "API应用名称", 128));
        application.setOwnerId(request.ownerId());
        application.setCallbackUrl(callbackUrl(request.callbackUrl(), application.getAppType()));
        application.setScopeJson(scopeJson(normalizeScopes(request.scopes())));
        application.setExtraJson(applicationConfigJson(application.getAppType(), request.config()));
        application.setExpiresAt(request.expiresAt());
        application.setUpdateBy(principal.id());
        application.setUpdateTime(LocalDateTime.now());
        if (mapper.updateApiApplication(application) != 1) {
            throw conflict("API应用已被并发修改");
        }
        return applicationView(application);
    }

    /**
     * 更新接口应用Status。
     *
     * @param applicationId 资源标识
     * @param requestedStatus 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiApplicationView updateApiApplicationStatus(Long applicationId, String requestedStatus) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireManage("manage", applicationId);
        ApiApplication application = lockApplication(applicationId);
        String target = requiredEnum(
            requestedStatus, Set.of("active", "disabled", "revoked"), "API应用状态"
        );
        if (target.equals(application.getStatus())) {
            return applicationView(application);
        }
        if (!allowedStatusTransition(application.getStatus(), target)) {
            throw conflict("不允许的API应用状态转换：" + application.getStatus() + " -> " + target);
        }
        if ("active".equals(target) && isExpired(application.getExpiresAt())) {
            throw conflict("已过期的API应用不能启用");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateApiApplicationStatus(
            applicationId, application.getStatus(), target, principal.id(), now
        ) != 1) {
            throw conflict("API应用状态已被并发修改");
        }
        application.setStatus(target);
        application.setUpdateBy(principal.id());
        application.setUpdateTime(now);
        return applicationView(application);
    }

    /**
     * 判断sue凭据是否满足要求。
     *
     * @param applicationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public IssuedApiCredentialView issueCredential(
        Long applicationId,
        IssueApiCredentialRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = requireManage("create", applicationId);
        ApiApplication application = lockApplication(applicationId);
        ServiceAccount account = lockAccount(request.serviceAccountId());
        requireActive(application.getStatus(), application.getExpiresAt(), "API应用");
        requireActive(account.getStatus(), account.getExpiresAt(), "服务账号");
        Set<String> applicationScopes = scopes(application.getScopeJson());
        Set<String> credentialScopes = request.scopes().isEmpty()
            ? applicationScopes : normalizeScopes(request.scopes());
        if (!applicationScopes.containsAll(credentialScopes)) {
            throw badRequest("凭证scope不能超出API应用scope");
        }
        LocalDateTime expiresAt = credentialExpiry(
            request.expiresAt(), application.getExpiresAt(), account.getExpiresAt()
        );
        Long credentialId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            GeneratedCredential generated = secretGenerator.generate();
            ApiCredential credential = new ApiCredential();
            credential.setId(credentialId);
            credential.setApplicationId(applicationId);
            credential.setServiceAccountId(account.getId());
            credential.setKeyPrefix(generated.keyPrefix());
            credential.setSecretHash(generated.secretHash());
            credential.setScopeJson(scopeJson(credentialScopes));
            credential.setExpiresAt(expiresAt);
            credential.setCreatedBy(principal.id());
            credential.setCreatedAt(now);
            if (mapper.insertApiCredential(credential) == 1) {
                return new IssuedApiCredentialView(credentialView(credential), generated.rawSecret());
            }
        }
        throw conflict("API凭证随机前缀冲突，请重试");
    }

    /**
     * 处理{@code credentials}并返回对应结果。
     *
     * @param applicationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ApiCredentialView> credentials(Long applicationId, int limit) {
        requireManage("view", applicationId);
        requireApplication(applicationId);
        return mapper.selectApiCredentials(applicationId, limit).stream()
            .map(this::credentialView).toList();
    }

    /**
     * 处理revoke凭据相关逻辑。
     *
     * @param applicationId 资源标识
     * @param credentialId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeCredential(Long applicationId, Long credentialId) {
        requireManage("revoke", applicationId);
        requireApplication(applicationId);
        mapper.revokeApiCredential(applicationId, credentialId, LocalDateTime.now());
    }

    /**
     * 处理凭据Expiry并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param applicationExpiry 应用Expiry参数
     * @param accountExpiry 账户Expiry参数
     * @return 处理结果
     */
    private LocalDateTime credentialExpiry(
        LocalDateTime requested,
        LocalDateTime applicationExpiry,
        LocalDateTime accountExpiry
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime parentExpiry = earlier(applicationExpiry, accountExpiry);
        LocalDateTime result = requested == null
            ? earlier(now.plusDays(90), parentExpiry)
            : requested;
        if (result == null || !result.isAfter(now)) {
            throw badRequest("凭证过期时间必须晚于当前时间");
        }
        if (result.isAfter(now.plusDays(365))) {
            throw badRequest("凭证有效期不能超过365天");
        }
        if (parentExpiry != null && result.isAfter(parentExpiry)) {
            throw badRequest("凭证有效期不能超过API应用或服务账号有效期");
        }
        return result;
    }

    /**
     * 处理{@code earlier}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 处理结果
     */
    private LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    /**
     * 处理{@code allowedStatusTransition}并返回对应结果。
     *
     * @param current 当前参数
     * @param target {@code target}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean allowedStatusTransition(String current, String target) {
        return MUTABLE_STATUSES.contains(current)
            && (MUTABLE_STATUSES.contains(target) || "revoked".equals(target));
    }

    /**
     * 校验{@code Active}，并在条件不满足时终止处理。
     *
     * @param status 目标状态
     * @param expiresAt {@code expiresAt}参数
     * @param label {@code label}参数
     */
    private void requireActive(String status, LocalDateTime expiresAt, String label) {
        if (!"active".equals(status) || isExpired(expiresAt)) {
            throw conflict(label + "当前不可用于签发凭证");
        }
    }

    /**
     * 判断{@code Expired}是否满足要求。
     *
     * @param expiresAt {@code expiresAt}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
    }

    /**
     * 校验{@code FutureExpiry}，并在条件不满足时终止处理。
     *
     * @param expiresAt {@code expiresAt}参数
     * @param label {@code label}参数
     */
    private void validateFutureExpiry(LocalDateTime expiresAt, String label) {
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw badRequest(label + "过期时间必须晚于当前时间");
        }
    }

    /**
     * 校验{@code Owner}，并在条件不满足时终止处理。
     *
     * @param ownerId 资源标识
     */
    private void validateOwner(Long ownerId) {
        if (ownerId != null && userService.selectById(ownerId) == null) {
            throw notFound("负责人用户不存在");
        }
    }

    /**
     * 处理{@code normalizeScopes}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private Set<String> normalizeScopes(List<String> values) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (values == null || values.isEmpty()) {
            throw badRequest("API scope不能为空");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = requiredText(value, "API scope", 64).toLowerCase(Locale.ROOT);
            if (!SCOPES.contains(normalized)) {
                throw badRequest("API scope无效：" + normalized);
            }
            if (!result.add(normalized)) {
                throw badRequest("API scope不能重复");
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 处理{@code callbackUrl}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param appType 业务类型
     * @return 处理结果
     */
    private String callbackUrl(String value, String appType) {
        String normalized = optionalText(value, 1024);
        if (normalized == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw badRequest("API回调地址无效");
        }
        boolean allowedScheme = "https".equalsIgnoreCase(uri.getScheme())
            || ("internal".equals(appType) && "http".equalsIgnoreCase(uri.getScheme()));
        if (!allowedScheme || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw badRequest("API回调地址无效");
        }
        return uri.toASCIIString();
    }

    /**
     * 校验账户，并在条件不满足时终止处理。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    private ServiceAccount requireAccount(Long accountId) {
        ServiceAccount account = mapper.selectServiceAccount(accountId);
        if (account == null) {
            throw notFound("服务账号不存在");
        }
        return account;
    }

    /**
     * 处理lock账户并返回对应结果。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    private ServiceAccount lockAccount(Long accountId) {
        ServiceAccount account = mapper.lockServiceAccount(accountId);
        if (account == null) {
            throw notFound("服务账号不存在");
        }
        return account;
    }

    /**
     * 校验应用，并在条件不满足时终止处理。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    private ApiApplication requireApplication(Long applicationId) {
        ApiApplication application = mapper.selectApiApplication(applicationId);
        if (application == null) {
            throw notFound("API应用不存在");
        }
        return application;
    }

    /**
     * 处理lock应用并返回对应结果。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    private ApiApplication lockApplication(Long applicationId) {
        ApiApplication application = mapper.lockApiApplication(applicationId);
        if (application == null) {
            throw notFound("API应用不存在");
        }
        return application;
    }

    /**
     * 处理账户View并返回对应结果。
     *
     * @param account 账户参数
     * @return 处理结果
     */
    private ServiceAccountView accountView(ServiceAccount account) {
        String status = effectiveStatus(account.getStatus(), account.getExpiresAt());
        return new ServiceAccountView(
            account.getId(), account.getAccountKey(), account.getName(), account.getDescription(),
            account.getOwnerId(), status, account.getLastUsedAt(), account.getExpiresAt(),
            map(account.getMetadataJson()), account.getCreateTime()
        );
    }

    /**
     * 处理应用View并返回对应结果。
     *
     * @param application 应用参数
     * @return 处理结果
     */
    private ApiApplicationView applicationView(ApiApplication application) {
        return new ApiApplicationView(
            application.getId(), application.getAppKey(), application.getName(), application.getAppType(),
            effectiveStatus(application.getStatus(), application.getExpiresAt()), application.getOwnerId(),
            application.getCallbackUrl(), scopes(application.getScopeJson()), applicationConfig(application),
            application.getExpiresAt(),
            application.getCreateTime()
        );
    }

    /**
     * 处理应用ConfigJson并返回对应结果。
     *
     * @param appType 业务类型
     * @param config {@code config}参数
     * @return 处理结果
     */
    private String applicationConfigJson(String appType, Map<String, Object> config) {
        if ("embed".equals(appType)) {
            return documentJson(EmbedApplicationConfig.from(config).toMap(), "Embed应用配置");
        }
        if (config != null && !config.isEmpty()) {
            throw badRequest("仅Embed应用支持浏览器嵌入配置");
        }
        return "{}";
    }

    /**
     * 处理应用Config并返回对应结果。
     *
     * @param application 应用参数
     * @return 处理结果
     */
    private Map<String, Object> applicationConfig(ApiApplication application) {
        Map<String, Object> config = map(application.getExtraJson());
        return "embed".equals(application.getAppType())
            ? EmbedApplicationConfig.from(config).toMap() : Map.of();
    }

    /**
     * 处理凭据View并返回对应结果。
     *
     * @param credential 凭据参数
     * @return 处理结果
     */
    private ApiCredentialView credentialView(ApiCredential credential) {
        String status = credential.getRevokedAt() != null
            ? "revoked" : (isExpired(credential.getExpiresAt()) ? "expired" : "active");
        return new ApiCredentialView(
            credential.getId(), credential.getApplicationId(), credential.getServiceAccountId(),
            credential.getKeyPrefix(), scopes(credential.getScopeJson()), status,
            credential.getLastUsedAt(), credential.getExpiresAt(), credential.getCreatedAt()
        );
    }

    /**
     * 处理{@code grantView}并返回对应结果。
     *
     * @param grant {@code grant}参数
     * @return 处理结果
     */
    private ServiceAccountGrantView grantView(ServiceAccountGrant grant) {
        return new ServiceAccountGrantView(
            grant.getId(), grant.getServiceAccountId(), grant.getResourceType(),
            grant.getResourceId(), grant.getResourceKey(), grant.getAction(), grant.getEffect(),
            grant.getReason(), grant.getExpiresAt(), grant.getRevokedAt(), grant.getCreatedAt()
        );
    }

    /**
     * 处理{@code effectiveStatus}并返回对应结果。
     *
     * @param status 目标状态
     * @param expiresAt {@code expiresAt}参数
     * @return 处理结果
     */
    private String effectiveStatus(String status, LocalDateTime expiresAt) {
        return "active".equals(status) && isExpired(expiresAt) ? "expired" : status;
    }

    /**
     * 处理文档Json并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String documentJson(Map<String, Object> value, String label) {
        Map<String, Object> canonical = canonicalMap(value == null ? Map.of() : value, 0, label);
        String json = jsonMapper.writeValueAsString(canonical);
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
    private Map<String, Object> canonicalMap(
        Map<String, Object> value,
        int depth,
        String label
    ) {
        if (depth > 16) {
            throw badRequest(label + "嵌套过深");
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank() || key.length() > 128 || isSensitiveKey(key)) {
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
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
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
     * 判断{@code SensitiveKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return Set.of("secret", "password", "apikey", "authorization", "credential", "privatekey")
            .stream().anyMatch(normalized::contains)
            || Set.of("token", "accesstoken", "refreshtoken", "authtoken", "bearertoken", "sessiontoken")
            .contains(normalized);
    }

    /**
     * 处理范围Json并返回对应结果。
     *
     * @param scopes {@code scopes}参数
     * @return 处理结果
     */
    private String scopeJson(Set<String> scopes) {
        return jsonMapper.writeValueAsString(scopes.stream().sorted().toList());
    }

    /**
     * 处理{@code scopes}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private Set<String> scopes(String json) {
        return json == null || json.isBlank() ? Set.of() : jsonMapper.readValue(json, STRING_SET);
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
     * 校验{@code Manage}，并在条件不满足时终止处理。
     *
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @return 处理结果
     */
    private CurrentPrincipal requireManage(String action, Long resourceId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "iam", resourceId, null, action, ResourceState.ACTIVE, true, Set.of(), null
        ));
        return principal;
    }

    /**
     * 校验{@code dKey}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredKey(String value, String label) {
        String normalized = requiredText(value, label, 128);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code capabilityName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String capabilityName(String value, String label) {
        String normalized = requiredText(value, label, 32).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_]*")) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalCapabilityKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalCapabilityKey(String value) {
        String normalized = optionalText(value, 255);
        if (normalized != null && !normalized.matches("[A-Za-z0-9._:/-]+")) {
            throw badRequest("资源标识无效");
        }
        return normalized;
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
}
