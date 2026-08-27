package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentMcpDiscovery;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ConnectorCatalogService;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.DiscoveryWork;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.PreparedDiscovery;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.McpRemoteException;
import group.aitools.nhs.platform.connector.service.McpRuntimeLifecycleService;
import group.aitools.nhs.platform.connector.service.McpServersImportParser;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConnectorCatalogServiceTest {

    @Test
    void mapsMcpSafetyAnnotationsIntoVersionIdentityAndRiskPolicy() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        ));
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        ConnectorMcpConnectionFactory connections = mock(ConnectorMcpConnectionFactory.class);
        McpRemoteClient remote = mock(McpRemoteClient.class);
        McpDiscoveryPersistenceService persistence = mock(McpDiscoveryPersistenceService.class);
        AgentConnector connector = connector();
        when(mapper.selectConnectorById(7L)).thenReturn(connector);
        AgentMcpDiscovery discovery = new AgentMcpDiscovery();
        discovery.setId(80L);
        discovery.setStatus("running");
        discovery.setStartedBy(9L);
        discovery.setStartedAt(LocalDateTime.now());
        DiscoveryWork work = new DiscoveryWork(connector, discovery);
        when(persistence.begin(7L, 9L)).thenReturn(work);
        McpRemoteClient.Connection connection = new McpRemoteClient.Connection(
            URI.create("https://mcp.example/rpc"), "streamable_http", "none", null, null,
            Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        when(connections.create(connector)).thenReturn(connection);
        when(remote.discover(connection)).thenReturn(new McpRemoteClient.DiscoveryResult(
            "2025-11-25", Map.of("name", "test", "version", "1"), List.of(
                new McpRemoteClient.DiscoveredTool(
                    "read_report", null, "read", Map.of("type", "object"), Map.of(),
                    Map.of("readOnly", true, "destructive", false)
                ),
                new McpRemoteClient.DiscoveredTool(
                    "delete_report", null, "delete", Map.of("type", "object"), Map.of(),
                    Map.of("destructive", true, "idempotent", false)
                )
            )
        ));
        when(persistence.complete(any(), any())).thenReturn(true);
        ConnectorCatalogService service = new ConnectorCatalogService(
            principals, authorization, mock(PlatformIdGenerator.class), mapper,
            new ConnectorConfigurationValidator(jsonMapper), mock(ConnectorEndpointPolicy.class),
            mock(McpServersImportParser.class),
            connections, remote, persistence, mock(McpRuntimeLifecycleService.class), jsonMapper
        );
        ArgumentCaptor<PreparedDiscovery> prepared = ArgumentCaptor.forClass(PreparedDiscovery.class);

        service.discover(7L);

        verify(persistence).complete(any(), prepared.capture());
        assertEquals("R3", prepared.getValue().tools().getFirst().riskLevel());
        assertEquals("delete_report", prepared.getValue().tools().getFirst().externalName());
        assertEquals("R1", prepared.getValue().tools().get(1).riskLevel());
        assertTrue(prepared.getValue().tools().get(1).executionPolicyJson().contains("readOnly"));
    }

    @Test
    void testsMcpConnectionAndPersistsSuccessfulHealth() {
        Fixture fixture = fixture();
        when(fixture.remote().discover(fixture.connection())).thenReturn(
            new McpRemoteClient.DiscoveryResult(
                "2025-11-25", Map.of("title", "Reports MCP"), List.of(
                    new McpRemoteClient.DiscoveredTool(
                        "search", null, null, Map.of(), Map.of(), Map.of()
                    )
                )
            )
        );
        when(fixture.mapper().markConnectorCheckSucceeded(any(), any(), any())).thenReturn(1);

        var result = fixture.service().testConnection(7L);

        assertTrue(result.success());
        assertEquals("2025-11-25", result.protocolVersion());
        assertEquals("Reports MCP", result.serverName());
        assertEquals(1, result.toolCount());
        assertEquals("search", result.tools().getFirst().externalName());
        verify(fixture.mapper()).markConnectorCheckSucceeded(any(), any(), any());
    }

    @Test
    void persistsSanitizedFailureWhenMcpConnectionFails() {
        Fixture fixture = fixture();
        when(fixture.remote().discover(fixture.connection()))
            .thenThrow(new McpRemoteException("connection refused"));

        ServiceException exception = assertThrows(
            ServiceException.class, () -> fixture.service().testConnection(7L)
        );

        assertEquals(502, exception.getCode());
        assertFalse(exception.getMessage().isBlank());
        verify(fixture.mapper()).markConnectorCheckFailed(
            org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq("connection refused"), any()
        );
    }

    private Fixture fixture() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        ));
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        AgentConnector connector = connector();
        when(mapper.selectConnectorById(7L)).thenReturn(connector);
        ConnectorMcpConnectionFactory connections = mock(ConnectorMcpConnectionFactory.class);
        McpRemoteClient.Connection connection = new McpRemoteClient.Connection(
            URI.create("https://mcp.example/rpc"), "streamable_http", "none", null, null,
            Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        when(connections.create(connector)).thenReturn(connection);
        McpRemoteClient remote = mock(McpRemoteClient.class);
        ConnectorCatalogService service = new ConnectorCatalogService(
            principals, mock(AuthorizationEnforcer.class), mock(PlatformIdGenerator.class), mapper,
            new ConnectorConfigurationValidator(jsonMapper), mock(ConnectorEndpointPolicy.class),
            mock(McpServersImportParser.class),
            connections, remote, mock(McpDiscoveryPersistenceService.class),
            mock(McpRuntimeLifecycleService.class), jsonMapper
        );
        return new Fixture(service, mapper, remote, connection);
    }

    private AgentConnector connector() {
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setConnectorKey("reports");
        connector.setProviderType("mcp");
        connector.setScopeType("global");
        connector.setEndpointUrl("https://mcp.example/rpc");
        connector.setConfigJson("""
            {"transport":"streamable_http","authType":"none",\
             "connectTimeoutMs":1000,"requestTimeoutMs":2000}
            """);
        connector.setRevisionNo(2L);
        connector.setStatus("active");
        return connector;
    }

    private record Fixture(
        ConnectorCatalogService service,
        ConnectorCatalogMapper mapper,
        McpRemoteClient remote,
        McpRemoteClient.Connection connection
    ) {
    }
}
