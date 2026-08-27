package group.aitools.nhs.platform.nhs.portal.prompt;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptAuditServiceTest {

    private final AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
    private final PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
    private final CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
    private final PromptAuditService service = new PromptAuditService(mapper, ids, principals);

    @Test
    void restoreAuditContainsVersionLineageWithoutPromptContent() {
        when(ids.nextId()).thenReturn(9901L);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "operator", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));

        service.recordRestore(
            "agent", "agent_12",
            new PortalPromptService.RestoreResult(31L, 3, 44L, 5)
        );

        verify(mapper).insertEvent(
            eq(9901L), eq("user"), eq(7L), eq("prompt_restore"), eq("agent_version"), eq(44L),
            eq(null), eq("success"), eq("source_version=3"),
            eq("source=agent,target=agent_12,sourceVersion=3,restoredVersion=5"),
            org.mockito.ArgumentMatchers.any()
        );
    }
}
