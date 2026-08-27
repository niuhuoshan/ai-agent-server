package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeMetricsMapper;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeMetricsService;
import group.aitools.nhs.platform.knowledge.web.KnowledgeBaseView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeMetricsServiceTest {

    @Test
    void returnsPermissionFilteredEmptyMetricsForMember() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        KnowledgeApplicationService knowledge = mock(KnowledgeApplicationService.class);
        KnowledgeMetricsMapper mapper = mock(KnowledgeMetricsMapper.class);
        when(knowledge.list(null, true, 500)).thenReturn(List.of());
        KnowledgeMetricsService service = new KnowledgeMetricsService(() -> principal, knowledge, mapper);

        var result = service.summary(7, null, null);

        assertEquals("empty", result.status());
        assertEquals("self", result.summary().get("scope"));
        assertEquals(0, result.summary().get("accessible_knowledge_bases"));
        assertEquals(7, result.dailyTrend().size());
    }

    @Test
    void fillsCorpusAndTrendFromDurableFacts() {
        CurrentPrincipal principal = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        KnowledgeApplicationService knowledge = mock(KnowledgeApplicationService.class);
        KnowledgeMetricsMapper mapper = mock(KnowledgeMetricsMapper.class);
        KnowledgeBaseView base = new KnowledgeBaseView(
            10L, "sales", "销售数据", null, "postgres_pgvector", "enterprise_shared",
            "active", Map.of(), 1L, 1L, null, null
        );
        when(knowledge.list(null, true, 500)).thenReturn(List.of(base));
        when(mapper.selectActiveUserIds()).thenReturn(List.of(1L, 2L));
        when(mapper.selectSummary(any(), any(), anyList())).thenReturn(Map.of(
            "retrieval_count", 4L, "citation_count", 8L, "empty_count", 1L,
            "failed_count", 0L, "average_latency_ms", 12D
        ));
        when(mapper.selectDocumentStats(anyList())).thenReturn(Map.of("document_count", 3L, "chunk_count", 9L));
        when(mapper.selectDailyTrend(any(), any(), anyList())).thenReturn(List.of());
        when(mapper.selectBaseStats(any(), any(), anyList(), anyList())).thenReturn(List.of(Map.of("id", 10L)));
        KnowledgeMetricsService service = new KnowledgeMetricsService(() -> principal, knowledge, mapper);

        var result = service.summary(1, null, null);

        assertEquals("ok", result.status());
        assertEquals("enterprise", result.summary().get("scope"));
        assertEquals(50D, result.summary().get("citation_rate"));
        assertEquals(1, result.knowledgeBases().size());
        assertEquals(1, result.dailyTrend().size());
    }
}
