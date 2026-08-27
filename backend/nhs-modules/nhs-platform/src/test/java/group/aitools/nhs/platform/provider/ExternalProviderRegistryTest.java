package group.aitools.nhs.platform.provider;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class ExternalProviderRegistryTest {

    @Test
    void emptyRegistriesAreValidAndFailOnlyWhenMissingAdapterIsInvoked() {
        ExternalKnowledgeProviderRegistry knowledge = new ExternalKnowledgeProviderRegistry(List.of());
        ExternalAgentProviderRegistry agent = new ExternalAgentProviderRegistry(List.of());

        assertFalse(knowledge.available("ragflow"));
        assertFalse(agent.available("remote_agent"));
        assertEquals(503, assertThrows(ServiceException.class, () -> knowledge.require("ragflow")).getCode());
        assertEquals(503, assertThrows(ServiceException.class, () -> agent.require("remote_agent")).getCode());
    }

    @Test
    void providerTypesAreNormalizedAndResolveConfiguredAdapters() {
        ExternalKnowledgeProvider knowledgeProvider = knowledgeProvider(" RAGFlow ");
        ExternalAgentProvider agentProvider = agentProvider(" Remote Agent ");
        ExternalKnowledgeProviderRegistry knowledge = new ExternalKnowledgeProviderRegistry(
            List.of(knowledgeProvider)
        );
        ExternalAgentProviderRegistry agent = new ExternalAgentProviderRegistry(List.of(agentProvider));

        assertSame(knowledgeProvider, knowledge.require("ragflow"));
        assertSame(agentProvider, agent.require("REMOTE AGENT"));
    }

    @Test
    void duplicateAndReservedProviderTypesAreRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new ExternalKnowledgeProviderRegistry(
            List.of(knowledgeProvider("ragflow"), knowledgeProvider("RAGFLOW"))
        ));
        assertThrows(IllegalStateException.class, () -> new ExternalAgentProviderRegistry(
            List.of(agentProvider("remote_agent"), agentProvider("REMOTE_AGENT"))
        ));
        assertThrows(IllegalStateException.class, () -> new ExternalKnowledgeProviderRegistry(
            List.of(knowledgeProvider("postgres_pgvector"))
        ));
        assertThrows(IllegalStateException.class, () -> new ExternalAgentProviderRegistry(
            List.of(agentProvider("agentscope_java"))
        ));
    }

    @Test
    void blankProviderTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalKnowledgeProviderRegistry(
            List.of(knowledgeProvider(" "))
        ));
        assertThrows(IllegalArgumentException.class, () -> new ExternalAgentProviderRegistry(
            List.of(agentProvider(" "))
        ));
    }

    private ExternalKnowledgeProvider knowledgeProvider(String type) {
        ExternalKnowledgeProvider provider = mock(ExternalKnowledgeProvider.class);
        when(provider.providerType()).thenReturn(type);
        return provider;
    }

    private ExternalAgentProvider agentProvider(String type) {
        ExternalAgentProvider provider = mock(ExternalAgentProvider.class);
        when(provider.providerType()).thenReturn(type);
        return provider;
    }
}
