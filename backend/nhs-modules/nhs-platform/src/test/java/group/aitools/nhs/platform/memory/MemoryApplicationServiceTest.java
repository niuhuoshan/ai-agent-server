package group.aitools.nhs.platform.memory;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryApplicationService;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import group.aitools.nhs.platform.memory.web.CreateMemoryRequest;
import group.aitools.nhs.platform.memory.web.UpdateMemoryRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class MemoryApplicationServiceTest {

    private MemoryCatalogMapper mapper;
    private MemoryScopeAuthorizationService authorization;
    private MemoryApplicationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MemoryCatalogMapper.class);
        authorization = mock(MemoryScopeAuthorizationService.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(1001L);
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        service = new MemoryApplicationService(
            () -> principal, authorization, ids, mapper, JsonMapper.builder().build()
        );
    }

    @Test
    void explicitPersonalMemoryIsImmediatelyApprovedByItsOwner() {
        ArgumentCaptor<AgentMemory> inserted = ArgumentCaptor.forClass(AgentMemory.class);
        when(mapper.insertMemory(any())).thenReturn(1);

        var result = service.create("user", 101L, createRequest(Map.of("source", "settings")));

        verify(mapper).insertMemory(inserted.capture());
        AgentMemory memory = inserted.getValue();
        assertEquals("approved", result.reviewStatus());
        assertEquals(101L, result.reviewedBy());
        assertNotNull(result.reviewedAt());
        assertEquals(64, memory.getContentHash().length());
    }

    @Test
    void sharedMemoryUpdateReturnsToPendingReview() {
        AgentMemory existing = existingProjectMemory();
        when(mapper.selectById(1001L)).thenReturn(existing);
        when(mapper.updateMemory(any())).thenReturn(1);

        var result = service.update(1001L, new UpdateMemoryRequest(
            3L, "fact", "Updated approved project fact", "manual", null,
            0.9, "internal", null, Map.of()
        ));

        assertEquals("pending", result.reviewStatus());
        assertEquals(4L, result.revisionNo());
        verify(mapper).updateMemory(existing);
    }

    @Test
    void metadataCannotSmuggleCredentialFields() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create("user", 101L, createRequest(Map.of(
                "nested", Map.of("apiKey", "secret")
            )))
        );

        assertEquals(400, exception.getCode());
        verify(mapper, never()).insertMemory(any());
    }

    private CreateMemoryRequest createRequest(Map<String, Object> metadata) {
        return new CreateMemoryRequest(
            "response-style", "preference", "Prefer concise engineering answers.",
            "manual", null, 1.0, "internal", null, metadata
        );
    }

    private AgentMemory existingProjectMemory() {
        AgentMemory memory = new AgentMemory();
        memory.setId(1001L);
        memory.setMemoryKey("project-fact");
        memory.setScopeType("project");
        memory.setScopeId(2001L);
        memory.setMemoryType("fact");
        memory.setContent("Old fact");
        memory.setContentHash("a".repeat(64));
        memory.setSourceType("manual");
        memory.setSensitiveLevel("internal");
        memory.setReviewStatus("approved");
        memory.setMetadataJson("{}");
        memory.setRevisionNo(3L);
        memory.setDelFlag("0");
        return memory;
    }
}
