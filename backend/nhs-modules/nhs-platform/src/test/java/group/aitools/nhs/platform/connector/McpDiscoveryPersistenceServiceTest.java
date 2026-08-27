package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentMcpDiscovery;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.DiscoveryWork;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.PreparedDiscovery;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.PreparedRemoteTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class McpDiscoveryPersistenceServiceTest {

    @Test
    void recoversCrashedRunningDiscoveryBeforeStartingAnother() {
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setProviderType("mcp");
        connector.setStatus("active");
        connector.setRevisionNo(3L);
        when(mapper.selectConnectorById(7L)).thenReturn(connector);
        when(ids.nextId()).thenReturn(80L);
        McpDiscoveryPersistenceService service = new McpDiscoveryPersistenceService(mapper, ids);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);

        service.begin(7L, 9L);

        verify(mapper).failStaleDiscoveries(
            org.mockito.ArgumentMatchers.eq(7L), cutoff.capture(), now.capture()
        );
        assertEquals(60, ChronoUnit.MINUTES.between(cutoff.getValue(), now.getValue()));
        InOrder order = inOrder(mapper);
        order.verify(mapper).lockConnector(7L);
        order.verify(mapper).failStaleDiscoveries(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        order.verify(mapper).insertDiscovery(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newlyDiscoveredMcpToolRequiresExplicitPublication() {
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(500L);
        DiscoveryWork work = work();
        when(mapper.selectConnectorById(7L)).thenReturn(work.connector());
        when(mapper.selectLatestConnectorTools(7L)).thenReturn(List.of());
        when(mapper.completeDiscovery(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.markDiscoverySucceeded(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(1);
        McpDiscoveryPersistenceService service = new McpDiscoveryPersistenceService(mapper, ids);
        ArgumentCaptor<AgentTool> inserted = ArgumentCaptor.forClass(AgentTool.class);

        service.complete(work, prepared("new-hash"));

        verify(mapper).insertTool(inserted.capture());
        assertEquals("disabled", inserted.getValue().getStatus());
    }

    @Test
    void changedMcpSchemaPreservesPublishedState() {
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(501L);
        DiscoveryWork work = work();
        AgentTool existing = new AgentTool();
        existing.setId(400L);
        existing.setToolKey("mcp.search");
        existing.setExternalName("search");
        existing.setRemoteSchemaHash("old-hash");
        existing.setStatus("active");
        existing.setIsAvailable(true);
        existing.setVersionNo(1);
        when(mapper.selectConnectorById(7L)).thenReturn(work.connector());
        when(mapper.selectLatestRemoteTool(7L, "search")).thenReturn(existing);
        when(mapper.selectNextToolVersion("mcp.search")).thenReturn(2);
        when(mapper.selectLatestConnectorTools(7L)).thenReturn(List.of(existing));
        when(mapper.completeDiscovery(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.markDiscoverySucceeded(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(1);
        McpDiscoveryPersistenceService service = new McpDiscoveryPersistenceService(mapper, ids);
        ArgumentCaptor<AgentTool> inserted = ArgumentCaptor.forClass(AgentTool.class);

        service.complete(work, prepared("new-hash"));

        verify(mapper).deprecateRemoteTool(
            org.mockito.ArgumentMatchers.eq(400L), org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.any()
        );
        verify(mapper).insertTool(inserted.capture());
        assertEquals("active", inserted.getValue().getStatus());
        assertEquals(2, inserted.getValue().getVersionNo());
    }

    @Test
    void reappearedToolOnSameConnectorRevisionKeepsItsStableResourceId() {
        ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        DiscoveryWork work = work();
        AgentTool existing = new AgentTool();
        existing.setId(400L);
        existing.setToolKey("mcp.search");
        existing.setExternalName("search");
        existing.setRemoteSchemaHash("same-hash");
        existing.setStatus("deprecated");
        existing.setIsAvailable(false);
        existing.setVersionNo(1);
        when(mapper.selectConnectorById(7L)).thenReturn(work.connector());
        when(mapper.selectLatestRemoteTool(7L, "search")).thenReturn(existing);
        when(mapper.recoverRemoteTool(
            org.mockito.ArgumentMatchers.eq(400L), org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq("same-hash"), org.mockito.ArgumentMatchers.eq(80L),
            org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any()
        )).thenReturn(1);
        when(mapper.selectLatestConnectorTools(7L)).thenReturn(List.of(existing));
        when(mapper.completeDiscovery(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.markDiscoverySucceeded(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(1);
        McpDiscoveryPersistenceService service = new McpDiscoveryPersistenceService(
            mapper, mock(PlatformIdGenerator.class)
        );

        service.complete(work, prepared("same-hash"));

        verify(mapper, never()).insertTool(org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).deprecateRemoteTool(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private DiscoveryWork work() {
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setProviderType("mcp");
        connector.setStatus("active");
        connector.setRevisionNo(3L);
        AgentMcpDiscovery discovery = new AgentMcpDiscovery();
        discovery.setId(80L);
        discovery.setConnectorId(7L);
        discovery.setConnectorRevision(3L);
        discovery.setStatus("running");
        discovery.setStartedBy(9L);
        discovery.setStartedAt(LocalDateTime.now());
        return new DiscoveryWork(connector, discovery);
    }

    private PreparedDiscovery prepared(String hash) {
        return new PreparedDiscovery(
            "2025-11-25", "{}", "content-hash", List.of(new PreparedRemoteTool(
                "mcp.search", "search", "Search", "Search reports", "R1",
                "{\"type\":\"object\"}", "{}", hash
            ))
        );
    }
}
