package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.mapper.AgentNotificationMapper;
import group.aitools.nhs.platform.notification.persistence.row.TaskNotificationOwnerRow;
import group.aitools.nhs.platform.notification.web.NotificationView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责通知相关的业务编排与领域规则处理。
 * Human-only personal inbox and idempotent internal notification publisher. */
@Service
public class NotificationApplicationService {

    private static final Set<String> CATEGORIES = Set.of(
        "task", "approval", "run", "artifact", "acceptance", "system"
    );
    private static final Set<String> LEVELS = Set.of("info", "success", "warning", "error");

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final AgentNotificationMapper notificationMapper;
    private final NotificationOperationAuditService auditService;

    /**
     * 创建 {@code NotificationApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param notificationMapper 通知Mapper参数
     */
    public NotificationApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentNotificationMapper notificationMapper
    ) {
        this(principalProvider, idGenerator, notificationMapper, null);
    }

    /**
     * 创建 {@code NotificationApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param notificationMapper 通知Mapper参数
     * @param auditService 审计Service参数
     */
    @Autowired
    public NotificationApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentNotificationMapper notificationMapper,
        NotificationOperationAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.notificationMapper = notificationMapper;
        this.auditService = auditService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param category {@code category}参数
     * @param unreadOnly {@code unreadOnly}参数
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<NotificationView> list(
        String category,
        boolean unreadOnly,
        Long beforeId,
        int limit
    ) {
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        String normalizedCategory = optionalEnum(category, CATEGORIES, "通知类别");
        return notificationMapper.selectInbox(
            principal.id(), normalizedCategory, unreadOnly, beforeId, limit
        ).stream().map(NotificationView::from).toList();
    }

    /**
 * 查询{@code Page}列表。
 * Nhs portal pagination uses an offset instead of the platform cursor. */
    public List<AgentNotification> listPage(
        int offset, int limit, boolean unreadOnly
    ) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("通知分页参数无效");
        }
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        return notificationMapper.selectInboxPage(
            principal.id(), null, unreadOnly, offset, limit
        );
    }

    /**
     * 处理{@code unreadCount}并返回对应结果。
     *
     * @return 处理结果
     */
    public long unreadCount() {
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        return notificationMapper.countUnread(principal.id());
    }

    /**
     * 处理{@code markRead}并返回对应结果。
     *
     * @param notificationId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public NotificationView markRead(Long notificationId) {
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        if (notificationMapper.markRead(notificationId, principal.id(), LocalDateTime.now()) != 1) {
            throw new ServiceException("通知不存在", HttpStatus.NOT_FOUND);
        }
        AgentNotification notification = notificationMapper.selectOwned(notificationId, principal.id());
        if (notification == null) {
            throw new ServiceException("通知不存在", HttpStatus.NOT_FOUND);
        }
        return NotificationView.from(notification);
    }

    /**
     * 处理{@code markAllRead}并返回对应结果。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        int updated = notificationMapper.markAllRead(principal.id(), LocalDateTime.now());
        audit(principal, "notification.inbox.read_all", null, "success", "updated=" + updated);
        return updated;
    }

    /**
     * 删除{@code Read}。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteRead() {
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        int deleted = notificationMapper.deleteRead(principal.id());
        audit(principal, "notification.inbox.delete_read", null, "success", "deleted=" + deleted);
        return deleted;
    }

    /**
     * 删除{@code One}。
     *
     * @param notificationId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOne(Long notificationId) {
        if (notificationId == null || notificationId <= 0) {
            throw new ServiceException("通知ID无效", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = requireHuman(principalProvider.currentPrincipal());
        if (notificationMapper.deleteOne(notificationId, principal.id()) != 1) {
            throw new ServiceException("通知不存在", HttpStatus.NOT_FOUND);
        }
        audit(principal, "notification.inbox.delete_one", notificationId, "success", "owner_scoped=true");
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param recipient {@code recipient}参数
     * @param message 待处理内容
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentNotification publish(NotificationRecipient recipient, NotificationMessage message) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (recipient.type() != PrincipalType.HUMAN) {
            throw new IllegalArgumentException("service accounts do not have a user notification inbox");
        }
        String eventKey = requiredText(message.eventKey(), 160, "通知事件键");
        AgentNotification existing = notificationMapper.selectByEventKey(recipient.id(), eventKey);
        if (existing != null) {
            return existing;
        }

        AgentNotification notification = new AgentNotification();
        notification.setId(idGenerator.nextId());
        notification.setUserId(recipient.id());
        notification.setEventKey(eventKey);
        notification.setCategory(requiredEnum(message.category(), CATEGORIES, "通知类别"));
        notification.setLevel(requiredEnum(message.level(), LEVELS, "通知级别"));
        notification.setTitle(requiredText(message.title(), 255, "通知标题"));
        notification.setContent(optionalText(message.content(), 2000, "通知内容"));
        notification.setResourceType(optionalText(message.resourceType(), 32, "资源类型"));
        if (message.resourceId() != null && message.resourceId() <= 0) {
            throw new IllegalArgumentException("resource id must be positive");
        }
        notification.setResourceId(message.resourceId());
        notification.setCreatedAt(LocalDateTime.now());
        if (notificationMapper.insertNotification(notification) == 1) {
            return notification;
        }
        AgentNotification raced = notificationMapper.selectByEventKey(recipient.id(), eventKey);
        if (raced == null) {
            throw new IllegalStateException("notification idempotency conflict");
        }
        return raced;
    }

    /**
 * 处理publish任务Owner相关逻辑。
 * Routes a task event only when the persisted owner is a human identity. */
    @Transactional(rollbackFor = Exception.class)
    public void publishTaskOwner(Long taskId, NotificationMessage message) {
        TaskNotificationOwnerRow owner = notificationMapper.selectTaskOwner(taskId);
        if (owner == null || owner.getOwnerId() == null
            || !"human".equals(owner.getOwnerPrincipalType())) {
            return;
        }
        publish(new NotificationRecipient(owner.getOwnerId(), PrincipalType.HUMAN), message);
    }

    /**
 * 处理publish审批Audience相关逻辑。
 * Approval users are resolved from active NHS human users, never machine identities. */
    @Transactional(rollbackFor = Exception.class)
    public void publishApprovalAudience(NotificationMessage message) {
        for (Long userId : notificationMapper.selectApprovalRecipientIds()) {
            if (userId != null && userId > 0) {
                publish(new NotificationRecipient(userId, PrincipalType.HUMAN), message);
            }
        }
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private CurrentPrincipal requireHuman(CurrentPrincipal principal) {
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能访问个人通知收件箱", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @param decision {@code decision}参数
     * @param summary {@code summary}参数
     */
    private void audit(
        CurrentPrincipal principal,
        String action,
        Long resourceId,
        String decision,
        String summary
    ) {
        if (auditService != null) {
            auditService.recordSafely(
                principal, action, "notification", resourceId, decision, "owner_scoped", summary
            );
        }
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
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredEnum(value, allowed, field);
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> allowed, String field) {
        String normalized = requiredText(value, 32, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + "无效");
        }
        return normalized;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maxLength, String field) {
        String normalized = optionalText(value, maxLength, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String optionalText(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + "无效");
        }
        return normalized;
    }
}
