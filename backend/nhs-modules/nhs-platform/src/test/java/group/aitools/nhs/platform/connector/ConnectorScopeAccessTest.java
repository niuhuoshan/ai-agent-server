package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ConnectorCatalogService;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.McpRuntimeLifecycleService;
import group.aitools.nhs.platform.connector.service.McpServersImportParser;
import group.aitools.nhs.platform.connector.web.CreateConnectorRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConnectorScopeAccessTest {

    @Test
    void listOnlyRequestsGlobalAndCurrentOwnersPersonalConnectors() {
        Fixture fixture = fixture(member(19L));

        fixture.service().list(null, null, true, "personal", 100);

        verify(fixture.mapper()).selectConnectors(null, null, true, "personal", 19L, 100);
    }

    @Test
    void anotherUsersPersonalConnectorIsHiddenEvenFromPlatformAdmin() {
        Fixture fixture = fixture(admin(9L));
        AgentConnector connector = connector("personal", 77L);
        when(fixture.mapper().selectConnectorById(7L)).thenReturn(connector);

        ServiceException exception = assertThrows(ServiceException.class, () -> fixture.service().get(7L));

        assertEquals(404, exception.getCode());
    }

    @Test
    void memberCannotCreateOrOperateGlobalConnector() {
        Fixture fixture = fixture(member(19L));
        ServiceException createFailure = assertThrows(ServiceException.class, () -> fixture.service().create(
            new CreateConnectorRequest(
                "reports", "Reports", "mcp", "global", "https://mcp.example/rpc", null,
                Map.of("transport", "streamable_http", "authType", "none"), "active"
            )
        ));
        assertEquals(403, createFailure.getCode());

        when(fixture.mapper().selectConnectorById(7L)).thenReturn(connector("global", null));
        ServiceException operateFailure = assertThrows(
            ServiceException.class, () -> fixture.service().testConnection(7L)
        );
        assertEquals(403, operateFailure.getCode());
    }

    @Test
    void personalConnectorPersistsCurrentHumanAsOwner() {
        Fixture fixture = fixture(member(19L));
        when(fixture.ids().nextId()).thenReturn(701L);
        when(fixture.endpointPolicy().normalize("https://mcp.example/rpc"))
            .thenReturn(URI.create("https://mcp.example/rpc"));
        when(fixture.mapper().insertConnector(any())).thenReturn(1);

        var result = fixture.service().create(new CreateConnectorRequest(
            "my-reports", "My Reports", "mcp", "personal", "https://mcp.example/rpc", null,
            Map.of("transport", "streamable_http", "authType", "none"), "active"
        ));

        assertEquals("personal", result.scope());
        assertEquals(19L, result.ownerId());
        assertTrue(result.ownedByCurrentUser());
        assertTrue(result.manageable());
    }

    private Fixture fixture(CurrentPrincipal principal) {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        ConnectorEndpointPolicy endpointPolicy = mock(ConnectorEndpointPolicy.class);
        ConnectorConfigurationValidator validator = new ConnectorConfigurationValidator(jsonMapper);
        ConnectorCatalogService service = new ConnectorCatalogService(
            principals, mock(AuthorizationEnforcer.class), ids, mapper, validator, endpointPolicy,
            mock(McpServersImportParser.class), mock(ConnectorMcpConnectionFactory.class),
            mock(McpRemoteClient.class), mock(McpDiscoveryPersistenceService.class),
            mock(McpRuntimeLifecycleService.class), jsonMapper
        );
        return new Fixture(service, mapper, ids, endpointPolicy);
    }

    private AgentConnector connector(String scope, Long ownerId) {
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setConnectorKey("reports");
        connector.setName("Reports");
        connector.setProviderType("mcp");
        connector.setScopeType(scope);
        connector.setOwnerId(ownerId);
        connector.setEndpointUrl("https://mcp.example/rpc");
        connector.setConfigJson("{}");
        connector.setStatus("active");
        connector.setRevisionNo(1L);
        return connector;
    }

    private CurrentPrincipal member(Long id) {
        return new CurrentPrincipal(id, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER));
    }

    private CurrentPrincipal admin(Long id) {
        return new CurrentPrincipal(id, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN));
    }

    private record Fixture(
        ConnectorCatalogService service,
        ConnectorCatalogMapper mapper,
        PlatformIdGenerator ids,
        ConnectorEndpointPolicy endpointPolicy
    ) {
    }
}
