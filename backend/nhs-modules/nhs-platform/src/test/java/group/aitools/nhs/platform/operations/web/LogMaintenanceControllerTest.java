package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.service.LogMaintenanceApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class LogMaintenanceControllerTest {

    private LogMaintenanceApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(LogMaintenanceApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LogMaintenanceController(service)).build();
    }

    @Test
    void preservesNhsLogConfigurationPathAndPayload() throws Exception {
        var request = new UpdateLogRetentionConfigRequest(120, null, null);
        when(service.updateConfiguration(request)).thenReturn(config(120));

        mockMvc.perform(post("/api/portal/system/logs/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"audit_log_retention_days\":120}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.retentionDays").value(120));

        verify(service).updateConfiguration(request);
    }

    @Test
    void exposesPostgresPartitionFacts() throws Exception {
        var statusView = new LogPartitionStatusView(
            "PostgreSQL", LocalDateTime.now(), 90, LocalDateTime.now().minusDays(90),
            2, 1000, 50000, List.of()
        );
        when(service.partitions()).thenReturn(statusView);

        mockMvc.perform(get("/platform/operations/logs/partitions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.databaseType").value("PostgreSQL"))
            .andExpect(jsonPath("$.data.maxRowsPerTablePerRun").value(50000));
    }

    @Test
    void bindsExplicitCleanupConfirmation() throws Exception {
        var request = new LogCleanupRequest("preview-token", true);
        var result = new LogCleanupResultView(
            "7", "succeeded", "manual", 90, LocalDateTime.now().minusDays(90),
            List.of(), List.of(), 0, 12, false, LocalDateTime.now(), LocalDateTime.now(),
            "日志清理完成", List.of()
        );
        when(service.cleanup(request)).thenReturn(result);

        mockMvc.perform(post("/platform/operations/logs/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmationToken\":\"preview-token\",\"confirm\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deletedRows").value(12));

        verify(service).cleanup(request);
    }

    @Test
    void exposesPersistentMaintenanceHistory() throws Exception {
        when(service.recentRuns(20)).thenReturn(List.of(new LogMaintenanceRunView(
            "9", "scheduled", "succeeded", 90, 2, LocalDateTime.now().minusDays(90), null,
            null, LocalDateTime.now(), LocalDateTime.now(), java.util.Map.of("deletedRows", 2),
            null, null, LocalDateTime.now()
        )));

        mockMvc.perform(get("/platform/operations/logs/maintenance-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].triggerType").value("scheduled"));
    }

    private LogRetentionConfigView config(int days) {
        return new LogRetentionConfigView(
            days, 1, 3650, 3, "1", LocalDateTime.now(), "test", "每天 02:00（Asia/Shanghai）"
        );
    }
}
