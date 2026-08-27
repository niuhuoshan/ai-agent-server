package group.aitools.nhs.platform.memory;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import group.aitools.nhs.platform.memory.service.PlatformRuntimeMemoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRuntimeMemoryProviderTest {

    private MemoryCatalogMapper mapper;
    private MemoryScopeAuthorizationService authorization;
    private PlatformRuntimeMemoryProvider provider;

    @BeforeEach
    void setUp() {
        mapper = mock(MemoryCatalogMapper.class);
        authorization = mock(MemoryScopeAuthorizationService.class);
        provider = new PlatformRuntimeMemoryProvider(
            new FrozenRuntimePrincipalResolver(), authorization, mapper
        );
    }

    @Test
    void returnsFrozenContentOnlyWhileRevisionAndCurrentAccessRemainValid() {
        AgentMemory current = currentMemory();
        when(mapper.selectById(5001L)).thenReturn(current);
        when(authorization.canView(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("task"),
            org.mockito.ArgumentMatchers.eq(3001L)
        )).thenReturn(true);

        var result = provider.resolve(request("Approved task fact"));

        assertEquals(1, result.size());
        assertEquals("Approved task fact", result.getFirst().content());

        current.setRevisionNo(3L);
        assertTrue(provider.resolve(request("Approved task fact")).isEmpty());
    }

    @Test
    void tamperedFrozenContentFailsClosedBeforePromptInjection() {
        when(mapper.selectById(5001L)).thenReturn(currentMemory());

        assertThrows(
            SecurityException.class,
            () -> provider.resolve(request("Tampered content"))
        );
    }

    private AgentMemory currentMemory() {
        AgentMemory memory = new AgentMemory();
        memory.setId(5001L);
        memory.setScopeType("task");
        memory.setScopeId(3001L);
        memory.setMemoryType("fact");
        memory.setContent("Approved task fact");
        memory.setContentHash(ContentHashing.sha256("Approved task fact"));
        memory.setRevisionNo(2L);
        memory.setReviewStatus("approved");
        memory.setSensitiveLevel("internal");
        memory.setDelFlag("0");
        return memory;
    }

    private AgentRunRequest request(String frozenContent) {
        Map<String, Object> entry = Map.of(
            "id", 5001L,
            "revisionNo", 2L,
            "scopeType", "task",
            "scopeId", 3001L,
            "memoryType", "fact",
            "content", frozenContent,
            "contentHash", ContentHashing.sha256("Approved task fact")
        );
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-1", "trace-1"),
            101L, null, 3001L, 4001L, 4002L, 100L, "agent", "session",
            "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "workspace", 10,
            Map.of("principalId", 101L, "principalType", "human", "roles", List.of("member")),
            Map.of("memorySnapshot", List.of(entry))
        );
    }
}
