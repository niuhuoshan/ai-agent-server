package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.mapper.McpRuntimeMapper;
import group.aitools.nhs.platform.connector.service.McpRuntimeObservationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class McpRuntimeObservationServiceTest {

    @Test
    void returnsPersistedHealthMountsAndUsageToTheGlobalConnectorAdministrator() {
        Fixture fixture = fixture(admin(9L));
        AgentConnector connector = connector("global", null);
        when(fixture.connectors().selectConnectorById(71L)).thenReturn(connector);
        McpRuntimeHealth health = new McpRuntimeHealth();
        health.setConnectorId(71L);
        health.setHealthStatus("degraded");
        health.setCircuitState("half_open");
        health.setConsecutiveFailures(2);
        health.setActiveMountCount(1L);
        when(fixture.runtime().selectHealth(71L)).thenReturn(health);
        McpRuntimeMount mount = new McpRuntimeMount();
        mount.setId(801L);
        mount.setConnectorId(71L);
        mount.setStatus("mounted");
        McpUsageDetail usage = new McpUsageDetail();
        usage.setId(901L);
        usage.setConnectorId(71L);
        usage.setStatus("success");
        when(fixture.runtime().selectMounts(71L, 20)).thenReturn(List.of(mount));
        when(fixture.runtime().selectUsage(71L, 50)).thenReturn(List.of(usage));

        var result = fixture.service().overview(71L, 20, 50);

        assertEquals("degraded", result.health().healthStatus());
        assertEquals("half_open", result.health().circuitState());
        assertEquals(1, result.mounts().size());
        assertEquals(1, result.usage().size());
    }

    @Test
    void hidesAnotherUsersPersonalConnectorEvenFromAnAdministrator() {
        Fixture fixture = fixture(admin(9L));
        when(fixture.connectors().selectConnectorById(71L)).thenReturn(connector("personal", 77L));

        ServiceException failure = assertThrows(
            ServiceException.class, () -> fixture.service().overview(71L, 20, 50)
        );

        assertEquals(404, failure.getCode());
    }

    @Test
    void doesNotExposeEnterpriseWideUserUsageToOrdinaryMembers() {
        Fixture fixture = fixture(member(9L));
        when(fixture.connectors().selectConnectorById(71L)).thenReturn(connector("global", null));

        ServiceException failure = assertThrows(
            ServiceException.class, () -> fixture.service().overview(71L, 20, 50)
        );

        assertEquals(403, failure.getCode());
    }

    private Fixture fixture(CurrentPrincipal principal) {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        ConnectorCatalogMapper connectors = mock(ConnectorCatalogMapper.class);
        McpRuntimeMapper runtime = mock(McpRuntimeMapper.class);
        McpRuntimeObservationService service = new McpRuntimeObservationService(
            principals, mock(AuthorizationEnforcer.class), connectors, runtime
        );
        return new Fixture(service, connectors, runtime);
    }

    private AgentConnector connector(String scope, Long ownerId) {
        AgentConnector connector = new AgentConnector();
        connector.setId(71L);
        connector.setProviderType("mcp");
        connector.setScopeType(scope);
        connector.setOwnerId(ownerId);
        connector.setStatus("active");
        return connector;
    }

    private CurrentPrincipal admin(Long id) {
        return new CurrentPrincipal(id, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN));
    }

    private CurrentPrincipal member(Long id) {
        return new CurrentPrincipal(id, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER));
    }

    private record Fixture(
        McpRuntimeObservationService service,
        ConnectorCatalogMapper connectors,
        McpRuntimeMapper runtime
    ) {
    }
}
