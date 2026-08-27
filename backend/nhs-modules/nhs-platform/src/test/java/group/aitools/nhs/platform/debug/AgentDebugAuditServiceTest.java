package group.aitools.nhs.platform.debug;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.debug.service.AgentDebugAuditService;
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
class AgentDebugAuditServiceTest {

    @Test
    void persistsContentFreeRunIdentityAndInputHash() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(901L);
        AgentDebugAuditService service = new AgentDebugAuditService(mapper, ids);
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "debugger", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );

        service.record(
            principal, "debug_create", 201L, 301L, "success", "completed",
            "agentId=10,versionId=11,inputSha256=abc123"
        );

        verify(mapper).insertEvent(
            eq(901L), eq("user"), eq(101L), eq("debug_create"),
            eq("agent_debug_run"), eq(201L), eq(301L), eq("success"),
            eq("completed"), eq("agentId=10,versionId=11,inputSha256=abc123"),
            any(LocalDateTime.class)
        );
    }
}
