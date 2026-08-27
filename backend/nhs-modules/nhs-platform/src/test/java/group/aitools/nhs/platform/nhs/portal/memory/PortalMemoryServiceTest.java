package group.aitools.nhs.platform.nhs.portal.memory;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryApplicationService;
import group.aitools.nhs.platform.memory.web.CreateMemoryRequest;
import group.aitools.nhs.platform.memory.web.MemoryView;
import group.aitools.nhs.platform.memory.web.UpdateMemoryRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalMemoryServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private MemoryApplicationService memoryService;
    private MemoryCatalogMapper memoryMapper;
    private AgentConversationMapper conversationMapper;
    private PortalMemoryOperationsAuditService operationsAuditService;
    private PortalMemoryService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        memoryService = mock(MemoryApplicationService.class);
        memoryMapper = mock(MemoryCatalogMapper.class);
        conversationMapper = mock(AgentConversationMapper.class);
        operationsAuditService = mock(PortalMemoryOperationsAuditService.class);
        service = new PortalMemoryService(
            principalProvider, memoryService, memoryMapper, conversationMapper, operationsAuditService
        );
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
    }

    @Test
    void adminSummaryHistoryIsCheckedAgainstTheTargetUser() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        MemoryView summary = memory(
            11L, "summary-900", "summary", "目标用户摘要", 900L,
            Map.of("conversation_id", "900"), LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        when(memoryService.list("user", 42L, null, 20)).thenReturn(List.of(summary));
        AgentConversation conversation = new AgentConversation();
        conversation.setId(900L);
        conversation.setUserId(42L);
        when(conversationMapper.selectOwnedConversation(900L, 42L)).thenReturn(conversation);
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(99L);
        message.setRole("assistant");
        message.setContent("历史回答");
        when(conversationMapper.selectMessages(900L, 0, 1)).thenReturn(List.of(message));

        List<Map<String, Object>> result = service.summariesForUser(42L, null, 20);

        assertThat(result).singleElement().satisfies(value ->
            assertThat(value).containsEntry("has_history", true)
        );
        verify(conversationMapper).selectOwnedConversation(900L, 42L);
        verify(conversationMapper, never()).selectOwnedConversation(900L, 1L);
    }

    @Test
    void clearingMemoryReportsSessionAndDailyCountsSeparately() {
        MemoryView session = memory(
            11L, "summary-900", "summary", "会话摘要", 900L,
            Map.of("conversation_id", "900"), LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        MemoryView daily = memory(
            12L, "daily-summary-2026-08-17", "summary", "每日摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 10, 0)
        );
        MemoryView ltm = memory(
            13L, "ltm-language", "preference", "中文", null,
            Map.of("kind", "ltm", "key", "language"), LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        when(memoryService.list("user", 7L, null, 500)).thenReturn(List.of(session, daily, ltm));

        Map<String, Object> result = service.clearSessionMemoryForUser(7L);

        assertThat(result)
            .containsEntry("session_summaries_deleted", 1)
            .containsEntry("daily_summaries_deleted", 1)
            .containsEntry("history_deleted", 0);
        verify(memoryService).delete(11L, 1L);
        verify(memoryService).delete(12L, 1L);
        verify(memoryService, never()).delete(13L, 1L);
    }

    @Test
    void dailyListPrefersThePersistedDailySummary() {
        MemoryView session = memory(
            11L, "summary-900", "summary", "实时拼接内容", 900L,
            Map.of("conversation_id", "900"), LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        MemoryView daily = memory(
            12L, "daily-summary-2026-08-17", "summary", "已保存的每日摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 10, 0)
        );
        when(memoryService.list("user", 7L, null, 500)).thenReturn(List.of(session, daily));

        List<Map<String, Object>> result = service.dailySummariesForUser(7L, null, null, null, 20);

        assertThat(result).singleElement().satisfies(value ->
            assertThat(value)
                .containsEntry("summary", "已保存的每日摘要")
                .containsEntry("stored", true)
                .containsEntry("session_count", 1)
                .containsEntry("id", 12L)
        );
    }

    @Test
    void deletingDailySummaryDoesNotDeleteSessionSummariesForThatDay() {
        MemoryView session = memory(
            11L, "summary-900", "summary", "会话摘要", 900L,
            Map.of("conversation_id", "900"), LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        MemoryView daily = memory(
            12L, "daily-summary-2026-08-17", "summary", "每日摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 10, 0)
        );
        when(memoryService.list("user", 7L, null, 500)).thenReturn(List.of(session, daily));

        service.deleteDailyForUser(7L, "2026-08-17");

        verify(memoryService).delete(12L, 1L);
        verify(memoryService, never()).delete(11L, 1L);
    }

    @Test
    void rebuildingDailySummaryUpdatesTheDurableRow() {
        MemoryView session = memory(
            11L, "summary-900", "summary", "会话摘要", 900L,
            Map.of("conversation_id", "900"), LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        MemoryView daily = memory(
            12L, "daily-summary-2026-08-17", "summary", "旧摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 10, 0)
        );
        MemoryView rebuilt = memory(
            12L, "daily-summary-2026-08-17", "summary", "- 会话摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        when(memoryService.list("user", 7L, null, 500)).thenReturn(List.of(session, daily));
        when(memoryService.update(any(), any())).thenReturn(rebuilt);

        Map<String, Object> result = service.rebuildDailyForUser(7L, "2026-08-17");

        ArgumentCaptor<UpdateMemoryRequest> captor = ArgumentCaptor.forClass(UpdateMemoryRequest.class);
        verify(memoryService).update(org.mockito.ArgumentMatchers.eq(12L), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("- 会话摘要");
        assertThat(result)
            .containsEntry("summary", "- 会话摘要")
            .containsEntry("stored", true);
    }

    @Test
    void finalizingConversationCreatesSessionAndDailyMemoryRowsTransactionally() {
        MemoryView session = memory(
            21L, "conversation-summary-900", "summary", "会话摘要", 900L,
            Map.of("kind", "session_summary", "conversation_id", "900", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        MemoryView daily = memory(
            22L, "daily-summary-2026-08-17", "summary", "- 会话摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        when(memoryService.list("user", 7L, null, 500))
            .thenReturn(List.of(), List.of(session));
        when(memoryService.create(eq("user"), eq(7L), any(CreateMemoryRequest.class)))
            .thenReturn(session, daily);

        var result = service.finalizeConversationSummary(
            7L, 900L, "会话摘要", java.time.LocalDate.of(2026, 8, 17)
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.dailySummaryRefreshed()).isTrue();
        verify(memoryService, org.mockito.Mockito.times(2)).create(
            eq("user"), eq(7L), any(CreateMemoryRequest.class)
        );
    }

    @Test
    void administratorSearchReturnsOnlyTheAdministratorsOwnerScopedMemoryRows() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        MemoryView ltm = memoryForOwner(
            1L,
            13L, "ltm-language", "preference", "默认使用中文回复", null,
            Map.of("kind", "ltm", "key", "language"), LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        when(memoryService.list("user", 1L, "中文", 10)).thenReturn(List.of(ltm));

        List<Map<String, Object>> result = service.search("中文", 10);

        assertThat(result).singleElement().satisfies(value ->
            assertThat(value)
                .containsEntry("id", 13L)
                .containsEntry("memory_type", "preference")
                .containsEntry("content", "默认使用中文回复")
        );
        verify(memoryService).list("user", 1L, "中文", 10);
        verify(operationsAuditService).record(
            eq(ADMIN), eq("memory.search_test"), eq(1L), eq("success"), anyString(),
            eq("ownerId=1, limit=10, resultCount=1")
        );
    }

    @Test
    void administratorConfigurationUpdateIsDurableAndAudited() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        MemoryView stored = memoryForOwner(
            1L, 15L, "portal-memory-config", "preference", "Portal memory configuration", null,
            Map.of("kind", "memory_config", "default_search_limit", 25),
            LocalDateTime.of(2026, 8, 17, 11, 0)
        );
        when(memoryService.list("user", 1L, null, 500))
            .thenReturn(List.of(), List.of(stored));
        when(memoryService.create(any(), any(), any())).thenReturn(stored);

        Map<String, Object> result = service.updateConfig(25);

        assertThat(result)
            .containsEntry("default_search_limit", 25)
            .containsEntry("stored", true);
        ArgumentCaptor<CreateMemoryRequest> request = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).create(eq("user"), eq(1L), request.capture());
        assertThat(request.getValue().metadata())
            .containsEntry("kind", "memory_config")
            .containsEntry("default_search_limit", 25);
        verify(operationsAuditService).record(
            ADMIN, "memory.config_update", 1L, "success", "memory configuration updated",
            "ownerId=1, defaultSearchLimit=25"
        );
    }

    @Test
    void ordinaryMemberCannotExecuteAdministratorMemoryOperations() {
        assertForbidden(service::config);
        assertForbidden(() -> service.updateConfig(25));
        assertForbidden(() -> service.search("私有事实", 10));
        assertForbidden(service::indexStatus);
        assertForbidden(service::verifyIndex);
        assertForbidden(service::testVectorStore);
        assertForbidden(service::testEmbedding);

        verifyNoInteractions(memoryService, memoryMapper, conversationMapper);
        verify(operationsAuditService).record(
            MEMBER, "memory.config_view", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.config_update", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.search_test", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.index_status", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.index_verify", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.redis_vector_test", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
        verify(operationsAuditService).record(
            MEMBER, "memory.embedding_test", 7L, "deny", "platform administrator role required", "ownerId=7"
        );
    }

    @Test
    void unavailableVectorProvidersRemain503AndAreNeverAuditedAsSuccess() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);

        ServiceException redisFailure = catchThrowableOfType(
            ServiceException.class, service::testVectorStore
        );
        ServiceException embeddingFailure = catchThrowableOfType(
            ServiceException.class, service::testEmbedding
        );

        assertThat(redisFailure.getCode()).isEqualTo(503);
        assertThat(embeddingFailure.getCode()).isEqualTo(503);
        verify(operationsAuditService).record(
            ADMIN, "memory.redis_vector_test", 1L, "failure", "redis vector provider unavailable",
            "ownerId=1, configured=false"
        );
        verify(operationsAuditService).record(
            ADMIN, "memory.embedding_test", 1L, "failure", "embedding provider unavailable",
            "ownerId=1, configured=false"
        );
        verify(operationsAuditService, never()).record(
            any(CurrentPrincipal.class), anyString(), anyLong(), eq("success"), anyString(), anyString()
        );
    }

    @Test
    void indexVerificationUsesRealPostgresCatalogFactsAndFailsClosed() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(memoryMapper.hasGeneratedSearchVector()).thenReturn(true);
        when(memoryMapper.hasValidLexicalIndex()).thenReturn(false);
        when(memoryMapper.countSearchDocuments("user", 1L)).thenReturn(3L);

        ServiceException failure = catchThrowableOfType(ServiceException.class, service::verifyIndex);

        assertThat(failure.getCode()).isEqualTo(503);
        verify(operationsAuditService).record(
            ADMIN, "memory.index_verify", 1L, "failure", "service_error(code=503)", "ownerId=1"
        );
        verify(operationsAuditService, never()).record(
            eq(ADMIN), eq("memory.index_verify"), eq(1L), eq("success"), anyString(), anyString()
        );
    }

    @Test
    void healthyGeneratedIndexIsVerifiedForTheAdministratorsOwnerScope() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(memoryMapper.hasGeneratedSearchVector()).thenReturn(true);
        when(memoryMapper.hasValidLexicalIndex()).thenReturn(true);
        when(memoryMapper.countSearchDocuments("user", 1L)).thenReturn(4L);

        Map<String, Object> result = service.verifyIndex();

        assertThat(result)
            .containsEntry("available", true)
            .containsEntry("verified", true)
            .containsEntry("rebuilt", false)
            .containsEntry("document_count", 4L)
            .containsEntry("search_vector_present", true)
            .containsEntry("lexical_index_present", true);
        verify(memoryMapper).countSearchDocuments("user", 1L);
        verify(operationsAuditService).record(
            ADMIN, "memory.index_verify", 1L, "success", "memory index verified",
            "ownerId=1, provider=postgresql_tsvector, rebuilt=false"
        );
    }

    @Test
    void relationalConsolidationCreatesDurableDailySummaries() {
        MemoryView session = memory(
            11L, "summary-900", "summary", "会话摘要", 900L,
            Map.of("conversation_id", "900", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 9, 0)
        );
        MemoryView daily = memory(
            12L, "daily-summary-2026-08-17", "summary", "- 会话摘要", null,
            Map.of("kind", "daily_summary", "date", "2026-08-17"),
            LocalDateTime.of(2026, 8, 17, 10, 0)
        );
        when(memoryService.list("user", 7L, null, 500)).thenReturn(List.of(session));
        when(memoryService.create(any(), any(), any())).thenReturn(daily);

        Map<String, Object> result = service.consolidate();

        assertThat(result)
            .containsEntry("mode", "postgresql_relational")
            .containsEntry("days_processed", 1)
            .containsEntry("daily_summaries_created", 1)
            .containsEntry("intelligent_rewrite", false);
        ArgumentCaptor<CreateMemoryRequest> request = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).create(org.mockito.ArgumentMatchers.eq("user"),
            org.mockito.ArgumentMatchers.eq(7L), request.capture());
        assertThat(request.getValue().content()).isEqualTo("- 会话摘要");
    }

    private MemoryView memory(
        Long id,
        String key,
        String type,
        String content,
        Long sourceId,
        Map<String, Object> metadata,
        LocalDateTime updatedAt
    ) {
        return memoryForOwner(7L, id, key, type, content, sourceId, metadata, updatedAt);
    }

    private MemoryView memoryForOwner(
        Long ownerId,
        Long id,
        String key,
        String type,
        String content,
        Long sourceId,
        Map<String, Object> metadata,
        LocalDateTime updatedAt
    ) {
        return new MemoryView(
            id, key, "user", ownerId, type, content, "manual", sourceId,
            1.0, "internal", "approved", null, metadata, 1L,
            null, null, null, ownerId, updatedAt.minusHours(1), updatedAt
        );
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        ServiceException failure = catchThrowableOfType(ServiceException.class, operation);
        assertThat(failure.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
