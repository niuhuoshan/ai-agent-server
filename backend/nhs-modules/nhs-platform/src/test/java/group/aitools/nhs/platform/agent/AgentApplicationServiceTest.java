package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentVersionResourceMapper;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.service.AgentConfigurationValidator;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.agent.web.AgentResourceBindingRequest;
import group.aitools.nhs.platform.agent.web.AgentOnboardingRequest;
import group.aitools.nhs.platform.agent.web.AgentOnboardingResult;
import group.aitools.nhs.platform.agent.web.AgentReorderItemRequest;
import group.aitools.nhs.platform.agent.web.AgentVersionPublishResult;
import group.aitools.nhs.platform.agent.web.AgentVersionView;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.agent.web.CreateAgentRequest;
import group.aitools.nhs.platform.agent.web.SaveAgentVersionRequest;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.core.type.TypeReference;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentApplicationServiceTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private AgentDefinitionMapper definitionMapper;
    private AgentDefinitionVersionMapper versionMapper;
    private AgentVersionResourceMapper resourceMapper;
    private AgentModelMapper modelMapper;
    private AgentVersionContentHasher contentHasher;
    private JsonMapper jsonMapper;
    private AgentApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        definitionMapper = mock(AgentDefinitionMapper.class);
        versionMapper = mock(AgentDefinitionVersionMapper.class);
        resourceMapper = mock(AgentVersionResourceMapper.class);
        modelMapper = mock(AgentModelMapper.class);
        jsonMapper = JsonMapper.builder().build();
        contentHasher = new AgentVersionContentHasher(jsonMapper);
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(resourceMapper.selectBindings(anyLong())).thenReturn(List.of());
        service = new AgentApplicationService(
            principalProvider,
            authorizationEnforcer,
            idGenerator,
            definitionMapper,
            versionMapper,
            resourceMapper,
            modelMapper,
            new AgentConfigurationValidator(),
            contentHasher,
            jsonMapper
        );
    }

    @Test
    void draftCreationFreezesModelConfigurationAndHashesContent() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "draft", null));
        when(modelMapper.selectModelById(20L)).thenReturn(model(20L, "active"));
        when(versionMapper.selectNextVersionNo(10L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(30L);

        AgentVersionView result = service.createVersion(10L, versionRequest());

        ArgumentCaptor<AgentDefinitionVersion> captor = ArgumentCaptor.forClass(
            AgentDefinitionVersion.class
        );
        verify(versionMapper).insertVersion(captor.capture());
        AgentDefinitionVersion stored = captor.getValue();
        Map<String, Object> runtime = jsonMapper.readValue(stored.getRuntimeConfigJson(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) runtime.get("modelSnapshot");
        assertEquals("db:model:20", snapshot.get("credentialRef"));
        assertEquals("gpt-test", snapshot.get("modelName"));
        assertEquals(64, stored.getContentHash().length());
        assertEquals("draft", result.status());
        verify(modelMapper).lockModel(20L);
    }

    @Test
    void disabledModelPreventsDraftCreation() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "draft", null));
        when(modelMapper.selectModelById(20L)).thenReturn(model(20L, "disabled"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.createVersion(10L, versionRequest())
        );

        assertEquals(409, exception.getCode());
        verify(versionMapper, never()).insertVersion(any());
    }

    @Test
    void publishedVersionCannotBeEdited() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "active", 30L));
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(version(30L, "published"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.updateVersion(10L, 30L, versionRequest())
        );

        assertEquals(409, exception.getCode());
        verify(versionMapper, never()).updateDraft(any());
        verify(modelMapper, never()).selectModelById(anyLong());
    }

    @Test
    void cloneVersionCopiesContentAndBindingsIntoFreshDraft() {
        AgentDefinitionVersion published = version(30L, "published");
        AgentVersionBindingRow binding = new AgentVersionBindingRow();
        binding.setId(31L);
        binding.setResourceType("tool");
        binding.setResourceId(50L);
        binding.setPermission("invoke");
        binding.setConfigJson("{\"binding\":{\"enabled\":true},\"resourceSnapshot\":{\"name\":\"search\"}}");
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "active", 30L));
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(published);
        when(resourceMapper.selectBindings(30L)).thenReturn(List.of(binding));
        when(resourceMapper.selectBindings(40L)).thenReturn(List.of(binding));
        when(versionMapper.selectNextVersionNo(10L)).thenReturn(2);
        when(idGenerator.nextId()).thenReturn(40L, 41L);

        AgentVersionView result = service.cloneVersion(10L, 30L);

        ArgumentCaptor<AgentDefinitionVersion> versionCaptor = ArgumentCaptor.forClass(
            AgentDefinitionVersion.class
        );
        verify(versionMapper).insertVersion(versionCaptor.capture());
        AgentDefinitionVersion stored = versionCaptor.getValue();
        assertEquals(40L, stored.getId());
        assertEquals(2, stored.getVersionNo());
        assertEquals("draft", stored.getStatus());
        assertEquals(published.getRuntimeConfigJson(), stored.getRuntimeConfigJson());
        assertEquals(published.getSystemPrompt(), stored.getSystemPrompt());
        assertEquals(64, stored.getContentHash().length());
        verify(resourceMapper).insertToolBinding(
            41L, 40L, 50L, "invoke", binding.getConfigJson(), stored.getCreatedAt()
        );
        assertEquals("draft", result.status());
    }

    @Test
    void deleteDraftRemovesBindingsBeforeVersionRow() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "draft", null));
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(version(30L, "draft"));
        when(versionMapper.deleteDraft(10L, 30L)).thenReturn(1);

        service.deleteVersion(10L, 30L);

        InOrder deletion = inOrder(resourceMapper, versionMapper);
        deletion.verify(resourceMapper).deleteToolBindings(30L);
        deletion.verify(resourceMapper).deleteSkillBindings(30L);
        deletion.verify(resourceMapper).deleteKnowledgeBindings(30L);
        deletion.verify(versionMapper).deleteDraft(10L, 30L);
    }

    @Test
    void publishedVersionCannotBeDeleted() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "active", 30L));
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(version(30L, "published"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.deleteVersion(10L, 30L)
        );

        assertEquals(409, exception.getCode());
        verify(resourceMapper, never()).deleteToolBindings(anyLong());
        verify(versionMapper, never()).deleteDraft(anyLong(), anyLong());
    }

    @Test
    void publishRejectsTamperedDraftHash() {
        AgentDefinitionVersion version = version(30L, "draft");
        version.setContentHash("0".repeat(64));
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "draft", null));
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(version);
        when(modelMapper.selectModelById(20L)).thenReturn(model(20L, "active"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.publish(10L, 30L)
        );

        assertEquals(409, exception.getCode());
        verify(versionMapper, never()).archivePreviouslyPublished(anyLong(), anyLong());
        verify(versionMapper, never()).publishDraft(anyLong(), anyLong(), any());
    }

    @Test
    void publishArchivesOldVersionActivatesAgentAndIsThenIdempotent() {
        AgentDefinition definition = agent(10L, "draft", null);
        definition.setIsDefault(true);
        AgentDefinitionVersion draft = version(30L, "draft");
        draft.setContentHash(contentHasher.hash(draft, List.of()));
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(definition);
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(draft);
        when(modelMapper.selectModelById(20L)).thenReturn(model(20L, "active"));
        when(versionMapper.publishDraft(eq(10L), eq(30L), any(LocalDateTime.class))).thenReturn(1);

        AgentVersionPublishResult first = service.publish(10L, 30L);
        AgentVersionPublishResult replay = service.publish(10L, 30L);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals("published", first.version().status());
        verify(versionMapper).archivePreviouslyPublished(10L, 30L);
        verify(versionMapper).publishDraft(eq(10L), eq(30L), any(LocalDateTime.class));
        verify(definitionMapper).clearOtherDefaults(eq(10L), eq(1L), any(LocalDateTime.class));
        verify(definitionMapper).updateStatus(eq(10L), eq("active"), eq(1L), any(LocalDateTime.class));
    }

    @Test
    void allowedListFiltersEveryPublishedVersionThroughAuthorization() {
        AgentDefinition allowed = agent(10L, "active", 30L);
        AgentDefinition denied = agent(11L, "active", 31L);
        denied.setAgentKey("denied");
        when(definitionMapper.selectActiveCandidates(100)).thenReturn(List.of(allowed, denied));
        when(authorizationEnforcer.decide(eq(ADMIN), any()))
            .thenReturn(decision(PermissionEffect.ALLOW), decision(PermissionEffect.DENY));

        List<AgentView> result = service.allowed(100);

        assertEquals(1, result.size());
        assertEquals(10L, result.getFirst().id());
    }

    @Test
    void embedAccessResolvesStableAgentKeyAndChecksPublishedVersionUsePermission() {
        AgentDefinition definition = agent(10L, "active", 30L);
        definition.setAgentKey("finance-assistant");
        when(definitionMapper.selectDefinitionByKey("finance-assistant")).thenReturn(definition);

        AgentView result = service.embedAccess("finance-assistant");

        assertEquals(10L, result.id());
        assertEquals("finance-assistant", result.agentKey());
        verify(authorizationEnforcer).requireAllowed(eq(ADMIN), any());
    }

    @Test
    void embedAccessReturnsNotFoundForInactiveOrUnpublishedAgent() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "disabled", 30L));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.embedAccess("10")
        );

        assertEquals(404, exception.getCode());
        verify(authorizationEnforcer, never()).requireAllowed(any(), any());
    }

    @Test
    void embedAccessPreservesForbiddenDecisionForPublishedAgent() {
        AgentDefinition definition = agent(10L, "active", 30L);
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(definition);
        doThrow(new ServiceException("没有权限", 403))
            .when(authorizationEnforcer).requireAllowed(eq(ADMIN), any());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.embedAccess("10")
        );

        assertEquals(403, exception.getCode());
    }

    @Test
    void reorderLocksDeterministicallyAndPersistsTheWholeBatch() {
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L, "active", 30L));
        AgentDefinition second = agent(11L, "disabled", null);
        second.setAgentKey("second");
        when(definitionMapper.selectDefinitionById(11L)).thenReturn(second);
        when(definitionMapper.updateSortOrder(eq(11L), eq(20), eq(1L), any(LocalDateTime.class)))
            .thenReturn(1);
        when(definitionMapper.updateSortOrder(eq(10L), eq(10), eq(1L), any(LocalDateTime.class)))
            .thenReturn(1);

        service.reorder(List.of(
            new AgentReorderItemRequest(11L, 20),
            new AgentReorderItemRequest(10L, 10)
        ));

        InOrder locks = inOrder(definitionMapper);
        locks.verify(definitionMapper).lockAgent(10L);
        locks.verify(definitionMapper).selectDefinitionById(10L);
        locks.verify(definitionMapper).lockAgent(11L);
        locks.verify(definitionMapper).selectDefinitionById(11L);
        locks.verify(definitionMapper).updateSortOrder(eq(11L), eq(20), eq(1L), any(LocalDateTime.class));
        locks.verify(definitionMapper).updateSortOrder(eq(10L), eq(10), eq(1L), any(LocalDateTime.class));
    }

    @Test
    void reorderRejectsDuplicateAgentIdsBeforeWriting() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.reorder(List.of(
                new AgentReorderItemRequest(10L, 20),
                new AgentReorderItemRequest(10L, 10)
            ))
        );

        assertEquals(400, exception.getCode());
        verify(definitionMapper, never()).updateSortOrder(anyLong(), any(Integer.class), anyLong(), any());
    }

    @Test
    void onboardingCreatesIdentityAndInitialVersionAsOneIdempotentUnit() {
        when(idGenerator.nextId()).thenReturn(10L, 30L);
        when(definitionMapper.selectDefinitionById(10L))
            .thenReturn(agent(10L, "draft", null), onboardingAgent(10L, 30L));
        when(modelMapper.selectModelById(20L)).thenReturn(model(20L, "active"));
        when(versionMapper.selectNextVersionNo(10L)).thenReturn(1);
        when(definitionMapper.updateOnboardingMetadata(
            eq(10L), eq("onboarding-key-1"), eq(30L), anyString(), eq("resource"), eq(1L),
            any(LocalDateTime.class)
        )).thenReturn(1);

        AgentOnboardingResult result = service.onboard(new AgentOnboardingRequest(
            "onboarding-key-1", createAgentRequest(), versionRequest()
        ));

        assertEquals(10L, result.agent().id());
        assertEquals(30L, result.version().id());
        assertEquals("resource", result.onboardingStep());
        assertFalse(result.replayed());
        verify(definitionMapper).insertDefinition(any());
        verify(versionMapper).insertVersion(any());
    }

    @Test
    void onboardingReplayReturnsTheOriginalPairWithoutCreatingDuplicates() {
        AgentOnboardingRequest request = new AgentOnboardingRequest(
            "onboarding-key-1", createAgentRequest(), versionRequest()
        );
        String requestHash = group.aitools.nhs.platform.common.ContentHashing.sha256(
            jsonMapper.writeValueAsString(request)
        );
        AgentDefinition existing = onboardingAgent(10L, 30L, requestHash);
        when(definitionMapper.selectOnboardingAgentId(1L, "onboarding-key-1")).thenReturn(10L);
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(existing);
        when(versionMapper.selectVersion(10L, 30L)).thenReturn(version(30L, "draft"));

        AgentOnboardingResult result = service.onboard(request);

        assertTrue(result.replayed());
        assertEquals(10L, result.agent().id());
        assertEquals(30L, result.version().id());
        verify(definitionMapper, never()).insertDefinition(any());
        verify(versionMapper, never()).insertVersion(any());
    }

    private SaveAgentVersionRequest versionRequest() {
        return new SaveAgentVersionRequest(
            "You are a careful assistant.",
            20L,
            null,
            Map.of("maxIterations", 10, "workspaceAccess", "none"),
            Map.of("title", "Welcome"),
            List.of("assistant"),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private CreateAgentRequest createAgentRequest() {
        return new CreateAgentRequest(
            "assistant", "Assistant", null, "assistant", "agentscope_java", null,
            false, 0, Map.of()
        );
    }

    private AgentDefinition onboardingAgent(Long id, Long versionId) {
        return onboardingAgent(id, versionId, "request-hash");
    }

    private AgentDefinition onboardingAgent(Long id, Long versionId, String requestHash) {
        AgentDefinition definition = agent(id, "draft", null);
        definition.setExtraJson(
            "{\"onboardingKey\":\"onboarding-key-1\",\"onboardingVersionId\":"
                + versionId + ",\"onboardingRequestHash\":\"" + requestHash
                + "\",\"onboardingStep\":\"resource\"}"
        );
        return definition;
    }

    private AgentDefinition agent(Long id, String status, Long publishedVersionId) {
        AgentDefinition definition = new AgentDefinition();
        definition.setId(id);
        definition.setAgentKey("assistant");
        definition.setName("Assistant");
        definition.setAgentType("assistant");
        definition.setEngineType("agentscope_java");
        definition.setIsSystem(false);
        definition.setIsDefault(false);
        definition.setStatus(status);
        definition.setOwnerId(1L);
        definition.setSortOrder(0);
        definition.setEngineConfigJson("{}");
        definition.setCreateTime(LocalDateTime.now());
        definition.setDelFlag("0");
        definition.setPublishedVersionId(publishedVersionId);
        return definition;
    }

    private AgentModel model(Long id, String status) {
        AgentModel model = new AgentModel();
        model.setId(id);
        model.setModelKey("chat-main");
        model.setProviderType("openai");
        model.setModelName("gpt-test");
        model.setModelType("chat");
        model.setEndpointUrl("https://api.openai.com/v1");
        model.setCredentialRef("env:MODEL_KEY");
        model.setContextSize(32000);
        model.setMaxOutputTokens(4096);
        model.setReasoningConfigJson("{}");
        model.setCapabilityJson("{\"streaming\":true}");
        model.setStatus(status);
        return model;
    }

    private AgentDefinitionVersion version(Long id, String status) {
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(id);
        version.setAgentId(10L);
        version.setVersionNo(1);
        version.setSystemPrompt("You are a careful assistant.");
        version.setModelId(20L);
        version.setRuntimeConfigJson("{\"modelSnapshot\":{\"modelId\":20}}");
        version.setWelcomeConfigJson("{}");
        version.setRoutingTagsJson("[\"assistant\"]");
        version.setStatus(status);
        version.setContentHash("0".repeat(64));
        version.setCreatedBy(1L);
        version.setCreatedAt(LocalDateTime.now());
        return version;
    }

    private AuthorizationDecision decision(PermissionEffect effect) {
        return new AuthorizationDecision(effect, effect.name(), "", List.of());
    }
}
