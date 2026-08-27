package group.aitools.nhs.platform.portal.quota.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.portal.quota.domain.AgentQuotaPolicy;
import group.aitools.nhs.platform.portal.quota.mapper.AgentQuotaPolicyMapper;
import group.aitools.nhs.platform.portal.quota.persistence.row.QuotaRoleRow;
import group.aitools.nhs.platform.portal.quota.persistence.row.QuotaUserRow;
import group.aitools.nhs.platform.portal.quota.web.QuotaPolicyRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责门户Quota相关的业务编排与领域规则处理。
 * Resolves and persists Nhs-compatible monthly Token quotas. */
@Service
public class PortalQuotaService {

    private static final String PERIOD = "monthly";

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final AgentQuotaPolicyMapper mapper;

    public PortalQuotaService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentQuotaPolicyMapper mapper
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
    }

    /**
 * 处理{@code myQuota}并返回对应结果。
 * Returns the current user's effective monthly quota and durable usage. */
    public Map<String, Object> myQuota() {
        CurrentPrincipal principal = human();
        return status(principal.id());
    }

    /**
     * 处理策略并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> policy(String scopeType, Long scopeId) {
        requireAdmin();
        validateScope(scopeType, scopeId);
        validateTarget(scopeType, scopeId);
        AgentQuotaPolicy policy = mapper.selectPolicy(scopeType, scopeId);
        Map<String, Object> result = policyView(scopeType, scopeId, policy);
        if ("user".equals(scopeType) && scopeId != null) {
            result.put("effective", status(scopeId));
        }
        return result;
    }

    /**
     * 处理{@code upsert}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upsert(String scopeType, Long scopeId, QuotaPolicyRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireAdmin();
        validateScope(scopeType, scopeId);
        validateTarget(scopeType, scopeId);
        if (request == null) {
            throw new ServiceException("额度策略请求不能为空", HttpStatus.BAD_REQUEST);
        }
        boolean enabled = request.enabled() == null || request.enabled();
        AgentQuotaPolicy current = mapper.selectPolicy(scopeType, scopeId);
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            AgentQuotaPolicy created = new AgentQuotaPolicy();
            created.setId(idGenerator.nextId());
            created.setScopeType(scopeType);
            created.setScopeId(scopeId);
            created.setPeriod(PERIOD);
            created.setEnabled(enabled);
            created.setLimitTokens(request.limitTokens());
            created.setActionOnExceed("block");
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            if (mapper.insertPolicy(created) != 1) {
                throw new ServiceException("额度策略保存失败", HttpStatus.CONFLICT);
            }
            current = created;
        } else {
            current.setEnabled(enabled);
            current.setLimitTokens(request.limitTokens());
            current.setUpdatedAt(now);
            if (mapper.updatePolicy(current) != 1) {
                throw new ServiceException("额度策略已被其他操作修改", HttpStatus.CONFLICT);
            }
        }
        Map<String, Object> result = policyView(scopeType, scopeId, current);
        if ("user".equals(scopeType) && scopeId != null) {
            result.put("effective", status(scopeId));
        }
        return result;
    }

    /**
     * 删除{@code delete}。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String scopeType, Long scopeId) {
        requireAdmin();
        validateScope(scopeType, scopeId);
        validateTarget(scopeType, scopeId);
        AgentQuotaPolicy policy = mapper.selectPolicy(scopeType, scopeId);
        if (policy != null && mapper.deletePolicy(policy.getId()) != 1) {
            throw new ServiceException("额度策略已被其他操作修改", HttpStatus.CONFLICT);
        }
    }

    /**
 * 处理{@code exceeded}并返回对应结果。
 * Used by runtime calls to enforce a quota without exposing admin policy details. */
    public boolean exceeded(CurrentPrincipal principal) {
        if (principal == null || !principal.isHuman() || principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            return false;
        }
        return exceeded(principal.id());
    }

    /**
 * 处理{@code exceeded}并返回对应结果。
 * Checks a frozen runtime user without relying on request-thread login state. */
    public boolean exceeded(Long userId) {
        if (userId == null || userId <= 0 || mapper.selectUser(userId) == null) {
            return false;
        }
        Map<String, Object> value = status(userId);
        Object limit = value.get("limit_tokens");
        return limit instanceof Number && ((Number) limit).longValue() <= ((Number) value.get("used_tokens")).longValue();
    }

    /**
 * 校验{@code Available}，并在条件不满足时终止处理。
 * Rejects a new model invocation after the effective monthly limit is exhausted. */
    public void requireAvailable(Long userId) {
        if (userId == null || userId <= 0 || mapper.selectUser(userId) == null) {
            return;
        }
        Map<String, Object> value = status(userId);
        Object limit = value.get("limit_tokens");
        long used = ((Number) value.get("used_tokens")).longValue();
        if (limit instanceof Number number && used >= number.longValue()) {
            throw new ServiceException(
                "本月 Token 额度已用尽（已用 " + used + " / 上限 " + number.longValue() + "），请联系管理员调整额度",
                429
            );
        }
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> status(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime exclusiveEnd = start.plusMonths(1);
        long used = Math.max(0L, value(mapper.selectMonthlyUsage(userId, start, exclusiveEnd)));
        Effective effective = resolve(userId);
        Long remaining = effective.limitTokens == null
            ? null : Math.max(0L, effective.limitTokens - used);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", PERIOD);
        result.put("period_start", start.toLocalDate().toString());
        result.put("period_end", exclusiveEnd.toLocalDate().minusDays(1).toString());
        result.put("used_tokens", used);
        result.put("limit_tokens", effective.limitTokens);
        result.put("remaining_tokens", remaining);
        result.put("source", effective.source);
        result.put("source_label", effective.sourceLabel);
        result.put("action_on_exceed", "block");
        result.put("is_admin_bypass", effective.adminBypass);
        result.put("policy_enabled", effective.policyEnabled);
        return result;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private Effective resolve(Long userId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<QuotaRoleRow> roles = mapper.selectRoles(userId);
        boolean targetAdmin = roles.stream().anyMatch(role -> isAdminRole(role.getRoleKey()));
        if (targetAdmin) {
            return new Effective(null, "admin_bypass", "系统管理员", true, true);
        }

        AgentQuotaPolicy user = mapper.selectPolicy("user", userId);
        if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
            return new Effective(user.getLimitTokens(), "user",
                user.getLimitTokens() == null ? "用户专属（不限额）" : "用户专属", false, true);
        }

        List<Long> roleIds = roles.stream().map(QuotaRoleRow::getRoleId).filter(id -> id != null).toList();
        List<AgentQuotaPolicy> rolePolicies = roleIds.isEmpty()
            ? List.of() : mapper.selectPolicies("role", roleIds);
        Map<Long, AgentQuotaPolicy> byRole = new LinkedHashMap<>();
        for (AgentQuotaPolicy policy : rolePolicies) {
            if (policy.getScopeId() != null) {
                byRole.put(policy.getScopeId(), policy);
            }
        }
        List<EffectiveRole> roleLimits = new ArrayList<>();
        for (QuotaRoleRow role : roles) {
            AgentQuotaPolicy policy = byRole.get(role.getRoleId());
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) {
                continue;
            }
            if (policy.getLimitTokens() == null) {
                return new Effective(null, "role", "角色：" + role.getRoleName() + "（不限额）", false, true);
            }
            roleLimits.add(new EffectiveRole(policy.getLimitTokens(), role.getRoleName()));
        }
        if (!roleLimits.isEmpty()) {
            EffectiveRole best = roleLimits.stream().max(Comparator.comparing(EffectiveRole::limitTokens)).orElseThrow();
            return new Effective(best.limitTokens(), "role", "角色：" + best.roleName(), false, true);
        }

        AgentQuotaPolicy system = mapper.selectPolicy("system", null);
        if (system != null && Boolean.TRUE.equals(system.getEnabled())) {
            return new Effective(system.getLimitTokens(), "system",
                system.getLimitTokens() == null ? "系统默认（不限额）" : "系统默认", false, true);
        }
        return new Effective(null, "unlimited", null, false, true);
    }

    /**
     * 处理策略View并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param policy 策略参数
     * @return 处理结果
     */
    private Map<String, Object> policyView(String scopeType, Long scopeId, AgentQuotaPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope_type", scopeType);
        result.put("scope_id", scopeId);
        result.put("enabled", policy != null && Boolean.TRUE.equals(policy.getEnabled()));
        result.put("limit_tokens", policy == null ? null : policy.getLimitTokens());
        result.put("action_on_exceed", policy == null ? "block" : policy.getActionOnExceed());
        result.put("inherit", policy == null);
        return result;
    }

    /**
     * 校验用户，并在条件不满足时终止处理。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    private QuotaUserRow requireUser(Long userId) {
        QuotaUserRow user = mapper.selectUser(userId);
        if (user == null) {
            throw new ServiceException("用户不存在", HttpStatus.NOT_FOUND);
        }
        return user;
    }

    /**
     * 校验角色，并在条件不满足时终止处理。
     *
     * @param roleId 资源标识
     * @return 处理结果
     */
    private QuotaRoleRow requireRole(Long roleId) {
        QuotaRoleRow role = mapper.selectRole(roleId);
        if (role == null) {
            throw new ServiceException("角色不存在或已停用", HttpStatus.NOT_FOUND);
        }
        return role;
    }

    /**
     * 校验{@code Target}，并在条件不满足时终止处理。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void validateTarget(String scopeType, Long scopeId) {
        if ("user".equals(scopeType)) {
            requireUser(scopeId);
        } else if ("role".equals(scopeType)) {
            requireRole(scopeId);
        }
    }

    /**
     * 判断Admin角色是否满足要求。
     *
     * @param roleKey 角色Key参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAdminRole(String roleKey) {
        if (roleKey == null) {
            return false;
        }
        String normalized = roleKey.strip().toLowerCase(java.util.Locale.ROOT);
        return PlatformRole.PLATFORM_ADMIN.key().equals(normalized) || "superadmin".equals(normalized);
    }

    /**
     * 处理{@code human}并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal human() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能访问个人额度", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 校验{@code Admin}，并在条件不满足时终止处理。
     */
    private void requireAdmin() {
        CurrentPrincipal principal = human();
        if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理额度策略", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验范围，并在条件不满足时终止处理。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void validateScope(String scopeType, Long scopeId) {
        if (!List.of("system", "user", "role").contains(scopeType)
            || ("system".equals(scopeType) && scopeId != null)
            || (!"system".equals(scopeType) && (scopeId == null || scopeId <= 0))) {
            throw new ServiceException("额度策略范围无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long value(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 封装{@code Effective}相关的不可变数据。
     */
    private record Effective(
        Long limitTokens,
        String source,
        String sourceLabel,
        boolean adminBypass,
        boolean policyEnabled
    ) {
    }

    /**
     * 封装Effective角色相关的不可变数据。
     */
    private record EffectiveRole(Long limitTokens, String roleName) {
    }
}
