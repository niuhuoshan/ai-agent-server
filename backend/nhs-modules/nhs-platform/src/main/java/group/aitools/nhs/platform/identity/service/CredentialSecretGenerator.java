package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.platform.common.ContentHashing;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 处理{@code generate}并返回对应结果。
 *
 * 表示凭据SecretGenerator相关的领域对象。
 * Generates high-entropy API secrets and immediately derives their non-reversible storage form. */
@Component
public class CredentialSecretGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public GeneratedCredential generate() {
        byte[] entropy = new byte[32];
        SECURE_RANDOM.nextBytes(entropy);
        String secretPart = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String keyPrefix = secretPart.substring(0, 12);
        String rawSecret = "agk_" + keyPrefix + "." + secretPart;
        return new GeneratedCredential(rawSecret, "agk_" + keyPrefix, ContentHashing.sha256(rawSecret));
    }

    /**
     * 封装Generated凭据相关的不可变数据。
     */
    public record GeneratedCredential(String rawSecret, String keyPrefix, String secretHash) {
    }
}
