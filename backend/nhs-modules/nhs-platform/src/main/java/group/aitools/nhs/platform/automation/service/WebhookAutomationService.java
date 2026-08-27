package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.web.AutomationFireView;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责回调通知自动化相关的业务编排与领域规则处理。
 */
@Service
public class WebhookAutomationService {

    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern SIGNATURE = Pattern.compile("v1=[0-9a-f]{64}");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AutomationMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final ApiCredentialAuthenticator authenticator;
    private final AutomationApplicationService applicationService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 创建 {@code WebhookAutomationService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param authenticator {@code authenticator}参数
     * @param applicationService 应用Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    @Autowired
    public WebhookAutomationService(
        AutomationMapper mapper,
        PlatformIdGenerator idGenerator,
        ApiCredentialAuthenticator authenticator,
        AutomationApplicationService applicationService,
        JsonMapper jsonMapper
    ) {
        this(mapper, idGenerator, authenticator, applicationService, jsonMapper, Clock.systemUTC());
    }

    /**
     * 创建 {@code WebhookAutomationService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param authenticator {@code authenticator}参数
     * @param applicationService 应用Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param clock {@code clock}参数
     */
    WebhookAutomationService(
        AutomationMapper mapper,
        PlatformIdGenerator idGenerator,
        ApiCredentialAuthenticator authenticator,
        AutomationApplicationService applicationService,
        JsonMapper jsonMapper,
        Clock clock
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.authenticator = authenticator;
        this.applicationService = applicationService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param triggerKey {@code triggerKey}参数
     * @param authorization 授权参数
     * @param timestampHeader {@code timestampHeader}参数
     * @param nonce {@code nonce}参数
     * @param signature {@code signature}参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param rawBody {@code rawBody}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationFireView invoke(
        String triggerKey,
        String authorization,
        String timestampHeader,
        String nonce,
        String signature,
        String idempotencyKey,
        String rawBody
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Instant requestTime = requestTime(timestampHeader);
        Instant now = clock.instant();
        if (Math.abs(requestTime.getEpochSecond() - now.getEpochSecond()) > MAX_CLOCK_SKEW_SECONDS) {
            throw unauthorized("Webhook时间戳已过期或超前");
        }
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            throw unauthorized("Webhook随机数无效");
        }
        String normalizedTriggerKey = required(triggerKey, "Webhook触发器标识", 128);
        String normalizedIdempotency = required(idempotencyKey, "Webhook幂等键", 128);
        String body = rawBody == null || rawBody.isBlank() ? "{}" : rawBody;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ServiceException("Webhook请求体超过64KB", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> payload;
        try {
            payload = jsonMapper.readValue(body, MAP_TYPE);
        } catch (RuntimeException exception) {
            throw new ServiceException("Webhook请求体不是有效JSON对象", HttpStatus.BAD_REQUEST);
        }
        if (!payload.keySet().stream().allMatch(Set.of("input")::contains)
            || (payload.containsKey("input") && !(payload.get("input") instanceof String))) {
            throw new ServiceException("Webhook请求只允许input字段", HttpStatus.BAD_REQUEST);
        }

        String rawSecret = bearerSecret(authorization);
        AuthenticatedServiceAccount authenticated = authenticator.authenticate(rawSecret);
        if (!"webhook".equals(authenticated.applicationType())
            || !authenticated.scopes().contains("webhooks:invoke")) {
            throw new ServiceException("Webhook凭证scope或应用类型不匹配", HttpStatus.FORBIDDEN);
        }
        AutomationTrigger trigger = mapper.selectTriggerByKey(normalizedTriggerKey);
        if (trigger == null || !"webhook".equals(trigger.getTriggerType())
            || !authenticated.principal().id().equals(trigger.getServiceAccountId())) {
            throw new ServiceException("Webhook凭证与触发器不匹配", HttpStatus.FORBIDDEN);
        }
        String canonical = timestampHeader.strip() + "\n" + nonce + "\n"
            + normalizedTriggerKey + "\n" + ContentHashing.sha256(body);
        if (!validSignature(rawSecret, canonical, signature)) {
            throw unauthorized("Webhook签名无效");
        }
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (mapper.insertWebhookNonce(
            idGenerator.nextId(), authenticated.credentialId(), ContentHashing.sha256(nonce),
            LocalDateTime.ofInstant(requestTime, ZoneOffset.UTC), nowUtc.plusMinutes(10), nowUtc
        ) != 1) {
            throw new ServiceException("Webhook随机数已使用", HttpStatus.CONFLICT);
        }
        String input = payload.containsKey("input") ? (String) payload.get("input") : null;
        return applicationService.webhookFire(trigger, normalizedIdempotency, input);
    }

    /**
     * 处理{@code validSignature}并返回对应结果。
     *
     * @param secret {@code secret}参数
     * @param canonical {@code canonical}参数
     * @param supplied {@code supplied}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean validSignature(String secret, String canonical, String supplied) {
        if (supplied == null || !SIGNATURE.matcher(supplied).matches()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(supplied.substring(3));
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 处理{@code requestTime}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Instant requestTime(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(required(value, "Webhook时间戳", 20)));
        } catch (NumberFormatException exception) {
            throw unauthorized("Webhook时间戳无效");
        }
    }

    /**
     * 处理{@code bearerSecret}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String bearerSecret(String value) {
        if (value == null || !value.startsWith("Bearer ")) {
            throw unauthorized("Webhook凭证无效");
        }
        String secret = value.substring(7).strip();
        if (secret.isBlank() || secret.indexOf(' ') >= 0) {
            throw unauthorized("Webhook凭证无效");
        }
        return secret;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String required(String value, String label, int limit) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.length() > limit || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code unauthorized}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unauthorized(String message) {
        return new ServiceException(message, HttpStatus.UNAUTHORIZED);
    }
}
