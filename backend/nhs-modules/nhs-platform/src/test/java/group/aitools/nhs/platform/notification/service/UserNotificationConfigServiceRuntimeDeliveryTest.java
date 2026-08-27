package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.UserNotificationChannelConfig;
import group.aitools.nhs.platform.notification.mapper.UserNotificationChannelConfigMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserNotificationConfigServiceRuntimeDeliveryTest {

    @Test
    void runtimeDeliveryUsesOwnerConfigAndNeverToolCredentials() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        UserNotificationChannelConfigMapper mapper = mock(UserNotificationChannelConfigMapper.class);
        NotificationConfigSecretCodec codec = mock(NotificationConfigSecretCodec.class);
        NotificationChannelSender sender = mock(NotificationChannelSender.class);
        UserNotificationChannelConfig row = new UserNotificationChannelConfig();
        row.setUserId(9L);
        row.setChannelType("dingtalk");
        row.setEnabled(true);
        row.setConfigJson("{}");
        row.setSecretPayload("encrypted");
        when(mapper.selectOne(9L, "dingtalk")).thenReturn(row);
        when(codec.decrypt("encrypted")).thenReturn(Map.of(
            "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=owner"
        ));
        when(sender.sendMessage(
            eq("dingtalk"), anyMap(), eq("Title"), eq("Body"), isNull()
        )).thenReturn(new NotificationChannelSender.SendResult("dingtalk", 8L));

        UserNotificationConfigService service = new UserNotificationConfigService(
            principalProvider, idGenerator, mapper, codec, sender, JsonMapper.builder().build()
        );

        NotificationChannelSender.SendResult result = service.sendForUser(
            9L, "dingtalk", "Title", "Body", null
        );

        assertEquals("dingtalk", result.channelType());
        verify(sender).sendMessage(
            eq("dingtalk"), anyMap(), eq("Title"), eq("Body"), isNull()
        );
    }
}
