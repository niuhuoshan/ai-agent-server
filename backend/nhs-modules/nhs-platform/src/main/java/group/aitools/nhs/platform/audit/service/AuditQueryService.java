package group.aitools.nhs.platform.audit.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditQueryMapper;
import group.aitools.nhs.platform.audit.web.AuditEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责审计查询相关的业务编排与领域规则处理。
 * Platform-administrator audit search with bounded range and cursor pagination. */
@Service
public class AuditQueryService {

    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Set<String> ACTOR_TYPES = Set.of(
        "user", "service_account", "application", "agent", "system"
    );
    private static final Set<String> DECISIONS = Set.of(
        "allow", "deny", "approval_required", "success", "failure"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentAuditQueryMapper auditMapper;

    /**
     * 创建 {@code AuditQueryService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param auditMapper 审计Mapper参数
     */
    public AuditQueryService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentAuditQueryMapper auditMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.auditMapper = auditMapper;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AuditEventView> search(
        String actorType,
        Long actorId,
        String action,
        String resourceType,
        Long resourceId,
        Long taskId,
        Long runId,
        String decision,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        Long beforeId,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "audit", null, "audit-events", "list", ResourceState.ACTIVE, true, Set.of()
        ));
        LocalDateTime effectiveTo = createdTo == null ? LocalDateTime.now().plusSeconds(1) : createdTo;
        LocalDateTime effectiveFrom = createdFrom == null ? effectiveTo.minusDays(30) : createdFrom;
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new ServiceException("审计开始时间必须早于结束时间", HttpStatus.BAD_REQUEST);
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
            throw new ServiceException("单次审计检索时间范围不能超过90天", HttpStatus.BAD_REQUEST);
        }
        return auditMapper.search(
            optionalEnum(actorType, ACTOR_TYPES, "主体类型"),
            actorId,
            optionalToken(action, 64, "操作"),
            optionalToken(resourceType, 32, "资源类型"),
            resourceId,
            taskId,
            runId,
            optionalEnum(decision, DECISIONS, "决策"),
            effectiveFrom,
            effectiveTo,
            beforeId,
            limit
        ).stream().map(AuditEventView::from).toList();
    }

    /**
     * 处理{@code optionalEnum}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String optionalEnum(String value, Set<String> allowed, String field) {
        String normalized = optionalToken(value, 32, field);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new ServiceException(field + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理optional令牌并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String optionalToken(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0
            || !normalized.matches("[a-z0-9_.:-]+")) {
            throw new ServiceException(field + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
