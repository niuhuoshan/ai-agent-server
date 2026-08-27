package group.aitools.nhs.platform.model;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.persistence.row.ModelReferenceRow;
import group.aitools.nhs.platform.model.service.ModelApplicationService;
import group.aitools.nhs.platform.model.service.ModelConfigurationValidator;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.platform.model.service.ModelProviderClient;
import group.aitools.nhs.platform.model.web.CreateModelRequest;
import group.aitools.nhs.platform.model.web.ModelConnectionView;
import group.aitools.nhs.platform.model.web.ModelView;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ModelApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private AgentModelMapper modelMapper;
    private ModelCredentialResolver credentialResolver;
    private ModelProviderClient providerClient;
    private ModelApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        modelMapper = mock(AgentModelMapper.class);
        credentialResolver = mock(ModelCredentialResolver.class);
        providerClient = mock(ModelProviderClient.class);
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        service = new ModelApplicationService(
            principalProvider,
            authorizationEnforcer,
            idGenerator,
            modelMapper,
            new ModelConfigurationValidator(),
            new ModelEndpointPolicy(false, false),
            credentialResolver,
            providerClient,
            JsonMapper.builder().build()
        );
    }

    @Test
    void createStoresEnteredApiKeyAndValidatedJson() {
        when(idGenerator.nextId()).thenReturn(10L);

        ModelView result = service.create(createRequest(Map.of("temperature", 0.5)));

        ArgumentCaptor<AgentModel> captor = ArgumentCaptor.forClass(AgentModel.class);
        verify(modelMapper).insertModel(captor.capture());
        AgentModel stored = captor.getValue();
        assertEquals("raw-secret", stored.getCredentialRef());
        assertFalse(stored.getReasoningConfigJson().contains("OPENAI_API_KEY"));
        assertEquals("https://api.openai.com/v1", stored.getEndpointUrl());
        assertEquals(10L, result.id());
        assertEquals(Map.of("temperature", 0.5), result.reasoningConfig());
    }

    @Test
    void missingApiKeyAndUnknownRuntimeOptionAreRejectedBeforePersistence() {
        CreateModelRequest missingApiKey = new CreateModelRequest(
            "chat-main", "Chat", "openai", "gpt-test", "chat", null,
            null, 32000, 4096, Map.of(), Map.of(), "active"
        );
        CreateModelRequest valid = createRequest(Map.of());

        assertThrows(ServiceException.class, () -> service.create(missingApiKey));
        service.create(valid);
        verify(modelMapper).insertModel(any());
    }

    @Test
    void connectivityTestResolvesSecretOnlyAtProviderBoundary() {
        AgentModel model = persistedModel(10L);
        when(modelMapper.selectModelById(10L)).thenReturn(model);
        when(credentialResolver.resolve(model.getCredentialRef())).thenReturn("resolved-secret");
        when(providerClient.test(eq(model), eq(URI.create("https://api.openai.com/v1")), eq("resolved-secret")))
            .thenReturn(new ModelConnectionView(true, "连接成功", "chat completion received", 12));

        ModelConnectionView result = service.test(10L);

        assertTrue(result.success());
        assertFalse(result.toString().contains("resolved-secret"));
        verify(providerClient).test(model, URI.create("https://api.openai.com/v1"), "resolved-secret");
    }

    @Test
    void unavailableStoredCredentialReturnsSanitizedFailure() {
        AgentModel model = persistedModel(10L);
        when(modelMapper.selectModelById(10L)).thenReturn(model);
        when(credentialResolver.resolve(model.getCredentialRef()))
            .thenThrow(new IllegalStateException("模型 API Key 不可用或已变更"));

        ModelConnectionView result = service.test(10L);

        assertFalse(result.success());
        assertTrue(result.message().contains("不可用"));
        verify(providerClient, never()).test(any(), any(), any());
    }

    @Test
    void referencedModelCannotBeDeleted() {
        when(modelMapper.selectModelById(10L)).thenReturn(persistedModel(10L));
        ModelReferenceRow reference = new ModelReferenceRow();
        reference.setAgentId(20L);
        reference.setAgentName("Research");
        reference.setVersionId(21L);
        reference.setVersionNo(1);
        reference.setVersionStatus("published");
        reference.setPrimaryModel(true);
        when(modelMapper.selectActiveReferences(10L)).thenReturn(List.of(reference));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(10L));

        assertEquals(409, exception.getCode());
        verify(modelMapper).lockModel(10L);
        verify(modelMapper, never()).softDelete(any(), any(), any());
    }

    @Test
    void unreferencedModelIsSoftDeletedAfterTransactionLock() {
        when(modelMapper.selectModelById(10L)).thenReturn(persistedModel(10L));
        when(modelMapper.selectActiveReferences(10L)).thenReturn(List.of());
        when(modelMapper.softDelete(eq(10L), eq(1L), any(LocalDateTime.class))).thenReturn(1);

        service.delete(10L);

        verify(modelMapper).lockModel(10L);
        verify(modelMapper).softDelete(eq(10L), eq(1L), any(LocalDateTime.class));
    }

    private CreateModelRequest createRequest(Map<String, Object> reasoning) {
        return new CreateModelRequest(
            "chat-main", "Chat", "openai", "gpt-test", "chat", null,
            "raw-secret", 32000, 4096, reasoning,
            Map.of("streaming", true, "toolCalling", true), "active"
        );
    }

    private AgentModel persistedModel(Long id) {
        AgentModel model = new AgentModel();
        model.setId(id);
        model.setModelKey("chat-main");
        model.setDisplayName("Chat");
        model.setProviderType("openai");
        model.setModelName("gpt-test");
        model.setModelType("chat");
        model.setEndpointUrl("https://api.openai.com/v1");
        model.setCredentialRef("stored-secret");
        model.setReasoningConfigJson("{}");
        model.setCapabilityJson("{}");
        model.setStatus("active");
        model.setCreateTime(LocalDateTime.now());
        model.setDelFlag("0");
        return model;
    }
}
