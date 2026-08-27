package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeRetrievalRow;
import group.aitools.nhs.platform.knowledge.service.KnowledgeAuthorizationContextFactory;
import group.aitools.nhs.platform.knowledge.service.KnowledgeEmbeddingClient;
import group.aitools.nhs.platform.knowledge.service.KnowledgeRetrievalService;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.provider.ExternalKnowledgeProvider;
import group.aitools.nhs.platform.provider.ExternalKnowledgeProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class KnowledgeRetrievalServiceTest {

    private KnowledgeCatalogMapper mapper;
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeCatalogMapper.class);
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        service = new KnowledgeRetrievalService(
            () -> principal,
            mock(AuthorizationEnforcer.class),
            mock(KnowledgeAuthorizationContextFactory.class),
            mapper,
            mock(AgentModelMapper.class),
            mock(KnowledgeEmbeddingClient.class),
            JsonMapper.builder().build()
        );
        AgentKnowledgeBase base = new AgentKnowledgeBase();
        base.setId(1001L);
        base.setStatus("active");
        base.setProviderType("postgres_pgvector");
        base.setConfigJson("{\"similarityThreshold\":0.2,\"vectorWeight\":0}");
        when(mapper.selectBaseById(1001L)).thenReturn(base);
    }

    @Test
    void returnsGroundedCitationContextForLexicalMatches() {
        KnowledgeRetrievalRow row = new KnowledgeRetrievalRow();
        row.setChunkId(3001L);
        row.setKnowledgeBaseId(1001L);
        row.setDocumentId(2001L);
        row.setDocumentName("policy.txt");
        row.setChunkNo(1);
        row.setContent("Expense claims require approval.");
        row.setMetadataJson("{\"page\":2}");
        row.setScore(0.8);
        when(mapper.searchLexical(1001L, "expense approval", 18)).thenReturn(List.of(row));

        var result = service.retrieve(
            new group.aitools.nhs.platform.knowledge.web.KnowledgeRetrieveRequest(
                List.of(1001L), "expense approval", 6, null, null
            )
        );

        assertEquals("ok", result.status());
        assertEquals(1, result.citations().size());
        assertTrue(result.content().contains("[ID:1]"));
        assertEquals(2001L, result.citations().getFirst().documentId());
    }

    @Test
    void emptyRecallExplicitlyForbidsUnsupportedAnswer() {
        when(mapper.searchLexical(1001L, "missing policy", 18)).thenReturn(List.of());

        var result = service.retrieve(
            new group.aitools.nhs.platform.knowledge.web.KnowledgeRetrieveRequest(
                List.of(1001L), "missing policy", 6, null, null
            )
        );

        assertEquals("empty", result.status());
        assertTrue(result.content().contains("不要编造"));
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void delegatesSingleExternalKnowledgeBaseToConfiguredProvider() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        AgentKnowledgeBase externalBase = new AgentKnowledgeBase();
        externalBase.setId(2001L);
        externalBase.setStatus("active");
        externalBase.setProviderType("ragflow");
        when(mapper.selectBaseById(2001L)).thenReturn(externalBase);
        ExternalKnowledgeProvider provider = mock(ExternalKnowledgeProvider.class);
        when(provider.providerType()).thenReturn("RAGFlow");
        var expected = new group.aitools.nhs.platform.knowledge.web.KnowledgeRetrievalView(
            "ok", "external grounded result", List.of()
        );
        when(provider.retrieve(principal, externalBase, "external policy", 4)).thenReturn(expected);
        KnowledgeRetrievalService externalService = new KnowledgeRetrievalService(
            () -> principal,
            mock(AuthorizationEnforcer.class),
            mock(KnowledgeAuthorizationContextFactory.class),
            mapper,
            mock(AgentModelMapper.class),
            mock(KnowledgeEmbeddingClient.class),
            JsonMapper.builder().build(),
            new ExternalKnowledgeProviderRegistry(List.of(provider))
        );

        var result = externalService.retrieve(
            new group.aitools.nhs.platform.knowledge.web.KnowledgeRetrieveRequest(
                List.of(2001L), "external policy", 4, null, null
            )
        );

        assertEquals(expected, result);
        verify(provider).retrieve(principal, externalBase, "external policy", 4);
    }
}
