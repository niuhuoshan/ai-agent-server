package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.service.SystemDiagnosticsApplicationService;
import group.aitools.nhs.platform.operations.service.SystemHealthApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class SystemHealthControllerTest {

    private SystemDiagnosticsApplicationService diagnosticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SystemHealthApplicationService healthService = mock(SystemHealthApplicationService.class);
        diagnosticsService = mock(SystemDiagnosticsApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new SystemHealthController(healthService, diagnosticsService)
        ).build();
    }

    @Test
    void readinessUsesMachineDetectable503WhenRequiredCheckFails() throws Exception {
        when(diagnosticsService.diagnostics()).thenReturn(view("unavailable"));

        mockMvc.perform(get("/platform/operations/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.data.status").value("unavailable"));
    }

    @Test
    void deepDiagnosticsRemainReadableForAdministratorUi() throws Exception {
        when(diagnosticsService.diagnostics()).thenReturn(view("degraded"));

        mockMvc.perform(get("/platform/operations/diagnostics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("degraded"))
            .andExpect(jsonPath("$.data.checks[0].remediation").value("处理积压"));
    }

    private SystemDiagnosticsView view(String status) {
        return new SystemDiagnosticsView(status, Instant.parse("2026-08-17T05:30:00Z"), List.of(
            new SystemDiagnosticCheckView(
                "outbox", "业务事件 Outbox", status, true, "存在积压", java.util.Map.of("due", 2), "处理积压"
            )
        ));
    }
}
