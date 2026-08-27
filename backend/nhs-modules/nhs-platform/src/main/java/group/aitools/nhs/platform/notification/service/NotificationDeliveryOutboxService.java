package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.NotificationDeliveryOutboxEvent;
import group.aitools.nhs.platform.notification.mapper.NotificationDeliveryOutboxMapper;
import group.aitools.nhs.platform.notification.web.NotificationDeliveryView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责通知DeliveryOutbox相关的业务编排与领域规则处理。
 * Creates and exposes durable external-channel notification deliveries. */
@Service
public class NotificationDeliveryOutboxService {

    private static final Set<String> CHANNELS = Set.of("dingtalk", "wechat_work", "email");

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final NotificationDeliveryOutboxMapper mapper;
    private final JsonMapper jsonMapper;
    private final NotificationOperationAuditService auditService;

    public NotificationDeliveryOutboxService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        NotificationDeliveryOutboxMapper mapper,
        JsonMapper jsonMapper,
        NotificationOperationAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
    }

    /**
     * 处理{@code enqueueChannels}相关逻辑。
     *
     * @param userId 资源标识
     * @param sourceEventKey 数据源事件Key参数
     * @param channels {@code channels}参数
     * @param title {@code title}参数
     * @param content 待处理内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueChannels(
        Long userId,
        String sourceEventKey,
        List<String> channels,
        String title,
        String content
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("notification delivery user is invalid");
        }
        for (String channel : normalizeChannels(channels)) {
            enqueue(userId, sourceEventKey, channel, title, content, null);
        }
    }

    /**
     * 处理{@code enqueue}并返回对应结果。
     *
     * @param userId 资源标识
     * @param sourceEventKey 数据源事件Key参数
     * @param channelType 业务类型
     * @param title {@code title}参数
     * @param content 待处理内容
     * @param recipient {@code recipient}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public NotificationDeliveryOutboxEvent enqueue(
        Long userId,
        String sourceEventKey,
        String channelType,
        String title,
        String content,
        String recipient
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("notification delivery user is invalid");
        }
        String channel = channel(channelType);
        String sourceKey = required(sourceEventKey, 256, "通知来源事件键");
        NotificationDeliveryPayload payload = new NotificationDeliveryPayload(
            userId,
            sourceKey,
            channel,
            required(title, 255, "通知标题"),
            optional(content, 16_384, "通知内容"),
            optional(recipient, 2_000, "通知收件人")
        );
        LocalDateTime now = utcNow();
        NotificationDeliveryOutboxEvent event = new NotificationDeliveryOutboxEvent();
        event.setId(idGenerator.nextId());
        event.setUserId(userId);
        event.setEventKey(deliveryEventKey(userId, sourceKey, channel));
        event.setPayloadJson(jsonMapper.writeValueAsString(payload));
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        if (mapper.insert(event) == 1) {
            return event;
        }
        NotificationDeliveryOutboxEvent existing = mapper.selectByEventKey(event.getEventKey());
        if (existing == null) {
            throw new ServiceException("通知投递幂等键冲突", HttpStatus.CONFLICT);
        }
        return existing;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<NotificationDeliveryView> list(int limit) {
        CurrentPrincipal principal = currentHuman();
        return mapper.selectOwned(principal.id(), Math.min(Math.max(limit, 1), 100)).stream()
            .map(this::view)
            .toList();
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param deliveryId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public NotificationDeliveryView retry(Long deliveryId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = currentHuman();
        if (deliveryId == null || deliveryId <= 0) {
            throw new ServiceException("通知投递ID无效", HttpStatus.BAD_REQUEST);
        }
        NotificationDeliveryOutboxEvent existing = mapper.selectOwnedById(deliveryId, principal.id());
        if (existing == null) {
            throw new ServiceException("通知投递不存在", HttpStatus.NOT_FOUND);
        }
        if (!"failed".equals(existing.getStatus())) {
            throw new ServiceException("只有失败的通知投递可以重试", HttpStatus.CONFLICT);
        }
        if (mapper.retryOwned(deliveryId, principal.id(), utcNow()) != 1) {
            throw new ServiceException("通知投递状态已变化", HttpStatus.CONFLICT);
        }
        NotificationDeliveryOutboxEvent retried = mapper.selectOwnedById(deliveryId, principal.id());
        auditService.recordSafely(
            principal, "notification.delivery.retry", "notification_delivery", deliveryId,
            "success", "manual_retry", "channel=" + payload(retried).channelType()
        );
        return view(retried);
    }

    /**
     * 处理{@code payload}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    NotificationDeliveryPayload payload(NotificationDeliveryOutboxEvent event) {
        try {
            NotificationDeliveryPayload payload = jsonMapper.readValue(
                event.getPayloadJson(), NotificationDeliveryPayload.class
            );
            if (payload == null || payload.userId() == null
                || !payload.userId().equals(event.getUserId())
                || !CHANNELS.contains(payload.channelType())
                || payload.sourceEventKey() == null || payload.sourceEventKey().isBlank()
                || payload.title() == null || payload.title().isBlank()) {
                throw new IllegalArgumentException("通知投递载荷无效");
            }
            return payload;
        } catch (RuntimeException exception) {
            throw new ServiceException("通知投递载荷无效", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private NotificationDeliveryView view(NotificationDeliveryOutboxEvent event) {
        return NotificationDeliveryView.from(event, payload(event));
    }

    /**
     * 处理{@code normalizeChannels}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private Set<String> normalizeChannels(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value == null || value.isBlank() || "inbox".equalsIgnoreCase(value)
                || "portal".equalsIgnoreCase(value)) {
                continue;
            }
            result.add(channel(value));
        }
        return result;
    }

    /**
     * 处理delivery事件Key并返回对应结果。
     *
     * @param userId 资源标识
     * @param sourceEventKey 数据源事件Key参数
     * @param channel {@code channel}参数
     * @return 处理结果
     */
    private String deliveryEventKey(Long userId, String sourceEventKey, String channel) {
        String value = userId + "\n" + sourceEventKey + "\n" + channel;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "notification:" + channel + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * 处理当前Human并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal currentHuman() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("服务账号不能访问个人通知投递", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code channel}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String channel(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new ServiceException("不支持的通知渠道", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param name 名称
     * @return 处理结果
     */
    private String required(String value, int maximum, String name) {
        String normalized = optional(value, maximum, name);
        if (normalized == null) {
            throw new ServiceException(name + "不能为空", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code optional}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param name 名称
     * @return 处理结果
     */
    private String optional(String value, int maximum, String name) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(name + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
