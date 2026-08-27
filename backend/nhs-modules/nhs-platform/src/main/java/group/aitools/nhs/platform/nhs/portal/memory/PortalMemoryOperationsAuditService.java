package group.aitools.nhs.platform.nhs.portal.memory;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责门户记忆Operations审计相关的业务编排与领域规则处理。
 * Persists content-free outcomes for administrator-triggered memory operations. */
@Service
public class PortalMemoryOperationsAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;

    public PortalMemoryOperationsAuditService(
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
     * @param action {@code action}参数
     * @param ownerId 资源标识
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        CurrentPrincipal principal,
        String action,
        Long ownerId,
        String decision,
        String reason,
        String summary
    ) {
        auditMapper.insertEvent(
            idGenerator.nextId(),
            principal.type() == PrincipalType.SERVICE_ACCOUNT ? "service_account" : "user",
            principal.id(),
            bounded(action),
            "memory_operations",
            ownerId,
            null,
            bounded(decision),
            bounded(reason),
            bounded(summary),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return sanitized.length() <= SUMMARY_LIMIT ? sanitized : sanitized.substring(0, SUMMARY_LIMIT);
    }
}
