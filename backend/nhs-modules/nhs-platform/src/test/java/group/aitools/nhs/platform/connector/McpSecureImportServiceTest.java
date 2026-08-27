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
import group.aitools.nhs.platform.connector.web.McpServersImportRequest;
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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class McpSecureImportServiceTest {

    @Test
    void replacesInlineSecretWithEnvironmentReferenceBeforePersistence() {
        Fixture fixture = fixture();
        Map<String, Object> document = inlineSecretDocument();

        fixture.service().importMcpServer(new McpServersImportRequest(
            document, "reports", "mcp-reports", "Reports", "personal",
            "env:MCP_REPORTS_TOKEN", "active"
        ));

        ArgumentCaptor<AgentConnector> persisted = ArgumentCaptor.forClass(AgentConnector.class);
        verify(fixture.mapper()).insertConnector(persisted.capture());
        assertEquals("env:MCP_REPORTS_TOKEN", persisted.getValue().getCredentialRef());
        assertEquals("personal", persisted.getValue().getScopeType());
        assertEquals(19L, persisted.getValue().getOwnerId());
        assertFalse(persisted.getValue().getConfigJson().contains("inline-secret-value"));
    }

    @Test
    void refusesInlineSecretWithoutEnvironmentReference() {
        Fixture fixture = fixture();

        ServiceException exception = assertThrows(ServiceException.class, () ->
            fixture.service().importMcpServer(new McpServersImportRequest(
                inlineSecretDocument(), "reports", "mcp-reports", "Reports", "personal",
                null, "active"
            ))
        );

        assertEquals(400, exception.getCode());
        verify(fixture.mapper(), never()).insertConnector(any());
    }

    private Fixture fixture() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        ConnectorConfigurationValidator validator = new ConnectorConfigurationValidator(jsonMapper);
        ConnectorEndpointPolicy endpointPolicy = new ConnectorEndpointPolicy(false, false);
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            19L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(701L);
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        when(mapper.insertConnector(any())).thenReturn(1);
        ConnectorCatalogService service = new ConnectorCatalogService(
            principals, mock(AuthorizationEnforcer.class), ids, mapper, validator, endpointPolicy,
            new McpServersImportParser(validator, endpointPolicy),
            mock(ConnectorMcpConnectionFactory.class), mock(McpRemoteClient.class),
            mock(McpDiscoveryPersistenceService.class), mock(McpRuntimeLifecycleService.class), jsonMapper
        );
        return new Fixture(service, mapper);
    }

    private Map<String, Object> inlineSecretDocument() {
        return Map.of("mcpServers", Map.of("reports", Map.of(
            "url", "https://mcp.example/rpc",
            "headers", Map.of("Authorization", "Bearer inline-secret-value")
        )));
    }

    private record Fixture(ConnectorCatalogService service, ConnectorCatalogMapper mapper) {
    }
}
