package group.aitools.nhs.platform.connector;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.McpRemoteException;
import group.aitools.nhs.platform.connector.service.McpRuntimeLifecycleService;
import group.aitools.nhs.platform.connector.service.McpRuntimePersistenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class McpRuntimeLifecycleServiceTest {

    @Test
    void reusesSessionMountAcrossCallsAndRetainsItUntilSessionCleanup() {
        Fixture fixture = fixture(3);
        McpRemoteClient.McpSession session = mock(McpRemoteClient.McpSession.class);
        when(fixture.remote().open(any())).thenReturn(session);
        when(session.invoke(anyString(), any())).thenReturn(success());
        AgentRunRequest request = request(null, "conversation-session");

        fixture.service().prepare(request, fixture.connector());
        fixture.service().invoke(request, fixture.connector(), 501L, "search", Map.of("q", "one"), "{\"q\":\"one\"}");
        fixture.service().invoke(request, fixture.connector(), 501L, "search", Map.of("q", "two"), "{\"q\":\"two\"}");
        fixture.service().end(request);

        verify(fixture.remote(), times(1)).open(any());
        verify(session, times(2)).invoke(anyString(), any());
        verify(session, never()).close();
        verify(fixture.persistence()).idle(eq(1001L), any(LocalDateTime.class));

        fixture.service().closeAll();
        verify(session).close();
    }

    @Test
    void closesFormalRunMountAtAgentScopeTerminalBoundary() {
        Fixture fixture = fixture(3);
        McpRemoteClient.McpSession session = mock(McpRemoteClient.McpSession.class);
        when(fixture.remote().open(any())).thenReturn(session);
        when(session.invoke(anyString(), any())).thenReturn(success());
        AgentRunRequest request = request(77L, "run-session");

        fixture.service().prepare(request, fixture.connector());
        fixture.service().invoke(request, fixture.connector(), 501L, "search", Map.of(), "{}");
        fixture.service().end(request);

        verify(session).close();
        verify(fixture.persistence()).close(
            org.mockito.ArgumentMatchers.eq(1001L),
            org.mockito.ArgumentMatchers.eq("closed"),
            any(LocalDateTime.class),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void reconnectsOnceAndRecordsTheRealAttemptCount() {
        Fixture fixture = fixture(3);
        McpRemoteClient.McpSession disconnected = mock(McpRemoteClient.McpSession.class);
        McpRemoteClient.McpSession reconnected = mock(McpRemoteClient.McpSession.class);
        when(fixture.remote().open(any())).thenReturn(disconnected, reconnected);
        when(disconnected.invoke(anyString(), any())).thenThrow(new McpRemoteException("connection reset"));
        when(reconnected.invoke(anyString(), any())).thenReturn(success());
        AgentRunRequest request = request(77L, "run-session");

        fixture.service().prepare(request, fixture.connector());
        fixture.service().invoke(request, fixture.connector(), 501L, "search", Map.of(), "{}");

        verify(fixture.remote(), times(2)).open(any());
        ArgumentCaptor<McpUsageDetail> usage = ArgumentCaptor.forClass(McpUsageDetail.class);
        verify(fixture.persistence()).usage(usage.capture());
        assertEquals("success", usage.getValue().getStatus());
        assertEquals(2, usage.getValue().getAttemptCount());
    }

    @Test
    void opensCircuitAndRejectsSubsequentCallsWithoutNetworkTraffic() {
        Fixture fixture = fixture(1);
        McpRemoteClient.McpSession first = mock(McpRemoteClient.McpSession.class);
        McpRemoteClient.McpSession second = mock(McpRemoteClient.McpSession.class);
        when(fixture.remote().open(any())).thenReturn(first, second);
        when(first.invoke(anyString(), any())).thenThrow(new McpRemoteException("connection reset"));
        when(second.invoke(anyString(), any())).thenThrow(new McpRemoteException("connection refused"));
        AgentRunRequest request = request(77L, "run-session");
        fixture.service().prepare(request, fixture.connector());

        assertThrows(McpRemoteException.class, () -> fixture.service().invoke(
            request, fixture.connector(), 501L, "search", Map.of(), "{}"
        ));
        assertThrows(McpRemoteException.class, () -> fixture.service().invoke(
            request, fixture.connector(), 501L, "search", Map.of(), "{}"
        ));

        verify(fixture.remote(), times(2)).open(any());
        ArgumentCaptor<McpUsageDetail> usage = ArgumentCaptor.forClass(McpUsageDetail.class);
        verify(fixture.persistence(), times(2)).usage(usage.capture());
        assertEquals(List.of("transport_error", "circuit_open"), usage.getAllValues().stream()
            .map(McpUsageDetail::getStatus).toList());
        assertEquals(0, usage.getAllValues().get(1).getAttemptCount());
        verify(fixture.persistence(), atLeastOnce()).saveHealth(any());
    }

    @Test
    void connectorInvalidationClosesRetainedMountAndClearsCircuitHealth() {
        Fixture fixture = fixture(3);
        McpRemoteClient.McpSession session = mock(McpRemoteClient.McpSession.class);
        when(fixture.remote().open(any())).thenReturn(session);
        AgentRunRequest request = request(null, "conversation-session");
        fixture.service().prepare(request, fixture.connector());

        fixture.service().invalidateConnector(71L, "configuration changed");

        verify(session).close();
        verify(fixture.persistence()).close(
            eq(1001L), eq("closed"), any(LocalDateTime.class), eq("configuration changed")
        );
        verify(fixture.persistence()).resetHealth(eq(71L), any(LocalDateTime.class));
    }

    private Fixture fixture(int threshold) {
        McpRuntimePersistenceService persistence = mock(McpRuntimePersistenceService.class);
        ConnectorMcpConnectionFactory connections = mock(ConnectorMcpConnectionFactory.class);
        McpRemoteClient remote = mock(McpRemoteClient.class);
        AgentConnector connector = connector();
        AtomicLong mountIds = new AtomicLong(1000L);
        when(persistence.createMount(any(), any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            String scope = invocation.getArgument(2);
            McpRuntimeMount mount = new McpRuntimeMount();
            mount.setId(mountIds.incrementAndGet());
            mount.setConnectorId(connector.getId());
            mount.setConnectorRevision(connector.getRevisionNo());
            mount.setScopeType(scope);
            mount.setStatus("mounting");
            mount.setConnectionAttempts(0);
            mount.setSessionId(request.sessionId());
            return mount;
        });
        when(connections.create(connector)).thenReturn(new McpRemoteClient.Connection(
            URI.create("https://mcp.example/rpc"), "streamable_http", "none", null, null,
            Duration.ofSeconds(1), Duration.ofSeconds(2)
        ));
        McpRuntimeLifecycleService service = new McpRuntimeLifecycleService(
            persistence, connections, remote, JsonMapper.builder().build(), 300_000, 30_000, threshold
        );
        return new Fixture(service, persistence, remote, connector);
    }

    private AgentConnector connector() {
        AgentConnector connector = new AgentConnector();
        connector.setId(71L);
        connector.setRevisionNo(2L);
        connector.setProviderType("mcp");
        connector.setStatus("active");
        return connector;
    }

    private AgentRunRequest request(Long runId, String sessionId) {
        return new AgentRunRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"), 9L,
            runId == null ? 44L : null, runId == null ? null : 66L, runId,
            runId == null ? null : 88L, 100L, "agent", sessionId, "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            null, 10, Map.of(), Map.of()
        );
    }

    private McpRemoteClient.InvocationResult success() {
        return new McpRemoteClient.InvocationResult(false, List.of(Map.of("type", "text", "text", "ok")), null, Map.of());
    }

    private record Fixture(
        McpRuntimeLifecycleService service,
        McpRuntimePersistenceService persistence,
        McpRemoteClient remote,
        AgentConnector connector
    ) {
    }
}
