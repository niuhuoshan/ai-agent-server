package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责NhsV1操作审计相关的业务编排与领域规则处理。
 *
 * Content-free audit writer for the Nhs V1 portal compatibility boundary.
 *
 * <p>The dataset menu can contain user-authored questions and the generated-file
 * route carries a bearer capability. Neither value is written to the audit
 * table; callers provide bounded summaries and this service exposes a short
 * fingerprint helper when an opaque identifier needs correlation.</p>
 */
@Service
public class NhsV1OperationAuditService {

    private static final int SUMMARY_LIMIT = 1000;
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final String AUDIT_UNAVAILABLE_MESSAGE = "操作审计写入失败，请稍后重试";

    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final CurrentPrincipalProvider principalProvider;

    public NhsV1OperationAuditService(
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        CurrentPrincipalProvider principalProvider
    ) {
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.principalProvider = principalProvider;
    }

    /**
 * 处理record当前相关逻辑。
 * Records an authenticated portal action in an independent transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCurrent(
        String action,
        String resourceType,
        Long resourceId,
        String decision,
        String reason,
        String summary
    ) {
        CurrentPrincipal principal = requiredCurrentPrincipal();
        record(principal, action, resourceType, resourceId, decision, reason, summary);
    }

    /**
 * 处理record应用相关逻辑。
 * Records a public capability operation without inventing a user identity. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordApplication(
        String action,
        String resourceType,
        Long resourceId,
        String decision,
        String reason,
        String summary
    ) {
        record(null, action, resourceType, resourceId, decision, reason, summary);
    }

    /**
 * 处理{@code record}相关逻辑。
 * Content-free audit operation used by tests and other Nhs adapters. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        CurrentPrincipal principal,
        String action,
        String resourceType,
        Long resourceId,
        String decision,
        String reason,
        String summary
    ) {
        try {
            String actorType = principal == null
                ? "application"
                : principal.type() == PrincipalType.HUMAN ? "user" : "service_account";
            int inserted = auditMapper.insertEvent(
                idGenerator.nextId(),
                actorType,
                principal == null ? null : principal.id(),
                bounded(action, 64),
                bounded(resourceType, 32),
                resourceId,
                null,
                bounded(decision, 20),
                bounded(reason, SUMMARY_LIMIT),
                bounded(summary, SUMMARY_LIMIT),
                LocalDateTime.now()
            );
            if (inserted != 1) {
                throw new IllegalStateException("Expected one audit row but inserted " + inserted);
            }
        } catch (RuntimeException exception) {
            throw unavailable("persist_event", exception);
        }
    }

    /**
 * 处理{@code fingerprint}并返回对应结果。
 * Returns a non-reversible correlation key for an opaque capability/id. */
    public String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return ContentHashing.sha256(value.strip()).substring(0, 16);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String bounded(String value, int limit) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
    }

    /**
     * 校验当前操作主体，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requiredCurrentPrincipal() {
        try {
            CurrentPrincipal principal = principalProvider.currentPrincipal();
            if (principal == null) {
                throw new IllegalStateException("Current principal is unavailable");
            }
            return principal;
        } catch (RuntimeException exception) {
            throw unavailable("resolve_principal", exception);
        }
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param stage {@code stage}参数
     * @param cause {@code cause}参数
     * @return 处理结果
     */
    private ServiceException unavailable(String stage, RuntimeException cause) {
        ServiceException failure = new ServiceException(AUDIT_UNAVAILABLE_MESSAGE, SERVICE_UNAVAILABLE);
        failure.setDetailMessage("Nhs V1 audit failure at " + stage + ": " + cause.getClass().getSimpleName());
        failure.initCause(cause);
        return failure;
    }
}
