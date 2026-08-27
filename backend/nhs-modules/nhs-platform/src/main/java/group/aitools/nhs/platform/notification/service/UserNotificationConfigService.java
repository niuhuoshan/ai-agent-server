package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.UserNotificationChannelConfig;
import group.aitools.nhs.platform.notification.mapper.UserNotificationChannelConfigMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责用户通知Config相关的业务编排与领域规则处理。
 * Owner-scoped personal notification configuration and real test delivery orchestration. */
@Service
public class UserNotificationConfigService {

    private static final String MASK = "******";
    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final Set<String> CHANNELS = Set.of("dingtalk", "wechat_work", "email");
    private static final Set<String> DINGTALK_KEYS = Set.of(
        "is_enabled", "webhook_url", "secret"
    );
    private static final Set<String> WECHAT_KEYS = Set.of("is_enabled", "webhook_url");
    private static final Set<String> EMAIL_KEYS = Set.of(
        "is_enabled", "smtp_host", "smtp_port", "smtp_user", "smtp_password",
        "sender_name", "recipients"
    );
    private static final Pattern EMAIL = Pattern.compile(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final UserNotificationChannelConfigMapper mapper;
    private final NotificationConfigSecretCodec secretCodec;
    private final NotificationChannelSender sender;
    private final JsonMapper jsonMapper;
    private final NotificationOperationAuditService auditService;

    /**
     * 创建 {@code UserNotificationConfigService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param secretCodec {@code secretCodec}参数
     * @param sender {@code sender}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public UserNotificationConfigService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        UserNotificationChannelConfigMapper mapper,
        NotificationConfigSecretCodec secretCodec,
        NotificationChannelSender sender,
        JsonMapper jsonMapper
    ) {
        this(principalProvider, idGenerator, mapper, secretCodec, sender, jsonMapper, null);
    }

    /**
     * 创建 {@code UserNotificationConfigService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param secretCodec {@code secretCodec}参数
     * @param sender {@code sender}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param auditService 审计Service参数
     */
    @Autowired
    public UserNotificationConfigService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        UserNotificationChannelConfigMapper mapper,
        NotificationConfigSecretCodec secretCodec,
        NotificationChannelSender sender,
        JsonMapper jsonMapper,
        NotificationOperationAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.secretCodec = secretCodec;
        this.sender = sender;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
    }

    /**
     * 处理{@code configs}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Map<String, Object>> configs() {
        long userId = currentUserId();
        Map<String, UserNotificationChannelConfig> rows = new LinkedHashMap<>();
        for (UserNotificationChannelConfig row : mapper.selectByUser(userId)) {
            if (CHANNELS.contains(row.getChannelType())) {
                rows.put(row.getChannelType(), row);
            }
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String channel : List.of("dingtalk", "wechat_work", "email")) {
            StoredConfig stored = stored(rows.get(channel));
            result.put(channel, view(channel, stored));
        }
        return result;
    }

    /**
     * 保存{@code save}。
     *
     * @param channelType 业务类型
     * @param requested {@code requested}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(String channelType, Map<String, Object> requested) {
        String channel = channel(channelType);
        long userId = currentUserId();
        UserNotificationChannelConfig existingRow = mapper.selectOne(userId, channel);
        StoredConfig existing = stored(existingRow);
        NormalizedConfig normalized = normalize(channel, requested, existing, false);
        LocalDateTime now = LocalDateTime.now();

        UserNotificationChannelConfig row = new UserNotificationChannelConfig();
        row.setId(existingRow == null ? idGenerator.nextId() : existingRow.getId());
        row.setUserId(userId);
        row.setChannelType(channel);
        row.setEnabled(normalized.enabled());
        row.setConfigJson(json(normalized.publicConfig()));
        row.setSecretPayload(secretCodec.encrypt(normalized.secrets()));
        row.setCreatedAt(existingRow == null ? now : existingRow.getCreatedAt());
        row.setUpdatedAt(now);
        if (mapper.upsert(row) != 1) {
            throw new ServiceException("通知配置保存失败", HttpStatus.ERROR);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "配置保存成功");
        result.put("channel_type", channel);
        result.put("config", view(channel, new StoredConfig(
            normalized.enabled(), normalized.publicConfig(), normalized.secrets()
        )));
        audit(principalProvider.currentPrincipal(), "notification.channel.save", null, "success", channel);
        return result;
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param channelType 业务类型
     * @param requested {@code requested}参数
     * @return 处理结果
     */
    public Map<String, Object> test(String channelType, Map<String, Object> requested) {
        String channel = channel(channelType);
        long userId = currentUserId();
        StoredConfig existing = stored(mapper.selectOne(userId, channel));
        NormalizedConfig normalized = normalize(channel, requested, existing, true);
        NotificationChannelSender.SendResult sent;
        try {
            sent = sender.sendTest(channel, normalized.deliveryConfig());
        } catch (RuntimeException exception) {
            audit(
                principalProvider.currentPrincipal(), "notification.channel.test", null,
                "failure", channel + ":" + exception.getMessage()
            );
            throw exception;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "测试消息已发送");
        result.put("channel_type", sent.channelType());
        result.put("elapsed_ms", sent.elapsedMs());
        audit(principalProvider.currentPrincipal(), "notification.channel.test", null, "success", channel);
        return result;
    }

