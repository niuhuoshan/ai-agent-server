package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.UserNotificationChannelConfig;
import group.aitools.nhs.platform.notification.mapper.UserNotificationChannelConfigMapper;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class UserNotificationConfigServiceTest {

    private static final CurrentPrincipal HUMAN = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = Base64.getEncoder().encodeToString(
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    );

    private CurrentPrincipalProvider principalProvider;
    private PlatformIdGenerator idGenerator;
    private UserNotificationChannelConfigMapper mapper;
    private NotificationChannelSender sender;
    private NotificationConfigSecretCodec codec;
    private UserNotificationConfigService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        idGenerator = mock(PlatformIdGenerator.class);
        mapper = mock(UserNotificationChannelConfigMapper.class);
        sender = mock(NotificationChannelSender.class);
        codec = new NotificationConfigSecretCodec(JSON, KEY);
        when(principalProvider.currentPrincipal()).thenReturn(HUMAN);
        when(mapper.selectByUser(101L)).thenReturn(List.of());
        service = new UserNotificationConfigService(
            principalProvider, idGenerator, mapper, codec, sender, JSON
        );
    }

    @Test
    void returnsCompleteDefaultsForCurrentOwner() {
        Map<String, Map<String, Object>> configs = service.configs();

        assertEquals(Set.of("dingtalk", "wechat_work", "email"), configs.keySet());
        assertEquals(false, configs.get("dingtalk").get("is_enabled"));
        assertEquals(465, configs.get("email").get("smtp_port"));
        verify(mapper).selectByUser(101L);
    }

    @Test
    void savesWebhookAndSigningSecretOnlyAsCiphertextForCurrentOwner() {
        when(idGenerator.nextId()).thenReturn(900L);
        when(mapper.upsert(any())).thenReturn(1);

        service.save("dingtalk", Map.of(
            "is_enabled", true,
            "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=private-token",
            "secret", "private-signing-secret"
        ));

        ArgumentCaptor<UserNotificationChannelConfig> captor =
            ArgumentCaptor.forClass(UserNotificationChannelConfig.class);
        verify(mapper).selectOne(101L, "dingtalk");
        verify(mapper).upsert(captor.capture());
        UserNotificationChannelConfig saved = captor.getValue();
        assertEquals(101L, saved.getUserId());
        assertEquals("{}", saved.getConfigJson());
        assertFalse(saved.getSecretPayload().contains("private-token"));
        assertEquals("private-signing-secret", codec.decrypt(saved.getSecretPayload()).get("secret"));
    }

    @Test
    void maskedValuesPreserveExistingEncryptedCredentials() {
        UserNotificationChannelConfig existing = row(
            codec.encrypt(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=original",
                "secret", "original-secret"
            ))
        );
        when(mapper.selectOne(101L, "dingtalk")).thenReturn(existing);
        when(mapper.upsert(any())).thenReturn(1);

        service.save("dingtalk", Map.of(
            "is_enabled", true, "webhook_url", "******", "secret", "******"
        ));

        ArgumentCaptor<UserNotificationChannelConfig> captor =
            ArgumentCaptor.forClass(UserNotificationChannelConfig.class);
        verify(mapper).upsert(captor.capture());
        Map<String, Object> secrets = codec.decrypt(captor.getValue().getSecretPayload());
        assertEquals("original-secret", secrets.get("secret"));
        assertEquals(
            "https://oapi.dingtalk.com/robot/send?access_token=original",
            secrets.get("webhook_url")
        );
    }

    @Test
    void testSendUsesResolvedStoredSecretsAndPropagatesProviderUnavailable() {
        UserNotificationChannelConfig existing = row(codec.encrypt(Map.of(
            "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=original"
        )));
        existing.setChannelType("wechat_work");
        when(mapper.selectOne(101L, "wechat_work")).thenReturn(existing);
        when(sender.sendTest(eq("wechat_work"), any())).thenThrow(
            new ServiceException("企业微信通知供应商当前不可用", 503)
        );

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("wechat_work", Map.of("webhook_url", "******"))
        );

        assertEquals(503, exception.getCode());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendTest(eq("wechat_work"), captor.capture());
        assertEquals(
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=original",
            captor.getValue().get("webhook_url")
        );
    }

    @Test
    void rejectsUnknownConfigurationFieldsBeforePersistence() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.save("email", Map.of("smtp_host", "smtp.example.com", "password", "bad"))
        );

        assertEquals(400, exception.getCode());
    }

    private UserNotificationChannelConfig row(String encryptedSecrets) {
        UserNotificationChannelConfig row = new UserNotificationChannelConfig();
        row.setId(900L);
        row.setUserId(101L);
        row.setChannelType("dingtalk");
        row.setEnabled(true);
        row.setConfigJson("{}");
        row.setSecretPayload(encryptedSecrets);
        row.setCreatedAt(LocalDateTime.now().minusDays(1));
        row.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return row;
    }
}
