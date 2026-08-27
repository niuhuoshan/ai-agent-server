package group.aitools.nhs.platform.nhs.web;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService;
import group.aitools.nhs.platform.conversation.service.ConversationCancellationService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService.ConversationExport;
import group.aitools.nhs.platform.conversation.service.ConversationFinalizationService;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.conversation.web.ConversationCancellationResult;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackRequest;
import group.aitools.nhs.platform.conversation.web.ConversationFinalizeResult;
import group.aitools.nhs.platform.conversation.web.ConversationResourceScopeRequest;
import group.aitools.nhs.platform.conversation.web.ConversationView;
import group.aitools.nhs.platform.conversation.web.CreateConversationRequest;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataQueryExportService;
import group.aitools.nhs.platform.data.service.DataQueryExportService.ExportedFile;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService.ConversationTrace;
import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.platform.report.service.ReportApplicationService;
import group.aitools.nhs.platform.report.service.ReportExecutionPrincipalResolver;
import group.aitools.nhs.platform.report.web.ReportSubscriptionView;
import group.aitools.nhs.platform.report.web.ReportView;
import group.aitools.nhs.platform.report.web.UpdateReportSubscriptionStatusRequest;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.data.web.DataSourceView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsV1CompatibilityControllerTest {

    private ConversationAttachmentService attachments;
    private ConversationGovernanceService governance;
    private ConversationExportService exports;
    private NhsWorkspaceService workspace;
    private ReportApplicationService reports;
    private CurrentPrincipalProvider principals;
    private TaskQueryService taskQueries;
    private TaskRunApplicationService taskRuns;
    private ExecutionEventQueryService eventQueries;
    private ExecutionEventSseService eventStreams;
    private ConversationApplicationService conversations;
    private ConversationTurnApplicationService turns;
    private DataSourceCatalogService catalogs;
    private DataQueryExecutionService dataQueries;
    private DataQueryExportService dataExports;
    private ReportExecutionPrincipalResolver reportPrincipals;
    private NhsV1CompatibilityController controller;

    @BeforeEach
    void setUp() {
        attachments = mock(ConversationAttachmentService.class);
        governance = mock(ConversationGovernanceService.class);
        exports = mock(ConversationExportService.class);
        workspace = mock(NhsWorkspaceService.class);
        reports = mock(ReportApplicationService.class);
        principals = mock(CurrentPrincipalProvider.class);
        taskQueries = mock(TaskQueryService.class);
        taskRuns = mock(TaskRunApplicationService.class);
        eventQueries = mock(ExecutionEventQueryService.class);
        eventStreams = mock(ExecutionEventSseService.class);
        conversations = mock(ConversationApplicationService.class);
        turns = mock(ConversationTurnApplicationService.class);
        catalogs = mock(DataSourceCatalogService.class);
        dataQueries = mock(DataQueryExecutionService.class);
        dataExports = mock(DataQueryExportService.class);
        reportPrincipals = mock(ReportExecutionPrincipalResolver.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        controller = new NhsV1CompatibilityController(
            principals, mock(PlatformIdGenerator.class),
            conversations, attachments, governance, exports,
            turns, eventQueries,
            eventStreams, mock(AgentApplicationService.class),
            mock(TaskApplicationService.class), taskQueries,
            taskRuns, catalogs,
            dataQueries, dataExports, workspace, reports, reportPrincipals
        );
    }

    @Test
    void agentChatOverridesBodyAgentForExistingConversation() {
        var reader = eventReader();
        SseEmitter emitter = new SseEmitter();
        when(eventQueries.conversationReader(7L)).thenReturn(reader);
        when(eventStreams.stream(reader, 0)).thenReturn(emitter);

        SseEmitter response = controller.agentChat(41L, Map.of(
            "conversation_id", 7L, "agent_id", 99L, "input", "hello",
            "idempotency_key", "request-1"
        ), null);

        ArgumentCaptor<CreateConversationTurnRequest> turn = ArgumentCaptor.forClass(
            CreateConversationTurnRequest.class
        );
        verify(turns).start(eq(7L), turn.capture());
        assertThat(turn.getValue().agentId()).isEqualTo(41L);
        assertThat(response).isSameAs(emitter);
    }

    @Test
    void agentChatOverridesBodyAgentWhenCreatingConversation() {
        LocalDateTime now = LocalDateTime.now();
        var reader = eventReader();
        when(conversations.create(any())).thenReturn(new ConversationView(
            7L, null, null, 41L, null, "New chat", "private", "active", now, now
        ));
        when(eventQueries.conversationReader(7L)).thenReturn(reader);
        when(eventStreams.stream(reader, 0)).thenReturn(new SseEmitter());

        controller.agentChat(41L, Map.of(
            "agent_id", 99L, "input", "hello", "idempotency_key", "request-2"
        ), null);

        ArgumentCaptor<CreateConversationRequest> conversation = ArgumentCaptor.forClass(
            CreateConversationRequest.class
        );
        ArgumentCaptor<CreateConversationTurnRequest> turn = ArgumentCaptor.forClass(
            CreateConversationTurnRequest.class
        );
        verify(conversations).create(conversation.capture());
        verify(turns).start(eq(7L), turn.capture());
        assertThat(conversation.getValue().agentId()).isEqualTo(41L);
        assertThat(turn.getValue().agentId()).isEqualTo(41L);
    }

    @Test
    void agentChatResumesFromLastEventIdWithoutStartingAnotherTurn() {
        var reader = eventReader();
        when(eventQueries.conversationReader(7L)).thenReturn(reader);
        when(eventStreams.stream(reader, 12L)).thenReturn(new SseEmitter());

        controller.agentChat(41L, Map.of("conversation_id", 7L, "agent_id", 99L), "12");

        verify(turns, never()).start(any(), any());
        verify(eventStreams).stream(reader, 12L);
    }

    @Test
    void agentChatPropagatesAuthorizationFailureBeforeOpeningEventStream() {
        ServiceException denied = new ServiceException("Agent 不存在或无权使用", HttpStatus.FORBIDDEN);
        when(turns.start(eq(7L), any())).thenThrow(denied);

        assertThatThrownBy(() -> controller.agentChat(41L, Map.of(
            "conversation_id", 7L, "input", "hello", "idempotency_key", "request-3"
        ), null)).isSameAs(denied);

        verifyNoInteractions(eventStreams);
    }

    @Test
    void mapsHistoryDeletionAndBatchDeletionToOwnerBoundGovernanceService() {
        var single = controller.deleteHistory(7L);
        var batch = controller.deleteHistoryBatchAlias(Map.of("conversation_ids", List.of(7, "8", 7)));

        assertThat(single.getData()).containsEntry("conversation_id", 7L).containsEntry("deleted", true);
        assertThat(batch.getData()).containsEntry("deleted_count", 2);
        verify(governance, org.mockito.Mockito.times(2)).deleteConversation(7L);
        verify(governance).deleteConversation(8L);
    }

    @Test
    void deletesHistoryByTraceOnlyAfterOwnerScopedTraceLookup() {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setConversationId(17L);
        when(eventQueries.traceConversation("trace-1")).thenReturn(
            new ConversationTrace("trace-1", "member", turn, List.of(), List.of())
        );

        var result = controller.deleteHistoryByTrace("trace-1");

        assertThat(result.getData())
            .containsEntry("trace_id", "trace-1")
            .containsEntry("conversation_id", 17L)
            .containsEntry("deleted", true);
        verify(eventQueries).traceConversation("trace-1");
        verify(governance).deleteConversation(17L);
    }

    @Test
    void mapsActiveConversationReadAndWriteToDurableGovernanceState() {
        when(governance.activeConversation()).thenReturn(7L);

        var active = controller.activeConversation();
        var updated = controller.setActiveConversation(Map.of("conversation_id", "8"));

        assertThat(active.getData()).containsEntry("conversation_id", 7L);
        assertThat(updated.getData()).containsEntry("status", "success");
        verify(governance).setActiveConversation(8L);
    }

    @Test
    void mapsFeedbackAndResourceScopeWireFieldsToTypedRequests() {
        controller.feedback(Map.of(
            "conversation_id", 7, "message_id", "11", "turn_id", 12,
            "rating", "like", "reason", "accurate", "comment", "helpful", "trace_id", "trace-1"
        ));
        controller.updateResourceScope(7L, Map.of(
            "expected_revision", 3,
            "resources", Map.of("toolIds", List.of("21", 22), "datasetIds", List.of(31))
        ));

        ArgumentCaptor<ConversationFeedbackRequest> feedback = ArgumentCaptor.forClass(
            ConversationFeedbackRequest.class
        );
        verify(governance).saveFeedback(org.mockito.ArgumentMatchers.eq(7L), feedback.capture());
        assertThat(feedback.getValue()).isEqualTo(new ConversationFeedbackRequest(
            11L, 12L, "like", "accurate", "helpful", "trace-1"
        ));

        ArgumentCaptor<ConversationResourceScopeRequest> scope = ArgumentCaptor.forClass(
            ConversationResourceScopeRequest.class
        );
        verify(governance).updateResourceScope(org.mockito.ArgumentMatchers.eq(7L), scope.capture());
        assertThat(scope.getValue().expectedRevision()).isEqualTo(3);
        assertThat(scope.getValue().resources()).containsEntry("toolIds", List.of(21L, 22L))
            .containsEntry("datasetIds", List.of(31L));
    }

    @Test
    void delegatesConversationAndWorkspaceUploadsAndTrashLifecycle() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "evidence.txt", "text/plain", "proof".getBytes(StandardCharsets.UTF_8)
        );
        when(workspace.delete("docs/evidence.txt")).thenReturn(Map.of("trash_id", "trash-1"));
        when(workspace.restore("trash-1", null)).thenReturn(Map.of("path", "docs/evidence.txt"));

        controller.upload(7L, file);
        controller.uploadFile("docs", file);
        controller.deleteEntry(Map.of("path", "docs/evidence.txt"));
        controller.restoreEntry(Map.of("trash_id", "trash-1"));

        verify(attachments).upload(7L, file);
        verify(workspace).upload("docs", file);
        verify(workspace).delete("docs/evidence.txt");
        verify(workspace).restore("trash-1", null);
    }

    @Test
    void acceptsOriginalRenameAndRecentFilesWireFields() {
        when(workspace.rename("docs/old.txt", "new.txt")).thenReturn(Map.of("path", "docs/new.txt"));
        when(workspace.updateRecent(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
            Map.of("path", "docs/new.txt", "name", "new.txt")
        ));

        controller.renameEntry(Map.of("path", "docs/old.txt", "new_name", "new.txt"));
        var recent = controller.updateRecentFiles(Map.of(
            "items", List.of(Map.of("path", "docs/new.txt", "name", "new.txt", "mtime", 1))
        ));

        verify(workspace).rename("docs/old.txt", "new.txt");
        verify(workspace).updateRecent(org.mockito.ArgumentMatchers.any());
        assertThat(recent.getData()).containsKey("items");
    }

    @Test
    void returnsV1ConversationExportAsAttachment() {
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        when(exports.export(7L, "json")).thenReturn(new ConversationExport(
            "conversation-7.json", "application/json;charset=UTF-8", content
        ));

        var response = controller.export(Map.of("conversation_id", "7", "format", "json"));

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("attachment", "conversation-7.json");
        assertThat(response.getBody()).isEqualTo(content);
        verify(exports).export(7L, "json");
    }

    @Test
    void mapsOwnedDurableTraceToNhsHistoryAndSteps() {
        LocalDateTime now = LocalDateTime.now();
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(31L);
        turn.setConversationId(7L);
        turn.setUserId(101L);
        turn.setTraceId("trace-1");
        turn.setAgentId(41L);
        turn.setAgentVersionId(42L);
        turn.setStatus("succeeded");
        turn.setStartedAt(now.minusSeconds(2));
        turn.setFinishedAt(now);
        ConversationMessageRow user = message(51L, "user", "分析销售", now.minusSeconds(2));
        ConversationMessageRow assistant = message(52L, "assistant", "销售增长", now);
        assistant.setModelId(61L);
        assistant.setCompletionTokens(8);
        assistant.setTotalTokens(8);
        ExecutionEventView event = new ExecutionEventView(
            "event-1", "trace-1", 7L, null, null, 1,
            "tool_call", "success", "Tool call: execute_sql_query",
            Map.of("redacted", true), "internal", now,
            Map.ofEntries(
                Map.entry("agentName", "research-agent"),
                Map.entry("model", "test-model"),
                Map.entry("temperature", 0.1),
                Map.entry("toolName", "execute_sql_query"),
                Map.entry("toolInput", Map.of("dataset", "sales")),
                Map.entry("toolOutput", Map.of("rows", 3)),
                Map.entry("executionTimeMs", 125D),
                Map.entry("promptTokens", 11),
                Map.entry("completionTokens", 7),
                Map.entry("totalTokens", 18),
                Map.entry("spanId", "tool-1"),
                Map.entry("parentSpanId", "reply-1")
            )
        );
        when(eventQueries.traceConversation("trace-1")).thenReturn(new ConversationTrace(
            "trace-1", "member", turn, List.of(user, assistant), List.of(event)
        ));

        var response = controller.traceLogs("trace-1");

        assertThat(response.getData()).containsEntry("trace_id", "trace-1")
            .containsEntry("total_steps", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> history = (Map<String, Object>) response.getData().get("history");
        assertThat(history).containsEntry("query", "分析销售")
            .containsEntry("summary", "销售增长")
            .containsEntry("status", "success")
            .containsEntry("completion_tokens", 8);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) response.getData().get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst())
            .containsEntry("event_type", "tool_call")
            .containsEntry("agent_name", "research-agent")
            .containsEntry("model", "test-model")
            .containsEntry("temperature", 0.1)
            .containsEntry("tool_name", "execute_sql_query")
            .containsEntry("tool_input", Map.of("dataset", "sales"))
            .containsEntry("tool_output", Map.of("rows", 3))
            .containsEntry("execution_time_ms", 125D)
            .containsEntry("prompt_tokens", 11)
            .containsEntry("completion_tokens", 7)
            .containsEntry("total_tokens", 18)
            .containsEntry("span_id", "tool-1")
            .containsEntry("parent_span_id", "reply-1")
            .containsEntry("status", "success");
    }

    @Test
    void returnsTraceDataExportWithExactMediaTypeAndFileName() {
        byte[] content = {'P', 'K', 3, 4};
        when(dataExports.exportTrace("trace-1", "xlsx")).thenReturn(new ExportedFile(
            content, "export_trace-1.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 2
        ));

        var response = controller.exportTraceData("trace-1", "xlsx");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("attachment", "export_trace-1.xlsx");
        assertThat(response.getHeaders().getContentType()).isEqualTo(
            org.springframework.http.MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        );
        assertThat(response.getBody()).isEqualTo(content);
    }

    @Test
    void modelCallsProjectsV1StatsAndFiltersByTraceId() {
        LocalDateTime started = LocalDateTime.of(2026, 8, 16, 10, 0);
        when(eventQueries.listConversation(7L, 0, 200)).thenReturn(List.of(
            new ExecutionEventView(
                "event-start", "trace-1", 7L, null, null, 1,
                "model_call_started", "success", "Model call",
                Map.of("redacted", true), "internal", started,
                Map.of("replyId", "reply-1", "agentName", "analyst", "model", "gpt-test")
            ),
            new ExecutionEventView(
                "tool-start", "trace-1", 7L, null, null, 2,
                "tool_call_started", "success", "Tool call",
                Map.of("redacted", true), "internal", started.plusNanos(10_000_000),
                Map.of("replyId", "reply-1", "toolName", "execute_sql_query")
            ),
            new ExecutionEventView(
                "event-finish", "trace-1", 7L, null, null, 3,
                "model_call_finished", "success", "Model call",
                Map.of("redacted", true), "internal", started.plusNanos(125_000_000),
                Map.of(
                    "replyId", "reply-1", "promptTokens", 11, "completionTokens", 7,
                    "cachedTokens", 3, "totalTokens", 18, "durationMs", 125D
                )
            ),
            new ExecutionEventView(
                "other-trace", "trace-2", 7L, null, null, 4,
                "model_call_finished", "success", "Model call",
                Map.of("redacted", true), "internal", started,
                Map.of("replyId", "reply-2", "model", "other-model", "durationMs", 1D)
            )
        ));

        var response = controller.modelCalls(7L, 0, "trace-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stats = (List<Map<String, Object>>) response.getData().get("stats");
        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst())
            .containsEntry("call_index", 1)
            .containsEntry("trace_id", "trace-1")
            .containsEntry("agent_name", "analyst")
            .containsEntry("model_name", "gpt-test")
            .containsEntry("input_tokens", 11)
            .containsEntry("output_tokens", 7)
            .containsEntry("cache_input_tokens", 3)
            .containsEntry("total_tokens", 18)
            .containsEntry("elapsed_ms", 125D)
            .containsEntry("has_tools_bound", true)
            .containsEntry("has_tool_calls", true)
            .containsEntry("tool_names", List.of("execute_sql_query"));
        verify(eventQueries).listConversation(7L, 0, 200);
    }

    @Test
    void profileProjectsEffectiveUiPermissions() {
        PlatformUiPermissionService uiPermissions = mock(PlatformUiPermissionService.class);
        when(uiPermissions.buttons(any())).thenReturn(List.of("conversation:create", "report:list"));
        controller = new NhsV1CompatibilityController(
            principals, mock(PlatformIdGenerator.class),
            conversations, attachments, governance, exports,
            turns, eventQueries, eventStreams, mock(AgentApplicationService.class),
            mock(TaskApplicationService.class), taskQueries, taskRuns, catalogs,
            dataQueries, dataExports, workspace, reports, reportPrincipals, uiPermissions
        );

        var response = controller.profile(null);

        assertThat(response.getData()).containsEntry(
            "permissions", List.of("conversation:create", "report:list")
        );
        verify(uiPermissions).buttons(any());
    }

    @Test
    void globalCancelProjectsObservedRuntimeLaneOutcomes() {
        ConversationCancellationService cancellation = mock(ConversationCancellationService.class);
        when(cancellation.cancel(7L, "trace-7", "stop now")).thenReturn(
            new ConversationCancellationResult(
                7L, "trace-7", true, true, 2, true, 3, 1,
                "cancelled", "cancel_requested", 17L
            )
        );
        controller = controllerWithLifecycle(null, cancellation);

        var response = controller.cancelChat(Map.of(
            "conversation_id", 7L,
            "trace_id", "trace-7",
            "reason", "stop now"
        ));

        assertThat(response.getData())
            .containsEntry("conversation_id", 7L)
            .containsEntry("trace_id", "trace-7")
            .containsEntry("success", true)
            .containsEntry("lane_released", true)
            .containsEntry("session_locks_released", 2)
            .containsEntry("run_cancelled", true)
            .containsEntry("canvas_stopped", 3)
            .containsEntry("task_runs_cancelled", 1)
            .containsEntry("status", "cancelled")
            .containsEntry("reason", "cancel_requested")
            .containsEntry("turn_id", 17L);
        verify(cancellation).cancel(7L, "trace-7", "stop now");
        verifyNoInteractions(turns);
    }

    @Test
    void finalizeDelegatesToDurableSummaryAndMemoryProjection() {
        ConversationFinalizationService finalization = mock(ConversationFinalizationService.class);
        when(finalization.finalizeConversation(7L)).thenReturn(
            new ConversationFinalizeResult(true, 7L, "summary_refreshed")
        );
        controller = controllerWithLifecycle(finalization, null);

        var response = controller.finalizeConversation(7L);

        assertThat(response.getData()).isEqualTo(
            new ConversationFinalizeResult(true, 7L, "summary_refreshed")
        );
        verify(finalization).finalizeConversation(7L);
    }

    @Test
    void delegatesSavedReportSubscriptionLifecycleToRealReportService() {
        LocalDateTime now = LocalDateTime.now();
        ReportSubscriptionView subscription = new ReportSubscriptionView(
            41L, 9L, 17L, "Asia/Shanghai", "{}", "{}", "active", null, null, now
        );
        when(reports.visibleSubscriptions(500)).thenReturn(List.of(subscription));
        when(reports.get(9L)).thenReturn(new ReportView(
            9L, "sales", "销售日报", 3L, "SELECT 1", "{}", "private", 101L, "active", now, now
        ));
        when(reports.updateSubscriptionStatus(
            41L, new UpdateReportSubscriptionStatusRequest("active")
        )).thenReturn(subscription);
        when(reports.executeSubscription(41L)).thenReturn(new DataQueryResultView(
            71L, List.of("value"), List.of(List.of(1)), 1, 1, false, 12
        ));

        var listed = controller.reportSubscriptions();
        var status = controller.updateReportSubscriptionStatus(41L, Map.of("active", true));
        var run = controller.runReportSubscription(41L);
        var deleted = controller.deleteReportSubscription(41L);

        assertThat(listed.getData().getFirst()).containsEntry("subscription_id", 41L)
            .containsEntry("name", "销售日报");
        assertThat(status.getData()).containsEntry("status", "active");
        assertThat(run.getData()).containsEntry("query_id", 71L).containsEntry("row_count", 1L);
        assertThat(deleted.getData()).containsEntry("success", true);
        verify(reports).deleteSubscription(41L);
    }

    @Test
    void executionHistoryOnlyProjectsRunsOwnedByCurrentUser() {
        LocalDateTime now = LocalDateTime.now();
        TaskView owned = mock(TaskView.class);
        when(owned.id()).thenReturn(7L);
        when(owned.title()).thenReturn("月度复盘");
        when(owned.ownerId()).thenReturn(101L);
        TaskView other = mock(TaskView.class);
        when(other.id()).thenReturn(8L);
        when(other.ownerId()).thenReturn(202L);
        TaskRunView run = mock(TaskRunView.class);
        when(run.id()).thenReturn(91L);
        when(run.traceId()).thenReturn("trace-owned");
        when(run.status()).thenReturn("succeeded");
        when(run.createdAt()).thenReturn(now);
        when(taskQueries.list(500)).thenReturn(List.of(owned, other));
        when(taskRuns.list(7L, 500)).thenReturn(List.of(run));

        var result = controller.executionHistory(1, 20, "success", null, "复盘", null, null);

        assertThat(result.getData()).containsEntry("total", 1).containsEntry("page_size", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items.getFirst()).containsEntry("task_id", 7L).containsEntry("status", "success");
        verify(taskRuns, org.mockito.Mockito.never()).list(8L, 500);
    }

    @Test
    void executesChatBiSqlThroughGovernedDatasetService() {
        stubDatasetCatalog(List.of(dataset(11L, 21L, "sales", "Sales")));
        when(dataQueries.execute(any())).thenReturn(new DataQueryResultView(
            71L, List.of("id", "name"), List.of(List.of(1, "Alice")), 1, 12, false, 9
        ));

        Map<String, Object> response = controller.executeChatBiSql(Map.of(
            "sql", "SELECT id, name FROM users",
            "data_source", "reporting",
            "dataset_name", "sales",
            "sessionid", "session-1"
        ));

        ArgumentCaptor<DataQueryRequest> request = ArgumentCaptor.forClass(DataQueryRequest.class);
        verify(dataQueries).execute(request.capture());
        assertThat(request.getValue().datasetId()).isEqualTo(11L);
        assertThat(request.getValue().sql()).isEqualTo("SELECT id, name FROM users");
        assertThat(response).containsEntry("code", 200).containsEntry("message", "success")
            .containsEntry("execution_mode", "local");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("items", List.of(List.of(1, "Alice")))
            .containsEntry("query_id", 71L).containsEntry("row_count", 1L);
    }

    private NhsV1CompatibilityController controllerWithLifecycle(
        ConversationFinalizationService finalization,
        ConversationCancellationService cancellation
    ) {
        return new NhsV1CompatibilityController(
            principals, mock(PlatformIdGenerator.class),
            conversations, attachments, governance, exports,
            turns, eventQueries, eventStreams, mock(AgentApplicationService.class),
            mock(TaskApplicationService.class), taskQueries, taskRuns, catalogs,
            dataQueries, dataExports, workspace, reports, reportPrincipals,
            null, finalization, null, null, cancellation
        );
    }

    @Test
    void executeChatBiSqlRequiresSessionIdBeforeCatalogLookup() {
        assertThatThrownBy(() -> controller.executeChatBiSql(Map.of(
            "sql", "SELECT 1", "data_source", "reporting"
        )))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(catalogs, dataQueries);
    }

    @Test
    void executeChatBiSqlRejectsAmbiguousDataset() {
        DatasetView first = dataset(11L, 21L, "sales", "Sales");
        DatasetView second = dataset(12L, 21L, "finance", "Finance");
        stubDatasetCatalog(List.of(first, second));

        assertThatThrownBy(() -> controller.executeChatBiSql(Map.of(
            "sql", "SELECT 1", "data_source", "reporting", "sessionid", "session-1"
        )))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("dataset_name")
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(dataQueries, never()).execute(any());
    }

    @Test
    void executeChatBiSqlRejectsMissingDatasetOrSource() {
        when(catalogs.listDatasets(200)).thenReturn(List.of());

        assertThatThrownBy(() -> controller.executeChatBiSql(Map.of(
            "sql", "SELECT 1", "data_source", "missing", "sessionid", "session-1"
        )))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        verify(dataQueries, never()).execute(any());
    }

    @Test
    void executeChatBiSqlPropagatesQueryAuthorizationFailure() {
        stubDatasetCatalog(List.of(dataset(11L, 21L, "sales", "Sales")));
        ServiceException denied = new ServiceException("数据集查询权限不足", HttpStatus.FORBIDDEN);
        when(dataQueries.execute(any())).thenThrow(denied);

        assertThatThrownBy(() -> controller.executeChatBiSql(Map.of(
            "sql", "SELECT id FROM users", "data_source", "reporting", "sessionid", "session-1"
        ))).isSameAs(denied);
    }

    @Test
    void checkChatBiSqlAuthorizationValidatesOwnUserWithoutExecuting() {
        stubDatasetCatalog(List.of(dataset(11L, 21L, "sales", "Sales")));

        Map<String, Object> response = controller.checkChatBiSqlAuthorization(Map.of(
            "username", "MEMBER", "sql", "SELECT id FROM users", "data_source", "reporting"
        ));

        ArgumentCaptor<DataQueryRequest> request = ArgumentCaptor.forClass(DataQueryRequest.class);
        verify(dataQueries).validate(request.capture());
        assertThat(request.getValue().datasetId()).isEqualTo(11L);
        verify(dataQueries, never()).execute(any());
        verify(dataQueries, never()).validateForPrincipal(any(), any());
        assertThat(response).containsEntry("execution_mode", "local");
        assertThat(response.get("data")).isEqualTo(Map.of("allowed", true));
    }

    @Test
    void checkChatBiSqlAuthorizationRejectsCrossUserForNonAdminBeforeCatalogLookup() {
        assertThatThrownBy(() -> controller.checkChatBiSqlAuthorization(Map.of(
            "username", "alice", "sql", "SELECT 1", "data_source", "reporting"
        )))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verifyNoInteractions(catalogs, dataQueries, reportPrincipals);
    }

    @Test
    void checkChatBiSqlAuthorizationLetsAdminValidateResolvedHumanPrincipal() {
        CurrentPrincipal admin = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        CurrentPrincipal target = new CurrentPrincipal(
            202L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        when(principals.currentPrincipal()).thenReturn(admin);
        when(reportPrincipals.resolve("alice")).thenReturn(target);
        stubDatasetCatalog(List.of(dataset(11L, 21L, "sales", "Sales")));

        controller.checkChatBiSqlAuthorization(Map.of(
            "username", "alice", "sql", "SELECT id FROM users", "data_source", "reporting"
        ));

        ArgumentCaptor<DataQueryRequest> request = ArgumentCaptor.forClass(DataQueryRequest.class);
        verify(dataQueries).validateForPrincipal(eq(target), request.capture());
        assertThat(request.getValue().datasetId()).isEqualTo(11L);
        verify(dataQueries, never()).validate(any());
        verify(dataQueries, never()).execute(any());
    }

    @Test
    void checkChatBiSqlAuthorizationPropagatesQueryValidationFailure() {
        stubDatasetCatalog(List.of(dataset(11L, 21L, "sales", "Sales")));
        ServiceException denied = new ServiceException("字段无权访问", HttpStatus.FORBIDDEN);
        when(dataQueries.validate(any())).thenThrow(denied);

        assertThatThrownBy(() -> controller.checkChatBiSqlAuthorization(Map.of(
            "username", "member", "sql", "SELECT secret FROM users", "data_source", "reporting"
        ))).isSameAs(denied);

        verify(dataQueries, never()).execute(any());
    }

    private ExecutionEventQueryService.EventStreamReader eventReader() {
        return (cursor, limit) -> List.of();
    }

    private void stubDatasetCatalog(List<DatasetView> datasets) {
        when(catalogs.listDatasets(200)).thenReturn(datasets);
        when(catalogs.getSource(21L)).thenReturn(source(21L));
    }

    private DatasetView dataset(Long id, Long sourceId, String key, String name) {
        return new DatasetView(
            id, sourceId, key, name, null, "active", List.of("public"),
            1, null, null, 101L, null, null
        );
    }

    private DataSourceView source(Long id) {
        return new DataSourceView(
            id, "reporting", "Reporting", "postgresql", "postgresql://db:5432",
            "analytics", true, "active", Map.of(), 1, 1000, 5000, 1000,
            1024 * 1024, null, null, null, null, null, null, null
        );
    }

    private ConversationMessageRow message(Long id, String role, String content, LocalDateTime createdAt) {
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(id);
        message.setConversationId(7L);
        message.setTraceId("trace-1");
        message.setRole(role);
        message.setContent(content);
        message.setStatus("succeeded");
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setTotalTokens(0);
        message.setCreatedAt(createdAt);
        return message;
    }
}
