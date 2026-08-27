package group.aitools.nhs.platform.migration;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.migration.domain.LegacyExecutionArchive;
import group.aitools.nhs.platform.migration.mapper.LegacyExecutionArchiveMapper;
import group.aitools.nhs.platform.migration.service.LegacyExecutionArchiveQueryService;
import group.aitools.nhs.platform.migration.web.LegacyExecutionArchiveView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class LegacyExecutionArchiveQueryServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        101L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private AuthorizationEnforcer authorizationEnforcer;
    private LegacyExecutionArchiveMapper mapper;
    private LegacyExecutionArchiveQueryService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        mapper = mock(LegacyExecutionArchiveMapper.class);
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        service = new LegacyExecutionArchiveQueryService(principalProvider, authorizationEnforcer, mapper);
    }

    @Test
    void searchRequiresAuditAccessAndNeverProjectsPayload() {
        LegacyExecutionArchive row = new LegacyExecutionArchive();
        row.setId(9L);
        row.setSourceTraceId("trace-1");
        row.setSourceExecutionId("exec-1");
        row.setSummary("redacted summary");
        when(mapper.search("trace-1", "exec-1", "success", 20L, 50)).thenReturn(List.of(row));

        List<LegacyExecutionArchiveView> result = service.search(
            "trace-1", "exec-1", "success", 20L, 50
        );

        assertEquals(9L, result.getFirst().id());
        ArgumentCaptor<PermissionContext> context = ArgumentCaptor.forClass(PermissionContext.class);
        verify(authorizationEnforcer).requireAllowed(org.mockito.ArgumentMatchers.eq(ADMIN), context.capture());
        assertEquals("audit", context.getValue().resourceType());
        assertTrue(context.getValue().userInterfaceOperation());
        Set<String> fields = Arrays.stream(LegacyExecutionArchiveView.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet());
        assertFalse(fields.contains("payloadJson"));
    }

    @Test
    void unsafeIdentifierIsRejectedBeforeDatabaseQuery() {
        assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> service.search("trace%", null, null, null, 50)
        );
        verify(mapper, never()).search(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt()
        );
    }
}
