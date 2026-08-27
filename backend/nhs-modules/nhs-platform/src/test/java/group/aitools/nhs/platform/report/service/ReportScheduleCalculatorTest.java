package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class ReportScheduleCalculatorTest {

    private final ReportScheduleCalculator calculator = new ReportScheduleCalculator();

    @Test
    void cronScheduleIsCalculatedInSubscriptionTimezoneAndStoredAsUtc() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setScheduleType("cron");
        subscription.setCronExpr("0 9 * * *");
        subscription.setTimezone("Asia/Shanghai");

        LocalDateTime next = calculator.next(
            subscription, LocalDateTime.of(2026, 8, 16, 0, 30)
        );

        assertThat(subscription.getCronExpr()).isEqualTo("0 9 * * *");
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 16, 1, 0));
    }

    @Test
    void intervalScheduleAdvancesByConfiguredMinutes() {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setScheduleType("interval");
        subscription.setIntervalMinutes(15);

        LocalDateTime next = calculator.next(
            subscription, LocalDateTime.of(2026, 8, 16, 1, 2)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 16, 1, 17));
    }
}
