package group.aitools.nhs.platform.audit;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ToolInvocationAuditServiceTest {

    @Test
    void uiTestAuditStoresOnlyHashesAndSourceMetadata() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(90L);
        ToolInvocationAuditService service = new ToolInvocationAuditService(
            mapper, ids, JsonMapper.builder().build()
        );
        CurrentPrincipal principal = new CurrentPrincipal(
            9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        ArgumentCaptor<String> traceId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestSummary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultSummary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);

        service.recordUiTest(
            principal, 500L, "{\"password\":\"raw-argument\"}",
            "{\"token\":\"raw-result\"}", true, "UI_TOOL_TEST_SUCCEEDED"
        );

        verify(mapper).insertToolInvocation(
            eq(90L), traceId.capture(), eq("user"), eq(9L), eq(500L),
            eq(null), eq(null), eq("success"), eq("UI_TOOL_TEST_SUCCEEDED"),
            requestSummary.capture(), resultSummary.capture(), metadata.capture(),
            any(LocalDateTime.class)
        );
        assertTrue(traceId.getValue().startsWith("tool-test-"));
        assertTrue(requestSummary.getValue().startsWith("sha256="));
        assertTrue(resultSummary.getValue().startsWith("sha256="));
        assertFalse(requestSummary.getValue().contains("raw-argument"));
        assertFalse(resultSummary.getValue().contains("raw-result"));
        assertEquals("{\"source\":\"ui_tool_test\"}", metadata.getValue());
    }
}
