package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 表示沙箱SecretGenerator相关的领域对象。
 */
@Component
public class SandboxSecretGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 执行{@code nerSecret}相关的处理流程。
     *
     * @return 处理结果
     */
    public GeneratedSecret runnerSecret() {
        return generate("asr_");
    }

    /**
     * 处理作业令牌并返回对应结果。
     *
     * @return 处理结果
     */
    public GeneratedSecret jobToken() {
        return generate("asj_");
    }

    /**
     * 处理{@code generate}并返回对应结果。
     *
     * @param prefix {@code prefix}参数
     * @return 处理结果
     */
    private GeneratedSecret generate(String prefix) {
        byte[] entropy = new byte[32];
        SECURE_RANDOM.nextBytes(entropy);
        String secretPart = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String raw = prefix + secretPart.substring(0, 12) + "." + secretPart;
        return new GeneratedSecret(raw, ContentHashing.sha256(raw));
    }

    /**
     * 封装{@code GeneratedSecret}相关的不可变数据。
     */
    public record GeneratedSecret(String rawSecret, String secretHash) {
    }
}
