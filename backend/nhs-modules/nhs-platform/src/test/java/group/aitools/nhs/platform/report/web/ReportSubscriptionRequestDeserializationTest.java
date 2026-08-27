package group.aitools.nhs.platform.report.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ReportSubscriptionRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void reportNativeScheduleIsAcceptedAndAutomationTriggerFieldIsRejected() {
        CreateReportSubscriptionRequest request = jsonMapper.readValue("""
            {
              "scheduleType":"cron",
              "cronExpr":"0 0 9 * * *",
              "timezone":"Asia/Shanghai",
              "paramsJson":"{}",
              "notifyPolicyJson":"{\\"channel\\":\\"inbox\\"}",
              "maxAttempts":3
            }
            """, CreateReportSubscriptionRequest.class);

        assertThat(request.scheduleType()).isEqualTo("cron");
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue("""
            {
              "triggerId":501,
              "timezone":"Asia/Shanghai",
              "paramsJson":"{}",
              "notifyPolicyJson":"{}"
            }
            """, CreateReportSubscriptionRequest.class));
    }
}
