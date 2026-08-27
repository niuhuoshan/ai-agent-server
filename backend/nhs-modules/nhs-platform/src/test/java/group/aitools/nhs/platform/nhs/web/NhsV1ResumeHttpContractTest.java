package group.aitools.nhs.platform.nhs.web;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.approval.service.ApprovalApplicationService;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionRequest;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionResult;
import group.aitools.nhs.platform.approval.web.ApprovalView;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService;
import group.aitools.nhs.platform.conversation.service.ConversationCancellationService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService;
import group.aitools.nhs.platform.conversation.service.ConversationFinalizationService;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataQueryExportService;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.platform.report.service.ReportApplicationService;
import group.aitools.nhs.platform.report.service.ReportExecutionPrincipalResolver;
import group.aitools.nhs.platform.sandbox.service.ExternalExecutionResumeService;
import group.aitools.nhs.platform.sandbox.web.ExternalExecutionResumeResult;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contracts for Nhs V1 approval and external-execution resume adapters. */
@Tag("dev")
class NhsV1ResumeHttpContractTest {

    private ApprovalApplicationService approvals;
    private ExternalExecutionResumeService externalExecutions;
    private ExecutionEventQueryService eventQueries;
    private ExecutionEventSseService eventStreams;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        approvals = mock(ApprovalApplicationService.class);
        externalExecutions = mock(ExternalExecutionResumeService.class);
        eventQueries = mock(ExecutionEventQueryService.class);
        eventStreams = mock(ExecutionEventSseService.class);
        NhsV1CompatibilityController controller = new NhsV1CompatibilityController(
            mock(CurrentPrincipalProvider.class), mock(PlatformIdGenerator.class),
            mock(ConversationApplicationService.class), mock(ConversationAttachmentService.class),
            mock(ConversationGovernanceService.class), mock(ConversationExportService.class),
            mock(ConversationTurnApplicationService.class), eventQueries, eventStreams,
            mock(AgentApplicationService.class), mock(TaskApplicationService.class),
            mock(TaskQueryService.class), mock(TaskRunApplicationService.class),
            mock(DataSourceCatalogService.class), mock(DataQueryExecutionService.class),
            mock(DataQueryExportService.class), mock(NhsWorkspaceService.class),
            mock(ReportApplicationService.class), mock(ReportExecutionPrincipalResolver.class),
            mock(PlatformUiPermissionService.class), mock(ConversationFinalizationService.class),
            approvals, externalExecutions, mock(ConversationCancellationService.class)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new NhsV1ExceptionHandler(),
                new NhsV1ResponseBodyAdvice(Clock.fixed(
                    Instant.parse("2026-08-17T01:00:00Z"), ZoneOffset.UTC
                ))
            )
            .build();
    }

    @Test
    void approvalConfirmBindsNhsPayloadAndResumesSseFromLastEventId() throws Exception {
        ApprovalDecisionResult approved = new ApprovalDecisionResult(approval("approved"), false, true);
        ExecutionEventQueryService.EventStreamReader reader = emptyReader();
        SseEmitter emitter = new SseEmitter();
        when(approvals.approve(eq(42L), any())).thenReturn(approved);
        when(eventQueries.taskRunReader(10L, 500L)).thenReturn(reader);
        when(eventStreams.stream(reader, 12L)).thenReturn(emitter);

        mockMvc.perform(post("/api/v1/chat/permissions/approval-42/confirm")
                .header("Last-Event-ID", "12")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"confirmed":true,"idempotency_key":"confirm-42","comment":"reviewed"}
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        ArgumentCaptor<ApprovalDecisionRequest> request =
            ArgumentCaptor.forClass(ApprovalDecisionRequest.class);
        verify(approvals).approve(eq(42L), request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo("confirm-42");
        assertThat(request.getValue().comment()).isEqualTo("reviewed");
        verify(eventQueries).taskRunReader(10L, 500L);
        verify(eventStreams).stream(reader, 12L);
    }

    @Test
    void approvalRejectUsesDurableRejectPathWithoutOpeningRunStream() throws Exception {
        when(approvals.reject(eq(42L), any())).thenReturn(
            new ApprovalDecisionResult(approval("rejected"), false, false)
        );

        MvcResult response = mockMvc.perform(post("/api/v1/chat/permissions/42/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"confirmed\":false}"))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();
        response.getAsyncResult(2_000);

        ArgumentCaptor<ApprovalDecisionRequest> request =
            ArgumentCaptor.forClass(ApprovalDecisionRequest.class);
        verify(approvals).reject(eq(42L), request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo("nhs-permission-42-false");
        String stream = response.getResponse().getContentAsString();
        assertThat(stream)
            .contains("event:permission_denied")
            .contains("\"permission_request_id\":42")
            .contains("\"confirmed\":false")
            .contains("data:[DONE]");
        assertThat(stream.indexOf("event:permission_denied"))
            .isLessThan(stream.indexOf("data:[DONE]"));
        verifyNoInteractions(eventQueries, eventStreams);
    }

    @Test
    void approvalConfirmRejectsMalformedBooleanAsStandardHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/chat/permissions/42/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"confirmed\":\"true\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("confirmed必须为布尔值"));

        verifyNoInteractions(approvals, eventQueries, eventStreams);
    }

    @Test
    void approvalConfirmValidatesResumeCursorBeforeMutatingApproval() throws Exception {
        mockMvc.perform(post("/api/v1/chat/permissions/42/confirm")
                .header("Last-Event-ID", "not-a-cursor")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"confirmed\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("Last-Event-ID 无效"));

        verifyNoInteractions(approvals, eventQueries, eventStreams);
    }

    @Test
    void approvalAuthorizationFailureKeepsHttpStatusAndNhsEnvelope() throws Exception {
        when(approvals.approve(eq(42L), any())).thenThrow(
            new ServiceException("没有审批权限", HttpStatus.FORBIDDEN)
        );

        mockMvc.perform(post("/api/v1/chat/permissions/42/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"confirmed\":true}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("没有审批权限"));

        verifyNoInteractions(eventQueries, eventStreams);
    }

    @Test
    void externalResumeBindsResultsAndPrefersPayloadCursorForSse() throws Exception {
        ExternalExecutionResumeResult resumed = new ExternalExecutionResumeResult(
            10L, 500L, 501L, false
        );
        ExecutionEventQueryService.EventStreamReader reader = emptyReader();
        SseEmitter emitter = new SseEmitter();
        when(externalExecutions.resume(eq("external-request-1"), any())).thenReturn(resumed);
        when(eventQueries.taskRunReader(10L, 500L)).thenReturn(reader);
        when(eventStreams.stream(reader, 9L)).thenReturn(emitter);

        mockMvc.perform(post("/api/v1/chat/external-executions/external-request-1/resume")
                .header("Last-Event-ID", "4")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"after_cursor":9,"results":[
                      {"id":"call-1","name":"lookup","output":"ok","state":"success"}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> results = ArgumentCaptor.forClass(List.class);
        verify(externalExecutions).resume(eq("external-request-1"), results.capture());
        assertThat(results.getValue()).containsExactly(Map.of(
            "id", "call-1", "name", "lookup", "output", "ok", "state", "success"
        ));
        verify(eventQueries).taskRunReader(10L, 500L);
        verify(eventStreams).stream(reader, 9L);
    }

    @Test
    void externalResumeRejectsNonObjectResultsBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/chat/external-executions/external-request-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"results\":[\"not-an-object\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("results必须是对象列表"));

        verifyNoInteractions(externalExecutions, eventQueries, eventStreams);
    }

    @Test
    void externalResumeValidatesCursorBeforeClaimingExecution() throws Exception {
        mockMvc.perform(post("/api/v1/chat/external-executions/external-request-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"cursor":-1,"results":[
                      {"id":"call-1","name":"lookup","output":"ok","state":"success"}
                    ]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("事件游标不能为负数"));

        verifyNoInteractions(externalExecutions, eventQueries, eventStreams);
    }

    @Test
    void externalResumeOwnershipFailureKeepsNotFoundHttpContract() throws Exception {
        when(externalExecutions.resume(eq("unknown"), any())).thenThrow(
            new ServiceException("外部执行请求不存在或无权访问", HttpStatus.NOT_FOUND)
        );

        mockMvc.perform(post("/api/v1/chat/external-executions/unknown/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"results":[
                      {"id":"call-1","name":"lookup","output":"ok","state":"success"}
                    ]}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("外部执行请求不存在或无权访问"));

        verifyNoInteractions(eventQueries);
        verify(eventStreams, never()).stream(any(), anyLong());
    }

    private ApprovalView approval(String status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 17, 9, 0);
        return new ApprovalView(
            42L, 10L, 500L, 501L, "high", "调用敏感工具", "参数已脱敏", "当前任务",
            status, 101L, 101L, "reviewed", now.plusMinutes(5), now, now.minusMinutes(1)
        );
    }

    private ExecutionEventQueryService.EventStreamReader emptyReader() {
        return (cursor, limit) -> List.of();
    }
}
