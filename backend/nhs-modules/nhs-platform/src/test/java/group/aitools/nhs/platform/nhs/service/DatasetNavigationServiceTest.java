package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.web.DataColumnView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.platform.nhs.persistence.mapper.DatasetNavigationMapper;
import group.aitools.nhs.platform.nhs.persistence.row.DatasetNavigationCacheRow;
import group.aitools.nhs.platform.nhs.persistence.row.DatasetNavigationClickRow;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.ColumnInfo;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.NavigationResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshRequest;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.TableRecommendRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DatasetNavigationServiceTest {

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
    private final DatasetNavigationMapper mapper = mock(DatasetNavigationMapper.class);
    private final AgentModelMapper modelMapper = mock(AgentModelMapper.class);
    private final ModelEndpointPolicy endpointPolicy = mock(ModelEndpointPolicy.class);
    private final ModelCredentialResolver credentialResolver = mock(ModelCredentialResolver.class);
    private final HttpModelProviderClient modelClient = mock(HttpModelProviderClient.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private DatasetNavigationService service;

    @BeforeEach
    void setUp() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "analyst", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        when(catalogService.listDatasets(anyInt())).thenReturn(List.of(dataset()));
        when(catalogService.metadata(10L)).thenReturn(List.of(table()));
        service = new DatasetNavigationService(
            principalProvider, catalogService, mapper, modelMapper, endpointPolicy,
            credentialResolver, modelClient, jsonMapper
        );
    }

    @Test
    void buildsUsefulFallbackFromOnlyAuthorizedServerMetadata() {
        NavigationResponse response = service.navigation(false);

        assertEquals(1, response.datasetCount());
        assertTrue(response.hasDatasets());
        assertTrue(response.isFallback());
        assertTrue(response.llmGenerationFailed());
        assertEquals(64, response.datasetMenuHash().length());
        assertEquals("经营分析", response.groups().get(0).get("title"));
        assertTrue(response.markdown().contains("订单明细"));
        verify(mapper).upsertCache(
            eq(7L), eq(response.datasetMenuHash()), anyString(), any(), any()
        );
    }

    @Test
    void validStrictJsonEnhancesKnownGroupsAndUnknownIdsCannotAddData() {
        configureModel("""
            {"groups":[
              {"id":"dataset_10","summary":"订单经营场景", "tags":["经营","订单"],
               "questions":[{"label":"客群趋势","query":"分析不同客户的订单趋势"}],
               "followups":[{"label":"继续下钻","query":"按客户下钻订单变化"}]},
              {"id":"dataset_999","summary":"越权场景","questions":[{"label":"越权","query":"查询秘密表"}]}
            ]}
            """);

        NavigationResponse response = service.navigation(true);

        assertFalse(response.isFallback());
        assertFalse(response.llmGenerationFailed());
        assertEquals(1, response.groups().size());
        assertEquals("订单经营场景", response.groups().get(0).get("summary"));
        assertFalse(response.markdown().contains("秘密表"));
    }

    @Test
    void cachedPayloadReappliesCurrentClickRanking() {
        NavigationResponse initial = service.navigation(false);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsertCache(
            eq(7L), eq(initial.datasetMenuHash()), payload.capture(), any(), any()
        );
        DatasetNavigationCacheRow cache = new DatasetNavigationCacheRow();
        cache.setUserId(7L);
        cache.setMenuHash(initial.datasetMenuHash());
        cache.setPayloadJson(payload.getValue());
        cache.setGeneratedAt(LocalDateTime.now());
        cache.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(mapper.selectCache(eq(7L), eq(initial.datasetMenuHash()), any())).thenReturn(cache);

        DatasetNavigationClickRow click = new DatasetNavigationClickRow();
        click.setQueryText("分析订单明细最近一个月的变化趋势");
        click.setClickCount(9L);
        click.setLastClickedAt(LocalDateTime.of(2026, 8, 16, 10, 0));
        when(mapper.selectClicks(eq(7L), any(), anyInt())).thenReturn(List.of(click));

        NavigationResponse cached = service.navigation(false);

        assertTrue(cached.fromCache());
        List<Map<String, Object>> questions = maps(cached.groups().get(0).get("questions"));
        assertEquals("趋势分析", questions.get(0).get("label"));
        assertEquals(9L, ((Number) questions.get(0).get("click_count")).longValue());
    }

    @Test
    void tableRecommendationIgnoresCallerSuppliedColumnsAndUsesAuthorizedMetadata() {
        RefreshResponse response = service.recommend(new TableRecommendRequest(
            "订单明细", "attacker_table", "经营分析",
            List.of(new ColumnInfo("secret", "伪造字段", "text", "不可信"))
        ));

        assertEquals(3, response.questions().size());
        assertTrue(response.questions().stream().anyMatch(value -> value.query().contains("客户")));
        assertTrue(response.questions().stream().noneMatch(value -> value.query().contains("伪造字段")));
        verify(mapper, never()).upsertRecentQuestion(
            any(), anyString(), anyString(), anyString(), eq("伪造字段"), any(), any()
        );
    }

    @Test
    void unauthorizedTableIsNotAcceptedForRefreshOrRecommendation() {
        RefreshResponse refresh = service.refresh(new RefreshRequest(
            "秘密场景", List.of("秘密表"), null, null, List.of(), "questions"
        ));

        assertTrue(refresh.questions().isEmpty());
        assertEquals("暂无更多不同问题，稍后再试", refresh.refreshDisabledReason());
        ServiceException failure = assertThrows(ServiceException.class, () -> service.recommend(
            new TableRecommendRequest("秘密表", null, null, List.of())
        ));
        assertEquals(403, failure.getCode());
    }

    @Test
    void refreshNamespacesRecentQuestionsByDatasetMenuHash() {
        String menuHash = "a".repeat(64);

        service.refresh(new RefreshRequest(
            "经营分析", List.of("订单明细"), menuHash, "orders", List.of(), "questions"
        ));

        ArgumentCaptor<String> groupHash = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectRecentQuestions(
            eq(7L), eq("questions"), groupHash.capture(), any(), anyInt()
        );
        assertEquals(ContentHashing.sha256("orders\u0000menu:" + menuHash), groupHash.getValue());
    }

    @Test
    void invalidModelEnvelopeFallsBackInsteadOfTreatingMarkdownAsJson() {
        configureModel("```json\n{\"groups\":[]}\n```");

        NavigationResponse response = service.navigation(true);

        assertTrue(response.isFallback());
        assertTrue(response.llmGenerationFailed());
        assertTrue(response.markdown().contains("经营分析"));
    }

    private void configureModel(String completion) {
        AgentModel model = new AgentModel();
        model.setModelType("chat");
        model.setModelName("portal-model");
        model.setProviderType("openai-compatible");
        model.setEndpointUrl("https://models.example/v1");
        model.setCredentialRef("env:MODEL_KEY");
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(anyString(), anyString()))
            .thenReturn(URI.create("https://models.example/v1"));
        when(credentialResolver.resolve("env:MODEL_KEY")).thenReturn("secret");
        when(modelClient.complete(eq(model), any(), eq("secret"), anyString(), anyString()))
            .thenReturn(completion);
    }

    private DatasetView dataset() {
        return new DatasetView(
            10L, 20L, "business", "经营分析", "订单经营数据", "active",
            List.of("public"), 3, null, null, 7L,
            LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 16, 10, 0)
        );
    }

    private DataTableView table() {
        return new DataTableView(
            100L, "orders", "public", "biz_orders", "订单明细", "订单事实表",
            "table", "active", true, List.of(new DataColumnView(
                1000L, "customer_name", "customer_name", "客户", "varchar",
                "客户名称", false, false, "active", true
            ))
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
