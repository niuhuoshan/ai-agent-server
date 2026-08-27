package group.aitools.nhs.platform.memory;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("dev")
class MemoryScopeAuthorizationServiceTest {

    private final MemoryScopeAuthorizationService service = new MemoryScopeAuthorizationService(
        mock(AuthorizationEnforcer.class), mock(TaskVisibilityService.class),
        mock(AgentProjectMapper.class), mock(AgentTaskMapper.class), mock(TaskControlMapper.class)
    );

    @Test
    void personalMemoryIsPrivateEvenFromAdministratorAndServiceAccount() {
        CurrentPrincipal owner = new CurrentPrincipal(
            101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        CurrentPrincipal administrator = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        CurrentPrincipal serviceAccount = new CurrentPrincipal(
            101L, "service", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );

        assertTrue(service.canView(owner, "user", 101L));
        assertFalse(service.canView(administrator, "user", 101L));
        assertFalse(service.canView(serviceAccount, "user", 101L));
        assertThrows(
            ServiceException.class,
            () -> service.requireView(administrator, "user", 101L, true)
        );
    }
}
