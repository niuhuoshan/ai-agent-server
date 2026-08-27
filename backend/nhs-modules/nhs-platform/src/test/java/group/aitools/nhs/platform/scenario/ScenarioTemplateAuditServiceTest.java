package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.LocalDateTime;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ScenarioTemplateAuditServiceTest {

    @Test
    void persistsAContentFreeScenarioLifecycleDecision() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(900L);
        CurrentPrincipal principal = new CurrentPrincipal(
            7L,
            "operator",
            PrincipalType.HUMAN,
            Set.of(PlatformRole.MEMBER)
        );

        new ScenarioTemplateAuditService(mapper, idGenerator).record(
            principal,
            "scenario.uninstall",
            101L,
            "success",
            "业务下线",
            "template=knowledge-qa-assistant"
        );

        verify(mapper).insertEvent(
            eq(900L),
            eq("user"),
            eq(7L),
            eq("scenario.uninstall"),
            eq("scenario_instance"),
            eq(101L),
            isNull(),
            eq("success"),
            eq("业务下线"),
            eq("template=knowledge-qa-assistant"),
            any(LocalDateTime.class)
        );
    }
}
