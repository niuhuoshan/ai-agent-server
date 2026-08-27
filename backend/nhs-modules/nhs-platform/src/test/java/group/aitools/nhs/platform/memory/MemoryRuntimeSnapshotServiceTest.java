package group.aitools.nhs.platform.memory;

import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryRuntimeSnapshotService;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class MemoryRuntimeSnapshotServiceTest {

    @Test
    void freezesTaskProjectThenUserMemoryWithRevisionAndHash() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        MemoryScopeAuthorizationService authorization = mock(MemoryScopeAuthorizationService.class);
        MemoryCatalogMapper mapper = mock(MemoryCatalogMapper.class);
        when(authorization.canView(principal, "task", 3001L)).thenReturn(true);
        when(authorization.canView(principal, "project", 2001L)).thenReturn(true);
        when(authorization.canView(principal, "user", 101L)).thenReturn(true);
        when(mapper.selectApprovedForSnapshot("task", 3001L, 10))
            .thenReturn(List.of(memory(1L, "task", 3001L)));
        when(mapper.selectApprovedForSnapshot("project", 2001L, 10))
            .thenReturn(List.of(memory(2L, "project", 2001L)));
        when(mapper.selectApprovedForSnapshot("user", 101L, 10))
            .thenReturn(List.of(memory(3L, "user", 101L)));
        TaskRunDefinitionRow definition = new TaskRunDefinitionRow();
        definition.setTaskId(3001L);
        definition.setProjectId(2001L);
        MemoryRuntimeSnapshotService service = new MemoryRuntimeSnapshotService(
            authorization, mapper
        );

        var snapshot = service.snapshot(principal, definition);

        assertEquals(List.of(1L, 2L, 3L), snapshot.stream().map(v -> v.get("id")).toList());
        assertEquals(4L, snapshot.getFirst().get("revisionNo"));
        assertEquals("a".repeat(64), snapshot.getFirst().get("contentHash"));
    }

    @Test
    void serviceAccountNeverReceivesHumanOrSharedMemorySnapshot() {
        CurrentPrincipal servicePrincipal = new CurrentPrincipal(
            101L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        MemoryScopeAuthorizationService authorization = mock(MemoryScopeAuthorizationService.class);
        MemoryCatalogMapper mapper = mock(MemoryCatalogMapper.class);
        TaskRunDefinitionRow definition = new TaskRunDefinitionRow();
        definition.setTaskId(3001L);
        definition.setProjectId(2001L);
        MemoryRuntimeSnapshotService service = new MemoryRuntimeSnapshotService(authorization, mapper);

        assertEquals(List.of(), service.snapshot(servicePrincipal, definition));
        verifyNoInteractions(authorization, mapper);
    }

    private AgentMemory memory(Long id, String scopeType, Long scopeId) {
        AgentMemory memory = new AgentMemory();
        memory.setId(id);
        memory.setScopeType(scopeType);
        memory.setScopeId(scopeId);
        memory.setMemoryType("user".equals(scopeType) ? "preference" : "fact");
        memory.setContent("memory-" + id);
        memory.setContentHash("a".repeat(64));
        memory.setRevisionNo(4L);
        return memory;
    }
}
