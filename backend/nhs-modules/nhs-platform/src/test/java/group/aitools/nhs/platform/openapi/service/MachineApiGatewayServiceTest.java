package group.aitools.nhs.platform.openapi.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.openapi.mapper.MachineApiMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class MachineApiGatewayServiceTest {

    private static final String SECRET =
        "agk_abcdefghijkl.abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
    private ApiCredentialAuthenticator authenticator;
    private MachineApiMapper mapper;
    private MachineApiGatewayService service;

    @BeforeEach
    void setUp() {
        authenticator = mock(ApiCredentialAuthenticator.class);
        mapper = mock(MachineApiMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(100L);
        when(authenticator.authenticate(SECRET)).thenReturn(authenticated(Set.of("tasks:run")));
        when(mapper.insertCall(
            anyLong(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
            anyString(), anyString(), anyString(), anyLong(), anyInt(), any()
        )).thenReturn(1);
        when(mapper.consumeRate(anyLong(), any(), anyInt(), any())).thenReturn(1);
        service = new MachineApiGatewayService(authenticator, mapper, ids, 2);
    }

    @Test
    void validCredentialIsScopedRateLimitedAndAuditedWithoutSecret() {
        var context = service.begin(
            "Bearer " + SECRET, Set.of("open_api"), "tasks:run",
            "task_run_create", "POST", "task", 9L, 100
        );

        assertEquals(20L, context.authenticated().principal().id());
        verify(mapper).insertCall(
            eq(100L), anyString(), eq(10L), eq(30L), eq(20L),
            eq("task_run_create"), eq("POST"), eq("tasks:run"), eq("task"),
            eq(9L), eq(100), any()
        );
        verify(mapper).consumeRate(eq(10L), any(), eq(2), any());
    }

    @Test
    void wrongScopeOrApplicationTypeFailsBeforeAuditInsertion() {
        when(authenticator.authenticate(SECRET)).thenReturn(authenticated(Set.of("tasks:read")));

        ServiceException scope = assertThrows(ServiceException.class, () -> service.begin(
            "Bearer " + SECRET, Set.of("open_api"), "tasks:run",
            "task_run_create", "POST", "task", 9L, 0
        ));
        ServiceException type = assertThrows(ServiceException.class, () -> service.begin(
            "Bearer " + SECRET, Set.of("embed"), "tasks:read",
            "task_run_get", "GET", "task", 9L, 0
        ));

        assertEquals(HttpStatus.FORBIDDEN, scope.getCode());
        assertEquals(HttpStatus.FORBIDDEN, type.getCode());
        verify(mapper, never()).insertCall(
            anyLong(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
            anyString(), anyString(), anyString(), anyLong(), anyInt(), any()
        );
    }

    @Test
    void exhaustedBucketPersistsRateLimitedAuditOutcome() {
        when(mapper.consumeRate(anyLong(), any(), anyInt(), any())).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.begin(
            "Bearer " + SECRET, Set.of("open_api"), "tasks:run",
            "task_run_create", "POST", "task", 9L, 0
        ));

        assertEquals(429, exception.getCode());
        verify(mapper).completeCall(eq(100L), eq("rate_limited"), eq(429), eq(0L),
            eq("RATE_LIMITED"), any());
    }

    @Test
    void revokedCredentialIsRejectedImmediately() {
        when(authenticator.authenticate(SECRET)).thenThrow(
            new ServiceException("API凭证无效或已失效", HttpStatus.UNAUTHORIZED)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ServiceException.class, () ->
            service.begin(
                "Bearer " + SECRET, Set.of("open_api"), "tasks:run",
                "task_run_create", "POST", "task", 9L, 0
            )
        ).getCode());
        verify(mapper, never()).consumeRate(anyLong(), any(), anyInt(), any());
    }

    private AuthenticatedServiceAccount authenticated(Set<String> scopes) {
        CurrentPrincipal principal = new CurrentPrincipal(
            20L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        return new AuthenticatedServiceAccount(
            principal, 10L, "open-app", "open_api", 30L, scopes
        );
    }
}