    /**
 * 处理sendFor用户并返回对应结果。
 *
     * Delivers a runtime notification with the frozen human owner's personal configuration.
     * Runtime callers must pass the owner resolved from the run authorization snapshot; this
     * method deliberately does not accept webhook or SMTP credentials from the tool payload.
     */
    public NotificationChannelSender.SendResult sendForUser(
        Long userId,
        String channelType,
        String title,
        String content,
        String recipient
    ) {
        if (userId == null || userId <= 0) {
            throw new ServiceException("通知所有者无效", HttpStatus.BAD_REQUEST);
        }
        String channel = channel(channelType);
        UserNotificationChannelConfig row = mapper.selectOne(userId, channel);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            throw new ServiceException(
                "tool_unavailable: " + channel + " 通知渠道未配置或未启用", 503
            );
        }
        StoredConfig stored = stored(row);
        Map<String, Object> delivery = new LinkedHashMap<>(stored.publicConfig());
        delivery.putAll(stored.secrets());
        return sender.sendMessage(channel, Map.copyOf(delivery), title, content, recipient);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param channel {@code channel}参数
     * @param source 数据源参数
     * @param existing {@code existing}参数
     * @param requireReady {@code requireReady}参数
     * @return 处理结果
     */
    private NormalizedConfig normalize(
        String channel,
        Map<String, Object> source,
        StoredConfig existing,
        boolean requireReady
    ) {
        Map<String, Object> values = source == null ? Map.of() : source;
        Set<String> allowed = switch (channel) {
            case "dingtalk" -> DINGTALK_KEYS;
            case "wechat_work" -> WECHAT_KEYS;
            case "email" -> EMAIL_KEYS;
            default -> throw new IllegalStateException("unsupported channel");
        };
        if (values.size() > allowed.size() || !allowed.containsAll(values.keySet())) {
            throw new ServiceException("通知配置包含不支持字段", HttpStatus.BAD_REQUEST);
        }
        boolean enabled = booleanValue(
            values.get("is_enabled"), existing.enabled(), "is_enabled"
        );
        boolean ready = requireReady || enabled;
        return switch (channel) {
            case "dingtalk" -> dingTalk(values, existing, enabled, ready);
            case "wechat_work" -> wechatWork(values, existing, enabled, ready);
            case "email" -> email(values, existing, enabled, ready);
            default -> throw new IllegalStateException("unsupported channel");
        };
    }

    /**
     * 处理{@code dingTalk}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param existing {@code existing}参数
     * @param enabled {@code enabled}参数
     * @param ready {@code ready}参数
     * @return 处理结果
     */
    private NormalizedConfig dingTalk(
        Map<String, Object> values, StoredConfig existing, boolean enabled, boolean ready
    ) {
        String webhook = secret(values, "webhook_url", existing.secrets(), 2_048);
        String signingSecret = secret(values, "secret", existing.secrets(), 512);
        if (ready && webhook.isBlank()) {
            throw badRequest("钉钉 Webhook 地址不能为空");
        }
        Map<String, Object> secrets = compactSecrets(Map.of(
            "webhook_url", webhook,
            "secret", signingSecret
        ));
        Map<String, Object> delivery = new LinkedHashMap<>(secrets);
        return new NormalizedConfig(enabled, Map.of(), secrets, Map.copyOf(delivery));
    }

