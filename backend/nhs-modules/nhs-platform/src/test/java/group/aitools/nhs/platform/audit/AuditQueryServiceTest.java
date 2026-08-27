package group.aitools.nhs.platform.audit;

import group.aitools.nhs.platform.audit.domain.AgentAuditEvent;
import group.aitools.nhs.platform.audit.mapper.AgentAuditQueryMapper;
import group.aitools.nhs.platform.audit.service.AuditQueryService;
import group.aitools.nhs.platform.audit.web.AuditEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AuditQueryServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        101L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentAuditQueryMapper mapper;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        mapper = mock(AgentAuditQueryMapper.class);
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        service = new AuditQueryService(principalProvider, authorizationEnforcer, mapper);
    }

    @Test
    void searchUsesAuditedUiAuthorizationAndReturnsSanitizedProjection() {
        LocalDateTime from = LocalDateTime.now().minusHours(2);
        LocalDateTime to = LocalDateTime.now();
        AgentAuditEvent row = new AgentAuditEvent();
        row.setId(900L);
        row.setActorType("service_account");
        row.setActorId(42L);
        row.setAction("invoke");
        row.setResourceType("tool");
        row.setResourceId(7L);
        row.setDecision("allow");
        row.setCreatedAt(to.minusMinutes(1));
        when(mapper.search(
            "service_account", 42L, "invoke", "tool", 7L, null, null,
            "allow", from, to, 1000L, 50
        )).thenReturn(List.of(row));

        List<AuditEventView> result = service.search(
            "service_account", 42L, "invoke", "tool", 7L, null, null,
            "allow", from, to, 1000L, 50
        );

        assertEquals(1, result.size());
        assertEquals(900L, result.getFirst().id());
        ArgumentCaptor<PermissionContext> context = ArgumentCaptor.forClass(PermissionContext.class);
        verify(authorizationEnforcer).requireAllowed(org.mockito.ArgumentMatchers.eq(ADMIN), context.capture());
        assertEquals("audit", context.getValue().resourceType());
        assertEquals("list", context.getValue().action());
        assertTrue(context.getValue().userInterfaceOperation());
        Set<String> fieldNames = java.util.Arrays.stream(AuditEventView.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertFalse(fieldNames.contains("metadataJson"));
        assertFalse(fieldNames.contains("requestSummary"));
        assertFalse(fieldNames.contains("resultSummary"));
    }

    @Test
    void explicitAuthorizationDenialStopsQuery() {
        when(authorizationEnforcer.requireAllowed(any(), any()))
            .thenThrow(new ServiceException("denied", HttpStatus.FORBIDDEN));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.search(null, null, null, null, null, null, null,
                null, null, null, null, 50)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(mapper, never()).search(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void rangeLargerThanNinetyDaysIsRejected() {
        LocalDateTime to = LocalDateTime.now();
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.search(null, null, null, null, null, null, null,
                null, to.minusDays(91), to, null, 50)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
    }
}
