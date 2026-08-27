package group.aitools.nhs.platform.report.web;

import group.aitools.nhs.platform.report.service.ReportApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("dev")
class PlatformReportControllerTest {

    private final ReportApplicationService service = mock(ReportApplicationService.class);
    private final PlatformReportController controller = new PlatformReportController(service);

    @Test
    void delegatesReportLifecycleAndExecutionEndpoints() {
        CreateReportRequest create = new CreateReportRequest(
            "sales.daily", "Daily sales", 11L, "SELECT 1", "{}", "private"
        );
        UpdateReportRequest update = new UpdateReportRequest(
            "Daily sales", 11L, "SELECT 1", "{}", "active", "private"
        );
        ExecuteReportRequest execute = new ExecuteReportRequest(Map.of("region", "east"));

        controller.list("active", "sales", 20);
        controller.get(101L);
        controller.create(create);
        controller.update(101L, update);
        controller.archive(101L);
        controller.execute(101L, execute);
        controller.runs(101L, 20);

        verify(service).list("active", "sales", 20);
        verify(service).get(101L);
        verify(service).create(create);
        verify(service).update(101L, update);
        verify(service).archive(101L);
        verify(service).execute(101L, execute);
        verify(service).runs(101L, 20);
    }

    @Test
    void delegatesSubscriptionLifecycleEndpoints() {
        CreateReportSubscriptionRequest create = new CreateReportSubscriptionRequest(
            "cron", "0 0 9 * * *", null, "Asia/Shanghai", "{}",
            "{\"channel\":\"inbox\"}", 3
        );
        UpdateReportSubscriptionStatusRequest update =
            new UpdateReportSubscriptionStatusRequest("paused");

        controller.subscriptions(101L);
        controller.createSubscription(101L, create);
        controller.updateSubscriptionStatus(101L, 601L, update);
        controller.executeSubscription(101L, 601L);
        controller.deleteSubscription(101L, 601L);

        verify(service).subscriptions(101L);
        verify(service).createSubscription(101L, create);
        verify(service).updateSubscriptionStatus(101L, 601L, update);
        verify(service).executeSubscription(101L, 601L);
        verify(service).deleteSubscription(101L, 601L);
    }
}
