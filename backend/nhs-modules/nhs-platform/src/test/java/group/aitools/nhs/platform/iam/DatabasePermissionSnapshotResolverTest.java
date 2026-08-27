package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.persistence.mapper.PermissionRuleQueryMapper;
import group.aitools.nhs.platform.iam.persistence.row.PermissionBindingRow;
import group.aitools.nhs.platform.iam.persistence.row.TaskAccessRuleRow;
import group.aitools.nhs.platform.iam.persistence.row.PermissionRuleRow;
import group.aitools.nhs.platform.iam.service.impl.DatabasePermissionSnapshotResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DatabasePermissionSnapshotResolverTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal SERVICE = new CurrentPrincipal(
        101L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private PermissionRuleQueryMapper queryMapper;
    private DatabasePermissionSnapshotResolver resolver;

    @BeforeEach
    void setUp() {
        queryMapper = mock(PermissionRuleQueryMapper.class);
        resolver = new DatabasePermissionSnapshotResolver(queryMapper, JsonMapper.builder().build());
        when(queryMapper.selectEffectiveRelationalRules(101L)).thenReturn(List.of());
    }

    @Test
    void malformedSnapshotFailsClosedForTheRequestedCapability() {
        PermissionBindingRow binding = new PermissionBindingRow();
        binding.setId(20L);
        binding.setBindingType("snapshot");
        binding.setProfileVersion(3);
        binding.setSnapshotJson("{not-json");
        when(queryMapper.selectActiveBinding(101L)).thenReturn(binding);

        PermissionSnapshot snapshot = resolver.resolve(
            MEMBER, PermissionContext.active("tool", 8L, "invoke")
        );

        assertEquals(1, snapshot.rules().size());
        assertEquals(PermissionEffect.DENY, snapshot.rules().getFirst().effect());
        assertTrue(snapshot.rules().getFirst().matches(PermissionContext.active("tool", 8L, "invoke")));
    }

    @Test
    void serviceAccountCannotInheritHumanPermissionBindingWithSameNumericId() {
        PermissionSnapshot snapshot = resolver.resolve(
            SERVICE, PermissionContext.active("tool", 8L, "invoke")
        );

        assertTrue(snapshot.rules().isEmpty());
        verify(queryMapper, never()).selectActiveBinding(101L);
        verify(queryMapper, never()).selectEffectiveRelationalRules(101L);
    }

    @Test
    void serviceAccountUsesOnlyItsIndependentMachineGrant() {
        PermissionRuleRow grant = new PermissionRuleRow();
        grant.setResourceType("tool");
        grant.setResourceId(8L);
        grant.setAction("invoke");
        grant.setEffect("allow");
        grant.setSource("SERVICE_ACCOUNT_GRANT");
        grant.setSourceReference("service-account-grant:91");
        grant.setReason("automation tool");
        when(queryMapper.selectEffectiveServiceAccountRules(101L)).thenReturn(List.of(grant));

        PermissionSnapshot snapshot = resolver.resolve(
            SERVICE, PermissionContext.active("tool", 8L, "invoke")
        );

        assertEquals(1, snapshot.rules().size());
        assertEquals(PermissionEffect.ALLOW, snapshot.rules().getFirst().effect());
        assertEquals("service-account-grant:91", snapshot.rules().getFirst().sourceReference());
        verify(queryMapper, never()).selectActiveBinding(101L);
        verify(queryMapper, never()).selectEffectiveRelationalRules(101L);
    }

    @Test
    void taskAclForAnotherSubjectDoesNotGrantAccess() {
        TaskAccessRuleRow row = taskRule(31L, "user", 999L, null, "allow");
        when(queryMapper.selectActiveTaskAccessRules(7L, null, "view")).thenReturn(List.of(row));

        PermissionSnapshot snapshot = resolver.resolve(
            MEMBER, PermissionContext.active("task", 7L, "view")
        );

        assertTrue(snapshot.rules().isEmpty());
    }

    @Test
    void matchingTaskAclIsResolvedAsAnExplicitRule() {
        TaskAccessRuleRow row = taskRule(32L, "user", 101L, null, "allow");
        when(queryMapper.selectActiveTaskAccessRules(7L, null, "view")).thenReturn(List.of(row));

        PermissionSnapshot snapshot = resolver.resolve(
            MEMBER, PermissionContext.active("task", 7L, "view")
        );

        assertEquals(1, snapshot.rules().size());
        assertEquals(PermissionEffect.ALLOW, snapshot.rules().getFirst().effect());
        assertEquals("task-access-rule:32", snapshot.rules().getFirst().sourceReference());
    }

    private TaskAccessRuleRow taskRule(
        Long id,
        String subjectType,
        Long subjectId,
        String subjectKey,
        String effect
    ) {
        TaskAccessRuleRow row = new TaskAccessRuleRow();
        row.setId(id);
        row.setTaskId(7L);
        row.setSubjectType(subjectType);
        row.setSubjectId(subjectId);
        row.setSubjectKey(subjectKey);
        row.setAction("view");
        row.setEffect(effect);
        return row;
    }
}
