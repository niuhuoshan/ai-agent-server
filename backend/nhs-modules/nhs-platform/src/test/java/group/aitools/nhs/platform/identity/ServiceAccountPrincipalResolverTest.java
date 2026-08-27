package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.domain.ServiceAccount;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ServiceAccountPrincipalResolverTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        9L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    @Test
    void omittedAccountSelectsAnActiveAccountOwnedByFrozenHuman() {
        MachineIdentityMapper mapper = mock(MachineIdentityMapper.class);
        ServiceAccount account = account(20L, 9L);
        when(mapper.selectActiveAutomationAccountsByOwner(any(), any(), any(Integer.class)))
            .thenReturn(List.of(account));
        ServiceAccountPrincipalResolver resolver = new ServiceAccountPrincipalResolver(mapper);

        CurrentPrincipal resolved = resolver.requireOwnedForAutomation(MEMBER, null);

        assertEquals(20L, resolved.id());
        assertEquals(PrincipalType.SERVICE_ACCOUNT, resolved.type());
        verify(mapper).selectActiveAutomationAccountsByOwner(
            org.mockito.ArgumentMatchers.eq(9L), any(), org.mockito.ArgumentMatchers.eq(1)
        );
    }

    @Test
    void frozenHumanCannotSelectAnotherUsersServiceAccount() {
        MachineIdentityMapper mapper = mock(MachineIdentityMapper.class);
        when(mapper.selectServiceAccount(20L)).thenReturn(account(20L, 99L));
        ServiceAccountPrincipalResolver resolver = new ServiceAccountPrincipalResolver(mapper);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> resolver.requireOwnedForAutomation(MEMBER, 20L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
    }

    private ServiceAccount account(Long id, Long ownerId) {
        ServiceAccount account = new ServiceAccount();
        account.setId(id);
        account.setOwnerId(ownerId);
        account.setAccountKey("automation-" + id);
        account.setName("Automation");
        account.setStatus("active");
        return account;
    }
}
