package group.aitools.nhs.platform.canvas;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.canvas.service.CanvasActionAuditService;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class CanvasActionAuditServiceTest {

    @Test
    void persistsSanitizedCanvasOutcomeWithoutTheCanvasContent() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(901L);
        CanvasActionAuditService service = new CanvasActionAuditService(mapper, idGenerator);
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );

        service.record(
            principal, "update", 501L, "success", "completed",
            "conversationId=7,canvasId=501,expectedVersion=1"
        );

        verify(mapper).insertEvent(
            eq(901L), eq("user"), eq(101L), eq("update"),
            eq("conversation_canvas"), eq(501L), eq(null), eq("success"),
            eq("completed"), eq("conversationId=7,canvasId=501,expectedVersion=1"),
            any(LocalDateTime.class)
        );
    }
}
