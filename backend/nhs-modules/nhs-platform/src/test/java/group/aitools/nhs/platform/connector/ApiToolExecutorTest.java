package group.aitools.nhs.platform.connector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ApiToolExecutor;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ApiToolExecutorTest {

    @Test
    void executesSameOriginGetWithBoundedJsonAndLateCredentialResolution() throws Exception {
        HttpServer server = server();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/json", "{\"items\":[\"ok\"]}");
        });
        server.start();
        try {
            ApiToolExecutor.ApiInvocationResult result = executor().invoke(
                connector(server, "bearer"),
                Map.of("method", "GET", "path", "/search", "maxOutputBytes", 4096),
                Map.of("query", "a b", "limit", 2)
            );

            assertEquals("limit=2&query=a+b", query.get());
            assertEquals("Bearer test-secret", authorization.get());
            assertEquals(200, result.statusCode());
            assertFalse(result.error());
            assertEquals(Map.of("items", java.util.List.of("ok")), result.content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesRedirectInsteadOfForwardingConnectorCredential() throws Exception {
        HttpServer server = server();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://other.example/steal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            ServiceException exception = assertThrows(
                ServiceException.class,
                () -> executor().invoke(
                    connector(server, "bearer"),
                    Map.of("method", "GET", "path", "/redirect"),
                    Map.of()
                )
            );

            assertEquals(502, exception.getCode());
        } finally {
            server.stop(0);
        }
    }

    private ApiToolExecutor executor() {
        JsonMapper mapper = JsonMapper.builder().build();
        return new ApiToolExecutor(
            new ConnectorEndpointPolicy(true, true),
            new ConnectorConfigurationValidator(mapper),
            ignored -> "test-secret",
            mapper
        );
    }

    private AgentConnector connector(HttpServer server, String authType) {
        AgentConnector connector = new AgentConnector();
        connector.setProviderType("api");
        connector.setEndpointUrl("http://127.0.0.1:" + server.getAddress().getPort());
        connector.setCredentialRef("env:API_KEY");
        connector.setConfigJson("""
            {"authType":"%s","connectTimeoutMs":1000,"requestTimeoutMs":3000}
            """.formatted(authType));
        return connector;
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
