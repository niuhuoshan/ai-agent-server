package group.aitools.nhs.platform.notification.web;

import group.aitools.nhs.platform.notification.service.UserNotificationConfigService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class NhsPortalNotificationConfigControllerTest {

    private final UserNotificationConfigService service = mock(UserNotificationConfigService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NhsPortalNotificationConfigController(service))
            .setControllerAdvice(new NhsPortalNotificationExceptionHandler())
            .build();
    }

    @Test
    void providerUnavailableRemainsAnHttp503InsteadOfFakeSuccess() throws Exception {
        when(service.test(eq("dingtalk"), any())).thenThrow(
            new ServiceException("钉钉通知供应商当前不可用", 503)
        );

        mockMvc.perform(post("/api/portal/notifications/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"channel_type":"dingtalk","config_data":{"webhook_url":"******"}}
                    """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(503))
            .andExpect(jsonPath("$.msg").value("钉钉通知供应商当前不可用"));
    }

    @Test
    void successfulTestReturnsMeasuredProviderMetadata() throws Exception {
        when(service.test(eq("wechat_work"), any())).thenReturn(Map.of(
            "status", "success", "message", "测试消息已发送",
            "channel_type", "wechat_work", "elapsed_ms", 12
        ));

        mockMvc.perform(post("/api/portal/notifications/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"channel_type":"wechat_work","config_data":{"webhook_url":"******"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("success"))
            .andExpect(jsonPath("$.data.elapsed_ms").value(12));
    }
}
