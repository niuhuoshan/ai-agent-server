package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.report.domain.AgentReport;
import group.aitools.nhs.platform.report.domain.AgentReportRun;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import group.aitools.nhs.platform.report.web.CreateReportSubscriptionRequest;
import group.aitools.nhs.platform.report.web.UpdateReportSubscriptionStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportApplicationServiceTest {

    private PlatformIdGenerator ids;
    private AgentReportMapper mapper;
    private DataQueryExecutionService queries;
    private ReportExecutionPrincipalResolver executionPrincipals;
    private ReportApplicationService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        ids = mock(PlatformIdGenerator.class);
        mapper = mock(AgentReportMapper.class);
        queries = mock(DataQueryExecutionService.class);
        executionPrincipals = mock(ReportExecutionPrincipalResolver.class);
        service = new ReportApplicationService(
            principals, ids, mapper, queries, JsonMapper.builder().build(), new ReportScheduleCalculator(),
            executionPrincipals
        );
    }

    @Test
    void subscriptionRunUsesPersistedParametersAndRecordsRealQueryRun() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        subscription.setParamsJson("{\"month\":\"2026-08\"}");
        AgentReport report = new AgentReport();
        report.setId(9L);
        report.setOwnerId(101L);
        report.setDatasetId(3L);
        report.setName("销售日报");
        report.setSqlTemplate("SELECT * FROM sales WHERE month = {{month}}");
        report.setStatus("active");
        when(mapper.selectSubscription(41L)).thenReturn(subscription);
        when(mapper.selectById(9L)).thenReturn(report);
        when(ids.nextId()).thenReturn(81L);
        when(queries.execute(any(DataQueryRequest.class))).thenReturn(new DataQueryResultView(
            71L, List.of("amount"), List.of(List.of(10)), 1, 2, false, 15
        ));

        DataQueryResultView result = service.executeSubscription(41L);

        assertThat(result.queryId()).isEqualTo(71L);
        ArgumentCaptor<AgentReportRun> inserted = ArgumentCaptor.forClass(AgentReportRun.class);
        verify(mapper).insertRun(inserted.capture());
        assertThat(inserted.getValue().getTriggerType()).isEqualTo("subscription");
        assertThat(inserted.getValue().getExecutedSql()).isEqualTo(
            "SELECT * FROM sales WHERE month = '2026-08'"
        );
        assertThat(inserted.getValue().getResolvedParamsJson()).contains("2026-08");
        ArgumentCaptor<AgentReportRun> finished = ArgumentCaptor.forClass(AgentReportRun.class);
        verify(mapper).finishRun(finished.capture());
        assertThat(finished.getValue().getRunId()).isEqualTo(71L);
        assertThat(finished.getValue().getStatus()).isEqualTo("succeeded");
        verify(mapper).recordSubscriptionRun(
            org.mockito.ArgumentMatchers.eq(41L),
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.eq(101L),
            any(LocalDateTime.class)
        );
    }

    @Test
    void visibleSubscriptionsAreScopedByCurrentOwner() {
        service.visibleSubscriptions(25);

        verify(mapper).selectVisibleSubscriptions(101L, false, 25);
    }

    @Test
    void scheduledRunUsesResolvedOwnerWithoutReadingHttpPrincipal() {
        CurrentPrincipal owner = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        subscription.setCreateBy(101L);
        subscription.setStatus("active");
        subscription.setParamsJson("{}");
        AgentReport report = new AgentReport();
        report.setId(9L);
        report.setDatasetId(3L);
        report.setName("销售日报");
        report.setSqlTemplate("SELECT 1");
        report.setStatus("active");
        when(mapper.selectSubscription(41L)).thenReturn(subscription);
        when(mapper.selectById(9L)).thenReturn(report);
        when(executionPrincipals.resolve(101L)).thenReturn(owner);
        when(ids.nextId()).thenReturn(81L);
        when(queries.executeBackground(eq(owner), any(DataQueryRequest.class))).thenReturn(
            new DataQueryResultView(71L, List.of("value"), List.of(List.of(1)), 1, 1, false, 2)
        );

        var result = service.executeScheduledSubscription(41L);

        assertThat(result.result().queryId()).isEqualTo(71L);
        verify(queries).executeBackground(eq(owner), any(DataQueryRequest.class));
        verify(queries, never()).execute(any(DataQueryRequest.class));
    }

    @Test
    void reportScopedSubscriptionRunRejectsMismatchedPathReport() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        when(mapper.selectSubscription(41L)).thenReturn(subscription);

        assertThatThrownBy(() -> service.executeSubscription(10L, 41L))
            .hasMessageContaining("不属于当前报表");
    }

    @Test
    void createsActiveIntervalSubscriptionWithPersistedNextRun() {
        AgentReport report = new AgentReport();
        report.setId(9L);
        report.setOwnerId(101L);
        when(mapper.selectById(9L)).thenReturn(report);
        when(ids.nextId()).thenReturn(41L);

        var result = service.createSubscription(9L, new CreateReportSubscriptionRequest(
            "interval", null, 15, "UTC", "{}", "{\"channel\":\"inbox\"}", 4
        ));

        ArgumentCaptor<AgentReportSubscription> inserted =
            ArgumentCaptor.forClass(AgentReportSubscription.class);
        verify(mapper).insertSubscription(inserted.capture());
        assertThat(inserted.getValue().getScheduleType()).isEqualTo("interval");
        assertThat(inserted.getValue().getIntervalMinutes()).isEqualTo(15);
        assertThat(inserted.getValue().getStatus()).isEqualTo("active");
        assertThat(inserted.getValue().getNextRunAt()).isAfter(inserted.getValue().getCreateTime());
        assertThat(result.nextRunAt()).isEqualTo(inserted.getValue().getNextRunAt());
    }

    @Test
    void reportScopedPauseRejectsMismatchedPathBeforeMutation() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        when(mapper.selectSubscription(41L)).thenReturn(subscription);

        assertThatThrownBy(() -> service.updateSubscriptionStatus(
            10L, 41L, new UpdateReportSubscriptionStatusRequest("paused")
        )).hasMessageContaining("不属于当前报表");

        verify(mapper).selectSubscription(41L);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void reportScopedDeleteRejectsMismatchedPathBeforeMutation() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        when(mapper.selectSubscription(41L)).thenReturn(subscription);

        assertThatThrownBy(() -> service.deleteSubscription(10L, 41L))
            .hasMessageContaining("不属于当前报表");

        verify(mapper).selectSubscription(41L);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }
}
