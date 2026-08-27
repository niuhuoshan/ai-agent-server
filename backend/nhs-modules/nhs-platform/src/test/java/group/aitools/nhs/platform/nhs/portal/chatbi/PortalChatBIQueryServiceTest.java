package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.web.ConversationView;
import group.aitools.nhs.platform.conversation.web.CreateConversationRequest;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.web.DataColumnView;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalChatBIQueryServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "analyst", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
    private final DataQueryExecutionService queryExecutionService = mock(DataQueryExecutionService.class);
    private final ConversationApplicationService conversationService = mock(ConversationApplicationService.class);
    private final PortalChatBIConversationStore conversationStore = mock(PortalChatBIConversationStore.class);
    private final PortalChatBIQueryMapper mapper = mock(PortalChatBIQueryMapper.class);
    private final PortalChatBIModelGateway modelGateway = mock(PortalChatBIModelGateway.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private PortalChatBIQueryService service;

    @BeforeEach
    void setUp() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(catalogService.getDataset(10L)).thenReturn(dataset());
        when(catalogService.listDatasets(anyInt())).thenReturn(List.of(dataset()));
        when(catalogService.metadata(10L)).thenReturn(List.of(table()));
        when(mapper.selectDatasetDbType(10L)).thenReturn("postgresql");
        service = new PortalChatBIQueryService(
            principalProvider, catalogService, queryExecutionService, conversationService,
            conversationStore, mapper, modelGateway, jsonMapper
        );
    }

    @Test
    void executesGeneratedSqlThroughGovernedExecutorAndGroundsAnalysisInRows() {
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(21L, """
                {"status":"query","title":"订单总额","sql":"SELECT o.amount FROM biz.orders o",
                 "analysis_intent":"汇总订单金额"}
                """),
            new PortalChatBIModelGateway.Completion(21L, "{\"analysis\":\"当前结果金额为120.50。\"}")
        );
        when(conversationService.create(any(CreateConversationRequest.class))).thenReturn(conversation());
        when(queryExecutionService.executeWithTrace(any(DataQueryRequest.class), anyString()))
            .thenReturn(new DataQueryResultView(
                99L, List.of("amount"), List.of(List.of("120.50")),
                1, 32, false, 8
            ));

        Map<String, Object> response = service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "订单金额是多少")
        );

        assertEquals("succeeded", response.get("status"));
        assertEquals(99L, response.get("query_id"));
        assertEquals("当前结果金额为120.50。", response.get("analysis"));
        ArgumentCaptor<DataQueryRequest> query = ArgumentCaptor.forClass(DataQueryRequest.class);
        verify(queryExecutionService).executeWithTrace(query.capture(), anyString());
        assertEquals(10L, query.getValue().datasetId());
        assertEquals(66L, query.getValue().conversationId());
        assertEquals("SELECT o.amount FROM biz.orders o", query.getValue().sql());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(modelGateway, times(2)).complete(prompts.capture(), anyString());
        assertTrue(prompts.getAllValues().get(0).contains("biz"));
        assertTrue(prompts.getAllValues().get(0).contains("amount"));
        assertFalse(prompts.getAllValues().get(0).contains("secret_token"));
        assertTrue(prompts.getAllValues().get(1).contains("120.50"));
        verify(conversationStore).append(
            eq(MEMBER), eq(66L), anyString(), eq(21L), eq(10L), eq(99L),
            eq("SELECT o.amount FROM biz.orders o"), eq("订单金额是多少"),
            eq("当前结果金额为120.50。")
        );
    }

    @Test
    void malformedModelPlanIsRejectedBeforeConversationOrSqlExecution() {
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(21L, "```json\n{\"status\":\"query\"}\n```")
        );

        ServiceException exception = assertThrows(ServiceException.class, () -> service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "查询订单")
        ));

        assertEquals(502, exception.getCode());
        verify(conversationService, never()).create(any());
        verify(queryExecutionService, never()).executeWithTrace(any(), anyString());
    }

    @Test
    void providerFailureDoesNotReturnSuccessOrCreateEmptyConversation() {
        when(modelGateway.complete(anyString(), anyString()))
            .thenThrow(new ServiceException("ChatBI 模型服务不可用", 503));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "查询订单")
        ));

        assertEquals(503, exception.getCode());
        verify(conversationService, never()).create(any());
        verify(queryExecutionService, never()).executeWithTrace(any(), anyString());
    }

    @Test
    void clarificationIsARealPrivateConversationTurnWithoutSqlExecution() {
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(21L, """
                {"status":"clarify","title":"订单范围","clarification":"请说明需要查询的日期范围。"}
                """)
        );
        when(conversationService.create(any())).thenReturn(conversation());

        Map<String, Object> response = service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "查询订单")
        );

        assertEquals("clarify", response.get("status"));
        assertEquals("请说明需要查询的日期范围。", response.get("clarification"));
        verify(queryExecutionService, never()).executeWithTrace(any(), anyString());
        verify(conversationStore).append(
            eq(MEMBER), eq(66L), anyString(), eq(21L), eq(10L), eq(null),
            eq(null), eq("查询订单"), eq("请说明需要查询的日期范围。")
        );
    }

    @Test
    void generatedWriteSqlIsRejectedByGovernedExecutorAndNeverReportedAsSuccess() {
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(21L, """
                {"status":"query","title":"错误计划","sql":"DELETE FROM biz.orders",
                 "analysis_intent":"删除订单"}
                """)
        );
        when(conversationService.create(any())).thenReturn(conversation());
        when(queryExecutionService.executeWithTrace(any(), anyString()))
            .thenThrow(new ServiceException("一期只允许 SELECT 查询", 400));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "删除全部订单")
        ));

        assertEquals(400, exception.getCode());
        verify(queryExecutionService).executeWithTrace(any(), anyString());
        verify(conversationStore).append(
            eq(MEMBER), eq(66L), anyString(), eq(21L), eq(10L), eq(null),
            eq("DELETE FROM biz.orders"), eq("删除全部订单"),
            eq("查询未能执行：一期只允许 SELECT 查询")
        );
        verify(modelGateway, times(1)).complete(anyString(), anyString());
    }

    @Test
    void malformedAnalysisAfterRealQueryIsRecordedAndReturnedAsFailure() {
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(21L, """
                {"status":"query","title":"订单总额","sql":"SELECT o.amount FROM biz.orders o",
                 "analysis_intent":"汇总订单金额"}
                """),
            new PortalChatBIModelGateway.Completion(21L, "not-json")
        );
        when(conversationService.create(any())).thenReturn(conversation());
        when(queryExecutionService.executeWithTrace(any(), anyString())).thenReturn(
            new DataQueryResultView(
                99L, List.of("amount"), List.of(List.of("120.50")), 1, 32, false, 8
            )
        );

        ServiceException exception = assertThrows(ServiceException.class, () -> service.query(
            new PortalChatBIQueryService.QueryRequest(10L, null, "订单金额是多少")
        ));

        assertEquals(502, exception.getCode());
        verify(conversationStore).append(
            eq(MEMBER), eq(66L), anyString(), eq(21L), eq(10L), eq(99L),
            eq("SELECT o.amount FROM biz.orders o"), eq("订单金额是多少"),
            eq("查询已完成，但业务分析生成失败：模型分析响应不是严格 JSON")
        );
    }

    @Test
    void historyAndDetailAlwaysUseCurrentOwnerId() {
        AgentDataQuery query = queryFact();
        when(mapper.selectOwnedQueries(7L, 30)).thenReturn(List.of(query));
        when(mapper.selectOwnedAssistantAnalysis(99L, 7L)).thenReturn("真实分析");
        when(mapper.selectOwnedConversationTitle(99L, 7L)).thenReturn("订单总额");
        when(mapper.selectOwnedQuery(99L, 7L)).thenReturn(query);
        DataQueryStoredResultRow stored = new DataQueryStoredResultRow();
        stored.setQueryId(99L);
        stored.setColumnsJson("[\"amount\"]");
        stored.setRowsJson("[[120.5]]");
        when(mapper.selectOwnedResult(99L, 7L)).thenReturn(stored);

        List<Map<String, Object>> history = service.history(30);
        Map<String, Object> detail = service.detail(99L);

        assertEquals(1, history.size());
        assertEquals("真实分析", history.get(0).get("analysis"));
        assertEquals("订单数据", history.get(0).get("dataset_name"));
        assertEquals(List.of("amount"), detail.get("columns"));
        assertEquals(List.of(List.of(120.5)), detail.get("rows"));
        verify(mapper).selectOwnedQueries(7L, 30);
        verify(mapper).selectOwnedQuery(99L, 7L);
        verify(mapper).selectOwnedResult(99L, 7L);
        verify(queryExecutionService, times(2)).requireInteractiveQueryAccess(10L);
        verify(mapper, never()).selectOwnedQuery(anyLong(), eq(8L));
    }

    @Test
    void revokedDatasetPermissionHidesPreviouslyStoredHistory() {
        AgentDataQuery query = queryFact();
        when(mapper.selectOwnedQueries(7L, 30)).thenReturn(List.of(query));
        doThrow(new ServiceException("数据集查询权限不足", 403))
            .when(queryExecutionService).requireInteractiveQueryAccess(10L);

        List<Map<String, Object>> history = service.history(30);

        assertTrue(history.isEmpty());
        verify(mapper, never()).selectOwnedAssistantAnalysis(anyLong(), anyLong());
        verify(mapper, never()).selectOwnedResult(anyLong(), anyLong());
    }

    private DatasetView dataset() {
        return new DatasetView(
            10L, 2L, "orders", "订单数据", "订单事实数据", "active",
            List.of("biz"), 1, null, null, 7L, LocalDateTime.now(), null
        );
    }

    private DataTableView table() {
        return new DataTableView(
            31L, "orders", "biz", "orders", "订单", "订单明细", "TABLE",
            "active", true, List.of(
                new DataColumnView(
                    41L, "amount", "amount", "金额", "numeric", "订单金额",
                    false, false, "active", true
                ),
                new DataColumnView(
                    42L, "secret", "secret_token", "密钥", "text", "内部密钥",
                    false, true, "active", true
                )
            )
        );
    }

    private ConversationView conversation() {
        return new ConversationView(
            66L, null, null, null, null, "订单总额", "private", "active",
            null, LocalDateTime.now()
        );
    }

    private AgentDataQuery queryFact() {
        AgentDataQuery query = new AgentDataQuery();
        query.setId(99L);
        query.setConversationId(66L);
        query.setTraceId("chatbi:trace");
        query.setDatasetId(10L);
        query.setUserQuery("订单金额是多少");
        query.setSqlText("SELECT o.amount FROM biz.orders o");
        query.setStatus("succeeded");
        query.setRowCount(1L);
        query.setResultBytes(32L);
        query.setResultTruncated(false);
        query.setStartedAt(LocalDateTime.now().minusSeconds(1));
        query.setFinishedAt(LocalDateTime.now());
        query.setCreatedAt(LocalDateTime.now());
        query.setCreatedBy(7L);
        return query;
    }
}
