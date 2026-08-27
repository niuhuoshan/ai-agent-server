package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * 表示通知ConfigSecretCodec相关的领域对象。
 * Encrypts user notification secrets with an installation-owned AES-GCM key. */
@Component
public class NotificationConfigSecretCodec {

    private static final String PREFIX = "v1.";
    private static final int IV_BYTES = 12;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JsonMapper jsonMapper;
    private final String encodedKey;

    /**
     * 创建 {@code NotificationConfigSecretCodec} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param encodedKey {@code encodedKey}参数
     */
    public NotificationConfigSecretCodec(
        JsonMapper jsonMapper,
        @Value("${agent.platform.notification.config-key:${NHS_NOTIFICATION_CONFIG_KEY:}}")
        String encodedKey
    ) {
        this.jsonMapper = jsonMapper;
        this.encodedKey = encodedKey == null ? "" : encodedKey.strip();
    }

    /**
     * 处理{@code encrypt}并返回对应结果。
     *
     * @param secrets {@code secrets}参数
     * @return 处理结果
     */
    public String encrypt(Map<String, Object> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] plaintext = jsonMapper.writeValueAsString(secrets)
                .getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] envelope = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (ServiceException exception) {
            throw exception;
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw unavailable("通知渠道密钥加密失败");
        }
    }

    /**
     * 处理{@code decrypt}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    public Map<String, Object> decrypt(String payload) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        if (!payload.startsWith(PREFIX)) {
            throw unavailable("通知渠道密钥格式不受支持");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(payload.substring(PREFIX.length()));
            if (envelope.length <= IV_BYTES) {
                throw new GeneralSecurityException("encrypted payload is too short");
            }
            byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(encrypted);
            return Map.copyOf(jsonMapper.readValue(plaintext, MAP_TYPE));
        } catch (ServiceException exception) {
            throw exception;
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw unavailable("通知渠道密钥不可用或已变更");
        }
    }

    /**
     * 处理{@code key}并返回对应结果。
     *
     * @return 处理结果
     */
    private SecretKeySpec key() {
        if (encodedKey.isBlank()) {
            throw unavailable("未配置 NHS_NOTIFICATION_CONFIG_KEY，无法安全保存通知凭证");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) {
                throw unavailable("NHS_NOTIFICATION_CONFIG_KEY 必须是 32 字节 Base64 密钥");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw unavailable("NHS_NOTIFICATION_CONFIG_KEY 不是有效 Base64 密钥");
        }
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, 503);
    }
}
