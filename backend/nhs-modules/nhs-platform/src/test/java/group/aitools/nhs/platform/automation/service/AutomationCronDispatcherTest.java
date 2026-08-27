package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("dev")
class AutomationCronDispatcherTest {

    private final CronScheduleCalculator calculator = new CronScheduleCalculator();
    private final AutomationCronDispatcher dispatcher = new AutomationCronDispatcher(
        mock(AutomationMapper.class), mock(AutomationApplicationService.class), calculator
    );

    @Test
    void fiveFieldCronIsNormalizedAndCalculatedInConfiguredZone() {
        String normalized = calculator.normalize("*/5 * * * *");
        LocalDateTime next = calculator.next(
            normalized,
            calculator.zone("Asia/Shanghai"),
            LocalDateTime.of(2026, 8, 14, 0, 1, 0)
        );

        assertEquals("0 */5 * * * *", normalized);
        assertEquals(LocalDateTime.of(2026, 8, 14, 0, 5, 0), next);
    }

    @Test
    void catchUpPolicyHasAHardBoundAndSkipsRemainingBacklog() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 0, 0);
        AutomationTrigger trigger = trigger("catch_up", now.minusHours(2), 3);

        AutomationCronDispatcher.DispatchPlan plan = dispatcher.plan(trigger, now);

        assertEquals(3, plan.fireTimes().size());
        assertTrue(plan.nextRunAt().isAfter(now));
    }

    @Test
    void skipPolicyDoesNotReplayOldMisfire() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 12, 0, 0);
        AutomationTrigger trigger = trigger("skip", now.minusMinutes(10), 10);

        AutomationCronDispatcher.DispatchPlan plan = dispatcher.plan(trigger, now);

        assertTrue(plan.fireTimes().isEmpty());
        assertTrue(plan.nextRunAt().isAfter(now));
    }

    private AutomationTrigger trigger(String policy, LocalDateTime due, int catchup) {
        AutomationTrigger trigger = new AutomationTrigger();
        trigger.setCronExpr("* * * * * *");
        trigger.setTimezone("UTC");
        trigger.setMisfirePolicy(policy);
        trigger.setMaxCatchupCount(catchup);
        trigger.setNextRunAt(due);
        return trigger;
    }
}
