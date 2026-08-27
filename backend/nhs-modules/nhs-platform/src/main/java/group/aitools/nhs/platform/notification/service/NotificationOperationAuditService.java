package group.aitools.nhs.platform.notification.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责通知操作审计相关的业务编排与领域规则处理。
 * Content- and credential-free audit trail for personal notification operations. */
@Slf4j
@Service
public class NotificationOperationAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public NotificationOperationAuditService(
        AgentAuditEventMapper mapper,
        PlatformIdGenerator idGenerator
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
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
        mapper.insertEvent(
            idGenerator.nextId(),
            principal.type() == PrincipalType.HUMAN ? "user" : "service_account",
            principal.id(),
            bounded(action, 64),
            bounded(resourceType, 32),
            resourceId,
            null,
            bounded(decision, 32),
            bounded(reason, 1000),
            bounded(summary, SUMMARY_LIMIT),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code recordSafely}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    public void recordSafely(
        CurrentPrincipal principal,
        String action,
        String resourceType,
        Long resourceId,
        String decision,
        String reason,
        String summary
    ) {
        try {
            record(principal, action, resourceType, resourceId, decision, reason, summary);
        } catch (RuntimeException exception) {
            log.error("Notification audit persistence failed for action {}", action, exception);
        }
    }

    /**
     * 处理record系统Safely相关逻辑。
     *
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    public void recordSystemSafely(
        String action,
        Long resourceId,
        String decision,
        String reason,
        String summary
    ) {
        recordSafely(
            new CurrentPrincipal(
                1L, "notification-worker", PrincipalType.SERVICE_ACCOUNT,
                java.util.Set.of(group.aitools.nhs.platform.iam.domain.PlatformRole.SERVICE_ACCOUNT)
            ),
            action, "notification_delivery", resourceId, decision, reason, summary
        );
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
}
