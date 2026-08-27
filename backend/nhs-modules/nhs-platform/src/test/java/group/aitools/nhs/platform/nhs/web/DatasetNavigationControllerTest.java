package group.aitools.nhs.platform.nhs.web;

import group.aitools.nhs.platform.nhs.service.DatasetNavigationService;
import group.aitools.nhs.platform.nhs.service.NhsV1OperationAuditService;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.NavigationResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.Question;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshRequest;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.TableRecommendRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class DatasetNavigationControllerTest {

    private final DatasetNavigationService service = mock(DatasetNavigationService.class);
    private final NhsV1OperationAuditService auditService = mock(NhsV1OperationAuditService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DatasetNavigationController(service, auditService))
            .setControllerAdvice(
                new NhsV1ExceptionHandler(),
                new NhsV1ResponseBodyAdvice(Clock.fixed(
                    Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC
                ))
            )
            .build();
    }

    @Test
    void exposesNavigationWithNhsStandardResponse() throws Exception {
        when(service.navigation(true)).thenReturn(new NavigationResponse(
            1, "a".repeat(64), "2026-08-16T10:00:00", List.of(), "portal",
            false, true, false, false, null
        ));

        mockMvc.perform(get("/api/v1/chat/dataset-menu").param("refresh", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dataset_count").value(1))
            .andExpect(jsonPath("$.data.dataset_menu_hash").value("a".repeat(64)));
        verify(service).navigation(true);
        verify(auditService).recordCurrent(
            eq("dataset_menu.view"), eq("dataset_menu"), eq(null), eq("success"),
            eq("authorized_catalog"), contains("datasets=1")
        );
    }

    @Test
    void bindsSnakeCaseRefreshContractAndRejectsInvalidPurpose() throws Exception {
        mockMvc.perform(post("/api/v1/chat/dataset-menu/refresh-group-questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"group_title":"经营分析","tables":["订单明细"],
                     "dataset_menu_hash":"abc","group_id":"orders","purpose":"invalid"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void validatesClickQueryBeforeRecording() throws Exception {
        mockMvc.perform(post("/api/v1/chat/dataset-menu/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void recordsAndClearsQuestionPreferenceWithSnakeCasePayload() throws Exception {
        when(service.clearClick("统计最近订单")).thenReturn(true);

        mockMvc.perform(post("/api/v1/chat/dataset-menu/click")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"统计最近订单","label":"订单概览","group_id":"orders",
                     "dataset_menu_hash":"abc"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(post("/api/v1/chat/dataset-menu/click/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"统计最近订单\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.success").value(true));

        verify(service).recordClick("统计最近订单", "订单概览", "orders");
        verify(service).clearClick("统计最近订单");
        verify(auditService).recordCurrent(
            eq("dataset_menu.click"), eq("dataset_menu"), eq(null), eq("success"),
            eq("preference_recorded"), contains("queryLength=6")
        );
        verify(auditService).recordCurrent(
            eq("dataset_menu.click_clear"), eq("dataset_menu"), eq(null), eq("success"),
            eq("preference_removed"), contains("removed=true")
        );
    }

    @Test
    void bindsRefreshAndTableRecommendationContracts() throws Exception {
        when(service.refresh(any(RefreshRequest.class))).thenReturn(new RefreshResponse(
            List.of(new Question("趋势", "分析订单趋势")), null
        ));
        when(service.recommend(any(TableRecommendRequest.class))).thenReturn(new RefreshResponse(
            List.of(new Question("明细", "查询订单明细")), null
        ));

        mockMvc.perform(post("/api/v1/chat/dataset-menu/refresh-group-questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"group_title":"经营分析","tables":["订单明细"],
                     "dataset_menu_hash":"abc","group_id":"orders",
                     "exclude_questions":[{"query":"查询旧问题"}],"purpose":"questions"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.questions[0].query").value("分析订单趋势"));

        mockMvc.perform(post("/api/v1/chat/dataset-menu/recommend-table-questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"table":"订单明细","physical_table_name":"biz_orders",
                     "dataset_name":"经营分析","columns":[{"name":"amount","term":"金额"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.questions[0].query").value("查询订单明细"));

        verify(service).refresh(any(RefreshRequest.class));
        verify(service).recommend(any(TableRecommendRequest.class));
        verify(auditService).recordCurrent(
            eq("dataset_menu.refresh"), eq("dataset_menu"), eq(null), eq("success"),
            eq("questions_refreshed"), contains("questions=1")
        );
        verify(auditService).recordCurrent(
            eq("dataset_menu.recommend"), eq("dataset_menu"), eq(null), eq("success"),
            eq("table_questions_generated"), contains("suppliedColumns=1")
        );
    }

    @Test
    void returnsServiceUnavailableInsteadOfSuccessWhenAuditCannotBePersisted() throws Exception {
        when(service.navigation(false)).thenReturn(new NavigationResponse(
            1, "a".repeat(64), "2026-08-16T10:00:00", List.of(), "portal",
            false, true, false, false, null
        ));
        ServiceException auditFailure = new ServiceException("操作审计写入失败，请稍后重试", 503);
        doThrow(auditFailure).when(auditService).recordCurrent(
            eq("dataset_menu.view"), eq("dataset_menu"), eq(null), eq("success"),
            eq("authorized_catalog"), anyString()
        );

        mockMvc.perform(get("/api/v1/chat/dataset-menu"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(503))
            .andExpect(jsonPath("$.message").value("操作审计写入失败，请稍后重试"));

        verify(service).navigation(false);
        verify(auditService, times(1)).recordCurrent(
            eq("dataset_menu.view"), eq("dataset_menu"), eq(null), eq("success"),
            eq("authorized_catalog"), anyString()
        );
    }

    @Test
    void preservesOperationFailureWhenFailureAuditAlsoCannotBePersisted() {
        RuntimeException operationFailure = new IllegalStateException("metadata unavailable");
        ServiceException auditFailure = new ServiceException("操作审计写入失败，请稍后重试", 503);
        when(service.navigation(false)).thenThrow(operationFailure);
        doThrow(auditFailure).when(auditService).recordCurrent(
            eq("dataset_menu.view"), eq("dataset_menu"), eq(null), eq("failure"),
            eq("runtime_error(IllegalStateException)"), anyString()
        );
        DatasetNavigationController controller = new DatasetNavigationController(service, auditService);

        ServiceException thrown = assertThrows(ServiceException.class, () -> controller.navigation(false));

        assertSame(auditFailure, thrown);
        assertThat(thrown.getSuppressed()).containsExactly(operationFailure);
    }
}
