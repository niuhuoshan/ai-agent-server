package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class FrozenRuntimePrincipalResolverTest {

    private final FrozenRuntimePrincipalResolver resolver = new FrozenRuntimePrincipalResolver();

    @Test
    void serviceAccountCannotEscalateByInjectingHumanRoles() {
        AgentRunRequest request = request(42L, Map.of(
            "principalId", 42L,
            "principalType", "service_account",
            "roles", List.of("platform_admin", "member", "service_account")
        ));

        var principal = resolver.resolve(request);

        assertEquals(PrincipalType.SERVICE_ACCOUNT, principal.type());
        assertEquals(java.util.Set.of(PlatformRole.SERVICE_ACCOUNT), principal.roles());
    }

    @Test
    void requestUserMustMatchFrozenPrincipal() {
        AgentRunRequest request = request(42L, Map.of(
            "principalId", 43L,
            "principalType", "human",
            "roles", List.of("member")
        ));

        assertThrows(SecurityException.class, () -> resolver.resolve(request));
    }

    private AgentRunRequest request(Long userId, Map<String, Object> authorization) {
        return new AgentRunRequest(
            new RuntimeExecutionKey("principal-test", "trace-principal"),
            userId, 10L, null, null, null, 20L,
            "agent", "session", "input", "system",
            new RuntimeModelConfig("agentscope_java", "provider-managed", null, "db:model:20", Map.of()),
            null, 10, authorization, Map.of()
        );
    }
}
