package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ConnectorEndpointPolicyTest {

    @Test
    void rejectsLocalPrivateIpv4Ipv6AndEncodedTraversalByDefault() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(false, false);
        List<String> unsafe = List.of(
            "https://localhost/rpc",
            "https://service.internal/rpc",
            "https://127.0.0.1/rpc",
            "https://10.0.0.1/rpc",
            "https://[::1]/rpc",
            "https://[fc00::1]/rpc",
            "https://example.com/%2e%2e/admin",
            "https://user@example.com/rpc",
            "https://example.com/rpc?target=x"
        );

        unsafe.forEach(endpoint -> assertThrows(
            ServiceException.class, () -> policy.normalize(endpoint), endpoint
        ));
    }

    @Test
    void checksLiteralNetworkTargetsAndMcpMessageOrigin() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(false, false);

        assertDoesNotThrow(() -> policy.validateNetworkTarget(URI.create("https://8.8.8.8/rpc")));
        assertThrows(
            ServiceException.class,
            () -> policy.requireSameOrigin(
                URI.create("https://mcp.example/rpc"),
                URI.create("https://other.example/messages")
            )
        );
        assertDoesNotThrow(() -> policy.requireSameOrigin(
            URI.create("https://mcp.example/rpc"),
            URI.create("https://mcp.example/messages")
        ));
    }

    @Test
    void privateDeploymentOverrideMustBeExplicitForHttpAndPrivateHosts() {
        ConnectorEndpointPolicy policy = new ConnectorEndpointPolicy(true, true);

        assertDoesNotThrow(() -> policy.normalize("http://127.0.0.1:8080/rpc"));
        assertDoesNotThrow(() -> policy.validateNetworkTarget(
            URI.create("http://127.0.0.1:8080/rpc")
        ));
    }
}
