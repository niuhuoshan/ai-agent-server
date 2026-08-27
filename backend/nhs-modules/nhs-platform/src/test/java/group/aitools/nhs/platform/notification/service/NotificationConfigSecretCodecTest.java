package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class NotificationConfigSecretCodecTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = Base64.getEncoder().encodeToString(
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    );

    @Test
    void encryptsAndDecryptsWithoutPersistingPlaintext() {
        NotificationConfigSecretCodec codec = new NotificationConfigSecretCodec(JSON, KEY);

        String payload = codec.encrypt(Map.of(
            "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=secret-token",
            "secret", "signing-secret"
        ));

        assertFalse(payload.contains("secret-token"));
        assertFalse(payload.contains("signing-secret"));
        assertEquals("signing-secret", codec.decrypt(payload).get("secret"));
    }

    @Test
    void missingInstallationKeyFailsExplicitly() {
        NotificationConfigSecretCodec codec = new NotificationConfigSecretCodec(JSON, "");

        ServiceException exception = assertThrows(
            ServiceException.class, () -> codec.encrypt(Map.of("secret", "value"))
        );

        assertEquals(503, exception.getCode());
    }

    @Test
    void changedInstallationKeyCannotSilentlyDecryptExistingCredentials() {
        NotificationConfigSecretCodec original = new NotificationConfigSecretCodec(JSON, KEY);
        String otherKey = Base64.getEncoder().encodeToString(
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8)
        );
        NotificationConfigSecretCodec changed = new NotificationConfigSecretCodec(JSON, otherKey);
        String payload = original.encrypt(Map.of("secret", "value"));

        ServiceException exception = assertThrows(ServiceException.class, () -> changed.decrypt(payload));

        assertEquals(503, exception.getCode());
        assertFalse(exception.getMessage().contains("value"));
    }
}
