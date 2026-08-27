package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.domain.ApiCredentialAuthenticationRow;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示接口凭据Authenticator相关的领域对象。
 * Authenticates a raw API secret directly as an isolated service-account principal. */
@Service
public class ApiCredentialAuthenticator {

    private static final Pattern SECRET_FORMAT = Pattern.compile(
        "agk_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{43}"
    );
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
    };

    private final MachineIdentityMapper mapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ApiCredentialAuthenticator} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ApiCredentialAuthenticator(MachineIdentityMapper mapper, JsonMapper jsonMapper) {
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code authenticate}并返回对应结果。
     *
     * @param rawSecret {@code rawSecret}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthenticatedServiceAccount authenticate(String rawSecret) {
        if (rawSecret == null || !SECRET_FORMAT.matcher(rawSecret).matches()) {
            throw unauthorized();
        }
        return authenticateRow(mapper.selectCredentialAuthentication(ContentHashing.sha256(rawSecret)));
    }

    /**
 * 处理authenticate凭据并返回对应结果。
 * Revalidates the credential that originally minted a short-lived browser capability. */
    @Transactional(rollbackFor = Exception.class)
    public AuthenticatedServiceAccount authenticateCredential(Long credentialId) {
        if (credentialId == null || credentialId <= 0) {
            throw unauthorized();
        }
        return authenticateRow(mapper.selectCredentialAuthenticationById(credentialId));
    }

    /**
     * 处理{@code authenticateRow}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private AuthenticatedServiceAccount authenticateRow(ApiCredentialAuthenticationRow row) {
        LocalDateTime now = LocalDateTime.now();
        if (row == null
            || row.getRevokedAt() != null
            || !"active".equals(row.getApplicationStatus())
            || !"active".equals(row.getServiceAccountStatus())
            || expired(row.getCredentialExpiresAt(), now)
            || expired(row.getApplicationExpiresAt(), now)
            || expired(row.getServiceAccountExpiresAt(), now)) {
            throw unauthorized();
        }
        Set<String> applicationScopes = scopes(row.getApplicationScopeJson());
        Set<String> credentialScopes = scopes(row.getCredentialScopeJson());
        LinkedHashSet<String> effectiveScopes = new LinkedHashSet<>(credentialScopes);
        effectiveScopes.retainAll(applicationScopes);
        if (effectiveScopes.isEmpty()
            || mapper.touchCredential(row.getCredentialId(), now) != 1
            || mapper.touchServiceAccount(row.getServiceAccountId(), now) != 1) {
            throw unauthorized();
        }
        String name = row.getAccountName() == null || row.getAccountName().isBlank()
            ? row.getAccountKey() : row.getAccountName();
        CurrentPrincipal principal = new CurrentPrincipal(
            row.getServiceAccountId(), name, PrincipalType.SERVICE_ACCOUNT,
            Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        return new AuthenticatedServiceAccount(
            principal, row.getApplicationId(), row.getAppKey(), row.getApplicationType(),
            row.getCredentialId(), effectiveScopes
        );
    }

    /**
     * 处理{@code scopes}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private Set<String> scopes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return jsonMapper.readValue(json, STRING_SET);
        } catch (RuntimeException exception) {
            throw unauthorized();
        }
    }

    /**
     * 处理{@code expired}并返回对应结果。
     *
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean expired(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * 处理{@code unauthorized}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException unauthorized() {
        return new ServiceException("API凭证无效或已失效", HttpStatus.UNAUTHORIZED);
    }
}
