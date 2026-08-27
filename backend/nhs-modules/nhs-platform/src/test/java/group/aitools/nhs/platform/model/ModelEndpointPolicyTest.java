package group.aitools.nhs.platform.model;

import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ModelEndpointPolicyTest {

    private final ModelEndpointPolicy strict = new ModelEndpointPolicy(false, false);

    @Test
    void officialOpenAiGetsHttpsDefault() {
        assertEquals(URI.create("https://api.openai.com/v1"), strict.normalize("openai", null));
    }

    @Test
    void insecureAndPrivateEndpointsFailClosed() {
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "http://models.example/v1")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://127.0.0.1/v1")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://169.254.169.254/latest")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://[::1]/v1")
        );
    }

    @Test
    void urlCredentialsQueriesFragmentsAndTraversalAreRejected() {
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://user:secret@models.example/v1")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://models.example/v1?token=secret")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://models.example/v1#secret")
        );
        assertThrows(
            ServiceException.class,
            () -> strict.normalize("openai-compatible", "https://models.example/v1/%2e%2e/admin")
        );
    }

    @Test
    void explicitPrivateDeploymentFlagsAllowLocalHttpEndpoint() {
        ModelEndpointPolicy local = new ModelEndpointPolicy(true, true);
        assertEquals(
            URI.create("http://localhost:11434/v1"),
            local.normalize("openai-compatible", "http://localhost:11434/v1/")
        );
    }
}
