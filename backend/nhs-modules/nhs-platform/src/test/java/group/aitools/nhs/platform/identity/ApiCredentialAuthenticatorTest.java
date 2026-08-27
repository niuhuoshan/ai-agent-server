package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.domain.ApiCredentialAuthenticationRow;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ApiCredentialAuthenticatorTest {

    private static final String SECRET =
        "agk_abcdefghijkl.abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

    @Test
    void validCredentialCreatesOnlyAServiceAccountPrincipalAndIntersectsScopes() {
        MachineIdentityMapper mapper = mock(MachineIdentityMapper.class);
        ApiCredentialAuthenticationRow row = activeRow();
        row.setApplicationScopeJson("[\"tasks:read\"]");
        row.setCredentialScopeJson("[\"tasks:read\",\"tasks:run\"]");
        when(mapper.selectCredentialAuthentication(anyString())).thenReturn(row);
        when(mapper.touchCredential(any(), any())).thenReturn(1);
        when(mapper.touchServiceAccount(any(), any())).thenReturn(1);
        ApiCredentialAuthenticator authenticator = new ApiCredentialAuthenticator(
            mapper, JsonMapper.builder().build()
        );

        var authenticated = authenticator.authenticate(SECRET);

        assertEquals(PrincipalType.SERVICE_ACCOUNT, authenticated.principal().type());
        assertEquals(Set.of(PlatformRole.SERVICE_ACCOUNT), authenticated.principal().roles());
        assertEquals(Set.of("tasks:read"), authenticated.scopes());
    }

    @Test
    void revokedOrMalformedCredentialFailsWithoutTouchingUsage() {
        MachineIdentityMapper mapper = mock(MachineIdentityMapper.class);
        ApiCredentialAuthenticationRow row = activeRow();
        row.setRevokedAt(LocalDateTime.now());
        when(mapper.selectCredentialAuthentication(anyString())).thenReturn(row);
        ApiCredentialAuthenticator authenticator = new ApiCredentialAuthenticator(
            mapper, JsonMapper.builder().build()
        );

        ServiceException malformed = assertThrows(
            ServiceException.class, () -> authenticator.authenticate("not-a-key")
        );
        ServiceException revoked = assertThrows(
            ServiceException.class, () -> authenticator.authenticate(SECRET)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, malformed.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.getCode());
        verify(mapper, never()).touchCredential(any(), any());
        verify(mapper, never()).touchServiceAccount(any(), any());
    }

    private ApiCredentialAuthenticationRow activeRow() {
        ApiCredentialAuthenticationRow row = new ApiCredentialAuthenticationRow();
        row.setCredentialId(30L);
        row.setApplicationId(10L);
        row.setAppKey("app");
        row.setApplicationType("webhook");
        row.setApplicationStatus("active");
        row.setApplicationScopeJson("[\"tasks:read\"]");
        row.setServiceAccountId(20L);
        row.setAccountKey("worker");
        row.setAccountName("Worker");
        row.setServiceAccountStatus("active");
        row.setCredentialScopeJson("[\"tasks:read\"]");
        row.setCredentialExpiresAt(LocalDateTime.now().plusDays(1));
        return row;
    }
}
