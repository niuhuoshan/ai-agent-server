package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalChatBIModelGatewayTest {

    private final AgentModelMapper modelMapper = mock(AgentModelMapper.class);
    private final ModelEndpointPolicy endpointPolicy = mock(ModelEndpointPolicy.class);
    private final ModelCredentialResolver credentialResolver = mock(ModelCredentialResolver.class);
    private final HttpModelProviderClient modelClient = mock(HttpModelProviderClient.class);
    private final PortalChatBIModelGateway gateway = new PortalChatBIModelGateway(
        modelMapper, endpointPolicy, credentialResolver, modelClient
    );

    @Test
    void noActiveChatModelIsExplicitlyUnavailable() {
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> gateway.complete("system", "question")
        );

        assertEquals(503, exception.getCode());
        assertTrue(exception.getMessage().contains("未配置"));
        verify(modelClient, never()).complete(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void providerFailureIsNotConvertedIntoEmptySuccess() {
        AgentModel model = new AgentModel();
        model.setId(21L);
        model.setProviderType("openai-compatible");
        model.setEndpointUrl("https://model.example/v1");
        model.setCredentialRef("env:MODEL_KEY");
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(anyString(), anyString()))
            .thenReturn(URI.create("https://model.example/v1"));
        when(credentialResolver.resolve("env:MODEL_KEY")).thenReturn("secret");
        when(modelClient.complete(any(), any(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("provider failed"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> gateway.complete("system", "question")
        );

        assertEquals(503, exception.getCode());
        assertTrue(exception.getMessage().contains("provider failed"));
    }
}
