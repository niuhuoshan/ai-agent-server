package group.aitools.nhs.platform.audit.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责授权审计相关的业务编排与领域规则处理。
 * Persists decisions in a separate transaction so denied operations remain auditable. */
@Service
public class AuthorizationAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;

    public AuthorizationAuditService(
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator
    ) {
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @param decision {@code decision}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        CurrentPrincipal principal,
        PermissionContext context,
        AuthorizationDecision decision
    ) {
        String actorType = principal.type() == PrincipalType.HUMAN ? "user" : "service_account";
        auditMapper.insertEvent(
            idGenerator.nextId(),
            actorType,
            principal.id(),
            context.action(),
            context.resourceType(),
            context.resourceId(),
            context.taskId(),
            decision.effect().name().toLowerCase(),
            truncate(decision.reasonCode() + ": " + decision.reason()),
            truncate("resourceKey=" + context.resourceKey()),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, SUMMARY_LIMIT);
    }
}
