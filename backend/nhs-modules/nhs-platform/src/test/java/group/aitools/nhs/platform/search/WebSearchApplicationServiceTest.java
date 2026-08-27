package group.aitools.nhs.platform.search;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.search.domain.SearchProviderState;
import group.aitools.nhs.platform.search.mapper.SearchProviderMapper;
import group.aitools.nhs.platform.search.service.SearchRuntimePersistenceService;
import group.aitools.nhs.platform.search.service.WebSearchApplicationService;
import group.aitools.nhs.platform.search.service.WebSearchClient;
import group.aitools.nhs.platform.search.web.SearchProviderView;
import group.aitools.nhs.platform.search.web.WebSearchRequest;
import group.aitools.nhs.platform.search.web.WebSearchResultView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class WebSearchApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principals;
    private SearchProviderMapper mapper;
    private WebSearchClient client;
    private SearchRuntimePersistenceService persistence;
    private WebSearchApplicationService service;

    @BeforeEach
    void setUp() {
        principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(ADMIN);
        mapper = mock(SearchProviderMapper.class);
        client = mock(WebSearchClient.class);
        persistence = mock(SearchRuntimePersistenceService.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        service = new WebSearchApplicationService(
            principals, mock(AuthorizationEnforcer.class), mapper,
            new ConnectorConfigurationValidator(jsonMapper), jsonMapper, client, persistence
        );
    }

    @Test
    void exposesCredentialFreePersistentHealth() {
        AgentConnector connector = connector();
        SearchProviderState state = new SearchProviderState();
        state.setCircuitState("open");
        state.setConsecutiveFailures(3);
        state.setTotalRequests(12L);
        state.setTotalFailures(4L);
        state.setLastLatencyMs(250);
        state.setNextProbeAt(LocalDateTime.now().plusMinutes(1));
        when(mapper.selectVisibleActiveProviders(9L)).thenReturn(List.of(connector));
        when(persistence.state(7L)).thenReturn(state);

        List<SearchProviderView> result = service.providers();

        assertEquals(1, result.size());
        assertEquals("open", result.getFirst().circuitState());
        assertEquals("brave", result.getFirst().engine());
        assertEquals(12L, result.getFirst().totalRequests());
    }

    @Test
    void previewCallsRealClientAndPersistsContentFreeSuccess() {
        AgentConnector connector = connector();
        when(mapper.selectConnector(7L)).thenReturn(connector);
        when(persistence.recentInvocations(eq(7L), any())).thenReturn(0);
        when(client.search(eq(connector), any(), eq("agent platform"), eq(4))).thenReturn(List.of(
            new WebSearchClient.SearchHit(
                1, "Docs", "https://example.com/docs", "Snippet", null, "example.com"
            )
        ));

        WebSearchResultView result = service.preview(new WebSearchRequest(7L, "agent platform", 4));

        assertEquals(1, result.resultCount());
        verify(persistence).success(eq(7L), eq(2L), any(Integer.class), any(), any());
    }

    @Test
    void openCircuitRejectsWithoutNetworkCallAndAuditsDecision() {
        AgentConnector connector = connector();
        when(mapper.selectConnector(7L)).thenReturn(connector);
        SearchProviderState state = new SearchProviderState();
        state.setCircuitState("open");
        state.setNextProbeAt(LocalDateTime.now().plusMinutes(1));
        when(persistence.state(7L)).thenReturn(state);

        ServiceException error = assertThrows(
            ServiceException.class,
            () -> service.preview(new WebSearchRequest(7L, "agent platform", 4))
        );

        assertEquals(503, error.getCode());
        verify(client, never()).search(any(), any(), any(), any(Integer.class));
        verify(persistence).rejected(eq(7L), eq("circuit_open"), eq("search_circuit_open"), any(), any());
    }

    @Test
    void runtimeWithoutVisibleProviderIsExplicitlyUnavailable() {
        when(mapper.selectVisibleActiveProviders(9L)).thenReturn(List.of());

        ServiceException error = assertThrows(
            ServiceException.class,
            () -> service.runtimeSearch(ADMIN, "web_search_bing_http", "query", 3, "1", "trace")
        );

        assertEquals(503, error.getCode());
        assertEquals(true, error.getMessage().startsWith("search_unavailable:"));
    }

    private AgentConnector connector() {
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setConnectorKey("enterprise-search");
        connector.setName("Enterprise Search");
        connector.setProviderType("search");
        connector.setScopeType("global");
        connector.setEndpointUrl("https://api.search.brave.com/res/v1/web/search");
        connector.setCredentialRef("env:BRAVE_SEARCH_API_KEY");
        connector.setConfigJson("""
            {"authType":"header","authHeader":"X-Subscription-Token",
             "connectTimeoutMs":1000,"requestTimeoutMs":3000,"engine":"brave",
             "requestMethod":"GET","queryParam":"q","countParam":"count",
             "maxResults":10,"rateLimitPerMinute":60,"failureThreshold":3,
             "cooldownSeconds":60}
            """);
        connector.setStatus("active");
        connector.setRevisionNo(2L);
        connector.setDelFlag("0");
        return connector;
    }
}