    /**
     * 处理{@code wechatWork}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param existing {@code existing}参数
     * @param enabled {@code enabled}参数
     * @param ready {@code ready}参数
     * @return 处理结果
     */
    private NormalizedConfig wechatWork(
        Map<String, Object> values, StoredConfig existing, boolean enabled, boolean ready
    ) {
        String webhook = secret(values, "webhook_url", existing.secrets(), 2_048);
        if (ready && webhook.isBlank()) {
            throw badRequest("企业微信 Webhook 地址不能为空");
        }
        Map<String, Object> secrets = compactSecrets(Map.of("webhook_url", webhook));
        return new NormalizedConfig(enabled, Map.of(), secrets, secrets);
    }

    /**
     * 处理{@code email}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param existing {@code existing}参数
     * @param enabled {@code enabled}参数
     * @param ready {@code ready}参数
     * @return 处理结果
     */
    private NormalizedConfig email(
        Map<String, Object> values, StoredConfig existing, boolean enabled, boolean ready
    ) {
        String host = field(values, "smtp_host", existing.publicConfig(), "", 253);
        int port = integerField(values, "smtp_port", existing.publicConfig(), 465);
        String user = field(values, "smtp_user", existing.publicConfig(), "", 320);
        String password = secret(values, "smtp_password", existing.secrets(), 8_192);
        String senderName = field(
            values, "sender_name", existing.publicConfig(), "AI Agent", 128
        );
        String recipients = field(values, "recipients", existing.publicConfig(), "", 2_000);
        if (ready) {
            if (host.isBlank() || user.isBlank() || password.isBlank()) {
                throw badRequest("SMTP 服务地址、账号和授权码不能为空");
            }
            validateEmail(user, "SMTP 账号");
            validateRecipients(recipients);
        }

        Map<String, Object> publicConfig = new LinkedHashMap<>();
        publicConfig.put("smtp_host", host);
        publicConfig.put("smtp_port", port);
        publicConfig.put("smtp_user", user);
        publicConfig.put("sender_name", senderName);
        publicConfig.put("recipients", recipients);
        Map<String, Object> secrets = compactSecrets(Map.of("smtp_password", password));
        Map<String, Object> delivery = new LinkedHashMap<>(publicConfig);
        delivery.putAll(secrets);
        return new NormalizedConfig(
            enabled, Map.copyOf(publicConfig), secrets, Map.copyOf(delivery)
        );
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param channel {@code channel}参数
     * @param stored {@code stored}参数
     * @return 处理结果
     */
    private Map<String, Object> view(String channel, StoredConfig stored) {
        Map<String, Object> result = defaults(channel);
        result.put("is_enabled", stored.enabled());
        result.putAll(stored.publicConfig());
        for (String secretField : secretFields(channel)) {
            Object value = stored.secrets().get(secretField);
            result.put(secretField, value == null || String.valueOf(value).isBlank() ? "" : MASK);
        }
        return result;
    }

    /**
     * 处理{@code defaults}并返回对应结果。
     *
     * @param channel {@code channel}参数
     * @return 处理结果
     */
    private Map<String, Object> defaults(String channel) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("is_enabled", false);
        switch (channel) {
            case "dingtalk" -> {
                result.put("webhook_url", "");
                result.put("secret", "");
            }
            case "wechat_work" -> result.put("webhook_url", "");
            case "email" -> {
                result.put("smtp_host", "");
                result.put("smtp_port", 465);
                result.put("smtp_user", "");
                result.put("smtp_password", "");
                result.put("sender_name", "AI Agent");
                result.put("recipients", "");
            }
            default -> throw new IllegalStateException("unsupported channel");
        }
        return result;
    }

