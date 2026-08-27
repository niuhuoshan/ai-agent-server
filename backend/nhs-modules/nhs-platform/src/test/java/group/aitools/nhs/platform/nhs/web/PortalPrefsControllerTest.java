package group.aitools.nhs.platform.nhs.web;

import group.aitools.nhs.platform.nhs.service.PortalPrefsApplicationService;
import group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.Preferences;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class PortalPrefsControllerTest {

    private final PortalPrefsApplicationService service = mock(PortalPrefsApplicationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PortalPrefsController(service))
            .setControllerAdvice(new NhsV1ExceptionHandler())
            .build();
    }

    @Test
    void exposesNhsPreferenceEnvelopeAndSnakeCaseFields() throws Exception {
        when(service.get()).thenReturn(new Preferences(
            List.of("orders"), List.of("orders"), List.of(), Map.of(), List.of(), "compact"
        ));

        mockMvc.perform(get("/api/portal/portal-prefs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.pinned_group_ids[0]").value("orders"))
            .andExpect(jsonPath("$.data.markdown_theme").value("compact"));
    }

    @Test
    void returnsHttp503WhenRedisBackedServiceIsUnavailable() throws Exception {
        when(service.get()).thenThrow(new ServiceException("Redis 服务不可用", 503));

        mockMvc.perform(get("/api/portal/portal-prefs"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void updatesMarkdownThemeUsingTheDedicatedNhsRoute() throws Exception {
        when(service.updateMarkdownTheme("minimal")).thenReturn("minimal");

        mockMvc.perform(put("/api/portal/portal-prefs/markdown-theme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"theme\":\"minimal\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.markdown_theme").value("minimal"));
    }
}
