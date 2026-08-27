package group.aitools.nhs.platform.nhs.portal.example;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.ReadOnlySqlValidator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalExampleServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AgentChatBIExampleMapper mapper;
    private DataCatalogMapper catalogMapper;
    private ReadOnlySqlValidator sqlValidator;
    private PlatformIdGenerator idGenerator;
    private PortalChatBIModelGateway modelGateway;
    private AgentChatBIExampleRevisionMapper revisionMapper;
    private PortalExampleAuditService auditService;
    private PortalExampleService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        mapper = mock(AgentChatBIExampleMapper.class);
        catalogMapper = mock(DataCatalogMapper.class);
        sqlValidator = mock(ReadOnlySqlValidator.class);
        idGenerator = mock(PlatformIdGenerator.class);
        modelGateway = mock(PortalChatBIModelGateway.class);
        revisionMapper = mock(AgentChatBIExampleRevisionMapper.class);
        auditService = mock(PortalExampleAuditService.class);
        service = new PortalExampleService(
            principalProvider, mapper, catalogMapper, sqlValidator, JsonMapper.builder().build(), idGenerator,
            modelGateway, revisionMapper, auditService
        );
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
    }

    @Test
    void listOnlyUsesTheCurrentUsersRowsForMembers() {
        when(mapper.countVisible(7L, false, null, null, null, null, null, null)).thenReturn(1L);
        when(mapper.selectPage(7L, false, null, null, null, null, null, null, 20, 0))
            .thenReturn(List.of(example(11L, 7L, "pending")));

        PortalExampleService.PageResult result = service.list(null);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0)).containsEntry("id", 11L);
        verify(mapper).selectPage(7L, false, null, null, null, null, null, null, 20, 0);
    }

    @Test
    void memberCannotReadAnotherUsersExample() {
        when(mapper.selectPage(7L, false, 11L, null, null, null, null, null, 1, 0))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.get(11L))
            .hasMessageContaining("没有访问权限");
    }

    @Test
    void reviewerSyncMarksOnlyValidatedReadOnlyRowsAsSynced() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        AgentChatBIExample row = example(11L, 7L, "approved");
        AgentChatBIExample synced = example(11L, 7L, "approved");
        synced.setLocalSyncStatus("synced");
        when(mapper.selectPage(1L, true, 11L, null, null, null, null, null, 1, 0))
            .thenReturn(List.of(row), List.of(synced));
        when(catalogMapper.selectTables(3L)).thenReturn(List.of());
        when(catalogMapper.selectColumns(3L)).thenReturn(List.of());
        when(sqlValidator.validate(eq("SELECT a.value FROM public.amounts a"), anyList(), anyList()))
            .thenReturn(new ReadOnlySqlValidator.ValidatedSql(
                "SELECT a.value FROM public.amounts a", "hash", List.of("public.amounts"), List.of("value")
            ));
        when(mapper.updateLocalSync(eq(11L), eq("synced"), eq(null), any(), any())).thenReturn(1);

        Map<String, Object> result = service.sync(11L);

        assertThat(result).containsEntry("local_sync_status", "synced");
        verify(mapper).updateLocalSync(eq(11L), eq("synced"), eq(null), any(), any());
    }

    @Test
    void enhancementPersistsValidatedModelResultAndResetsLocalIndex() {
        AgentChatBIExample original = example(11L, 7L, "pending");
        original.setAiAnswer("金额总计为 100 元");
        AgentChatBIExample enhanced = example(11L, 7L, "pending");
        enhanced.setRefinedQuery("查询当前数据集中金额总计");
        enhanced.setContextSummary("用户正在分析金额汇总。");
        enhanced.setEnhanceStatus("succeeded");
        when(mapper.selectPage(7L, false, 11L, null, null, null, null, null, 1, 0))
            .thenReturn(List.of(original), List.of(enhanced));
        when(mapper.claimEnhancement(eq(11L), eq(7L), eq(false), any())).thenReturn(1);
        when(modelGateway.complete(anyString(), anyString())).thenReturn(
            new PortalChatBIModelGateway.Completion(31L, """
                {"refined_query":"查询当前数据集中金额总计","context_summary":"用户正在分析金额汇总。",\
                "sql_metadata":{"tables":["public.amounts"],"query_type":"aggregation","dimensions":[]}}
                """)
        );
        when(mapper.completeEnhancement(
            eq(11L), eq("查询当前数据集中金额总计"), eq("用户正在分析金额汇总。"), anyString(), any()
        )).thenReturn(1);

        Map<String, Object> result = service.enhance(11L);

        assertThat(result).containsEntry("enhance_status", "succeeded")
            .containsEntry("refined_query", "查询当前数据集中金额总计");
        verify(mapper).completeEnhancement(
            eq(11L), eq("查询当前数据集中金额总计"), eq("用户正在分析金额汇总。"),
            org.mockito.ArgumentMatchers.contains("\"enhancement_model_id\":31"), any()
        );
    }

    @Test
    void enhancementFailureIsPersistedWithoutMaskingProviderError() {
        AgentChatBIExample original = example(11L, 7L, "pending");
        when(mapper.selectPage(7L, false, 11L, null, null, null, null, null, 1, 0))
            .thenReturn(List.of(original));
        when(mapper.claimEnhancement(eq(11L), eq(7L), eq(false), any())).thenReturn(1);
        when(modelGateway.complete(anyString(), anyString()))
            .thenThrow(new ServiceException("模型服务不可用", 503));

        assertThatThrownBy(() -> service.enhance(11L))
            .hasMessageContaining("模型服务不可用");
        verify(mapper).failEnhancement(eq(11L), eq("模型服务不可用"), any());
    }

    @Test
    void syncingARejectedSignalDoesNotValidateItAsARuntimeSqlExample() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        AgentChatBIExample row = example(11L, 7L, "deprecated");
        row.setFeedbackType("down");
        AgentChatBIExample synced = example(11L, 7L, "deprecated");
        synced.setFeedbackType("down");
        synced.setLocalSyncStatus("synced");
        when(mapper.selectPage(1L, true, 11L, null, null, null, null, null, 1, 0))
            .thenReturn(List.of(row), List.of(synced));
        when(mapper.updateLocalSync(eq(11L), eq("synced"), eq(null), any(), any())).thenReturn(1);

        Map<String, Object> result = service.sync(11L);

        assertThat(result).containsEntry("local_sync_status", "synced");
        verify(sqlValidator, org.mockito.Mockito.never()).validate(anyString(), anyList(), anyList());
    }

    @Test
    void feedbackCandidateUsesOnlyPersistedQueryAndAssistantFacts() {
        ConversationMessageRow assistant = assistantMessage("server-trace");
        ConversationMessageRow question = userMessage("界面提交的问题");
        AgentDataQuery query = new AgentDataQuery();
        query.setId(501L);
        query.setTraceId("server-trace");
        query.setDataSourceId(21L);
        query.setDatasetId(3L);
        query.setDataSourceRevision(4);
        query.setDatasetRevision(6);
        query.setUserQuery("持久化的数据查询问题");
        query.setSqlText("SELECT a.value FROM public.amounts a");
        query.setSqlHash("sql-hash");
        query.setRowCount(2L);
        query.setResultBytes(128L);
        query.setResultTruncated(false);
        query.setFinishedAt(LocalDateTime.of(2026, 8, 17, 9, 30));
        when(catalogMapper.selectLatestSucceededQueryByTrace("server-trace", 7L)).thenReturn(query);
        when(idGenerator.nextId()).thenReturn(81L);
        when(mapper.upsertFeedbackCandidate(any())).thenReturn(1);

        service.record(MEMBER, assistant, question, "down");

        ArgumentCaptor<AgentChatBIExample> captor = ArgumentCaptor.forClass(AgentChatBIExample.class);
        verify(mapper).upsertFeedbackCandidate(captor.capture());
        AgentChatBIExample candidate = captor.getValue();
        assertThat(candidate.getId()).isEqualTo(81L);
        assertThat(candidate.getTraceId()).isEqualTo("server-trace");
        assertThat(candidate.getAgentId()).isEqualTo("5");
        assertThat(candidate.getDatasetId()).isEqualTo(3L);
        assertThat(candidate.getUserQuery()).isEqualTo("持久化的数据查询问题");
        assertThat(candidate.getSqlText()).isEqualTo("SELECT a.value FROM public.amounts a");
        assertThat(candidate.getAiAnswer()).isEqualTo("服务端保存的助手回答");
        assertThat(candidate.getFeedbackType()).isEqualTo("down");
        assertThat(candidate.getReviewStatus()).isEqualTo("pending");
        assertThat(candidate.getLocalSyncStatus()).isEqualTo("pending");
        assertThat(candidate.getSqlMetadataJson())
            .contains("\"source\":\"conversation_feedback\"")
            .contains("\"data_query_id\":501")
            .contains("\"sql_hash\":\"sql-hash\"");
    }

    @Test
    void repeatedGeneralFeedbackUsesTraceUpsertAndResetsReviewCandidate() {
        ConversationMessageRow assistant = assistantMessage("general-trace");
        ConversationMessageRow question = userMessage("请总结这段对话");
        when(idGenerator.nextId()).thenReturn(82L, 83L);
        when(mapper.upsertFeedbackCandidate(any())).thenReturn(1);

        service.record(MEMBER, assistant, question, "up");
        service.record(MEMBER, assistant, question, "down");

        ArgumentCaptor<AgentChatBIExample> captor = ArgumentCaptor.forClass(AgentChatBIExample.class);
        verify(mapper, times(2)).upsertFeedbackCandidate(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentChatBIExample::getTraceId)
            .containsOnly("general-trace");
        AgentChatBIExample updated = captor.getAllValues().get(1);
        assertThat(updated.getDatasetId()).isNull();
        assertThat(updated.getSqlText()).isEmpty();
        assertThat(updated.getCategory()).isEqualTo("general");
        assertThat(updated.getUserQuery()).isEqualTo("请总结这段对话");
        assertThat(updated.getFeedbackType()).isEqualTo("down");
        assertThat(updated.getReviewStatus()).isEqualTo("pending");
    }

    private ConversationMessageRow assistantMessage(String traceId) {
        ConversationMessageRow row = new ConversationMessageRow();
        row.setId(9L);
        row.setConversationId(44L);
        row.setSequenceNo(2);
        row.setTraceId(traceId);
        row.setRole("assistant");
        row.setContent("服务端保存的助手回答");
        row.setAgentId(5L);
        return row;
    }

    private ConversationMessageRow userMessage(String content) {
        ConversationMessageRow row = new ConversationMessageRow();
        row.setId(8L);
        row.setConversationId(44L);
        row.setSequenceNo(1);
        row.setRole("user");
        row.setContent(content);
        return row;
    }

    private AgentChatBIExample example(Long id, Long owner, String reviewStatus) {
        AgentChatBIExample row = new AgentChatBIExample();
        row.setId(id);
        row.setCreatedBy(owner);
        row.setTraceId("trace-" + id);
        row.setDatasetId(3L);
        row.setUserQuery("查询金额");
        row.setSqlText("SELECT a.value FROM public.amounts a");
        row.setSqlMetadataJson("{}");
        row.setCategory("data_query");
        row.setReviewStatus(reviewStatus);
        row.setLocalSyncStatus("pending");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