    /**
     * 处理{@code stored}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private StoredConfig stored(UserNotificationChannelConfig row) {
        if (row == null) {
            return new StoredConfig(false, Map.of(), Map.of());
        }
        Map<String, Object> publicConfig;
        try {
            publicConfig = row.getConfigJson() == null || row.getConfigJson().isBlank()
                ? Map.of() : Map.copyOf(jsonMapper.readValue(row.getConfigJson(), MAP_TYPE));
        } catch (RuntimeException exception) {
            throw new ServiceException("通知配置数据格式无效", HttpStatus.ERROR);
        }
        return new StoredConfig(
            Boolean.TRUE.equals(row.getEnabled()),
            publicConfig,
            secretCodec.decrypt(row.getSecretPayload())
        );
    }

    /**
     * 处理{@code json}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String json(Map<String, Object> value) {
        String serialized = jsonMapper.writeValueAsString(value);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw badRequest("通知配置超过 32KB 限制");
        }
        return serialized;
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
            throw badRequest("不支持的通知渠道");
        }
        return normalized;
    }

    /**
     * 处理当前用户Id并返回对应结果。
     *
     * @return 处理结果
     */
    private long currentUserId() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("服务账号不能管理个人通知配置", HttpStatus.FORBIDDEN);
        }
        return principal.id();
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
        if (auditService != null && principal != null) {
            auditService.recordSafely(
                principal, action, "notification_channel", resourceId, decision, "owner_scoped", summary
            );
        }
    }

    /**
     * 处理{@code booleanValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param field {@code field}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean booleanValue(Object value, boolean fallback, String field) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw badRequest(field + "必须是布尔值");
    }

    /**
     * 处理{@code secret}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param key {@code key}参数
     * @param existing {@code existing}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String secret(
        Map<String, Object> requested,
        String key,
        Map<String, Object> existing,
        int maxLength
    ) {
        if (!requested.containsKey(key)) {
            return text(existing.get(key), maxLength, key);
        }
        String value = text(requested.get(key), maxLength, key);
        if (MASK.equals(value)) {
            return text(existing.get(key), maxLength, key);
        }
        return value;
    }

    /**
     * 处理{@code field}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param key {@code key}参数
     * @param existing {@code existing}参数
     * @param fallback {@code fallback}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String field(
        Map<String, Object> requested,
        String key,
        Map<String, Object> existing,
        String fallback,
        int maxLength
    ) {
        Object value = requested.containsKey(key)
            ? requested.get(key) : existing.getOrDefault(key, fallback);
        return text(value, maxLength, key);
    }

    /**
     * 处理{@code integerField}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param key {@code key}参数
     * @param existing {@code existing}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private int integerField(
        Map<String, Object> requested,
        String key,
        Map<String, Object> existing,
        int fallback
    ) {
        Object raw = requested.containsKey(key)
            ? requested.get(key) : existing.getOrDefault(key, fallback);
        long value;
        try {
            value = raw instanceof Number number
                ? number.longValue() : Long.parseLong(String.valueOf(raw));
        } catch (RuntimeException exception) {
            throw badRequest("SMTP 端口无效");
        }
        if (value < 1 || value > 65_535) {
            throw badRequest("SMTP 端口无效");
        }
        return Math.toIntExact(value);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String text(Object value, int maxLength, String field) {
        String normalized = value == null ? "" : String.valueOf(value).strip();
        if (normalized.length() > maxLength
            || normalized.chars().anyMatch(Character::isISOControl)) {
            throw badRequest(field + "无效或超过长度限制");
        }
        return normalized;
    }

    /**
     * 校验{@code Email}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param field {@code field}参数
     */
    private void validateEmail(String value, String field) {
        if (!EMAIL.matcher(value).matches()) {
            throw badRequest(field + "格式无效");
        }
    }

    /**
     * 校验{@code Recipients}，并在条件不满足时终止处理。
     *
     * @param recipients {@code recipients}参数
     */
    private void validateRecipients(String recipients) {
        if (recipients.isBlank()) {
            return;
        }
        String[] values = recipients.replace(';', ',').split(",");
        if (values.length > 50) {
            throw badRequest("收件人数量超过 50 个");
        }
        for (String value : values) {
            validateEmail(value.strip(), "收件人邮箱");
        }
    }

    /**
     * 处理{@code secretFields}并返回对应结果。
     *
     * @param channel {@code channel}参数
     * @return 符合条件的数据集合
     */
    private Set<String> secretFields(String channel) {
        return switch (channel) {
            case "dingtalk" -> Set.of("webhook_url", "secret");
            case "wechat_work" -> Set.of("webhook_url");
            case "email" -> Set.of("smtp_password");
            default -> Set.of();
        };
    }

    /**
     * 处理{@code compactSecrets}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private Map<String, Object> compactSecrets(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
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
     * 封装{@code Stored}相关的不可变数据。
     */
    private record StoredConfig(
        boolean enabled,
        Map<String, Object> publicConfig,
        Map<String, Object> secrets
    ) {
    }

    /**
     * 封装{@code Normalized}相关的不可变数据。
     */
    private record NormalizedConfig(
        boolean enabled,
        Map<String, Object> publicConfig,
        Map<String, Object> secrets,
        Map<String, Object> deliveryConfig
    ) {
    }
}
