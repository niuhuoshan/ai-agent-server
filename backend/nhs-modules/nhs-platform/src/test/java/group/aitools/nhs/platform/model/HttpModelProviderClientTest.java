package group.aitools.nhs.platform.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.platform.model.web.ModelConnectionView;
import group.aitools.nhs.platform.model.web.ModelOptionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class HttpModelProviderClientTest {

    private HttpServer server;
    private HttpModelProviderClient client;
    private AtomicReference<String> authorization;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        client = new HttpModelProviderClient(
            new ModelEndpointPolicy(true, true),
            JsonMapper.builder().build()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.close();
    }

    @Test
    void chatTestUsesCredentialAndReturnsOnlySanitizedSummary() {
        ModelConnectionView result = client.test(chatModel(), endpoint("v1"), "top-secret");

        assertTrue(result.success());
        assertEquals("chat completion received", result.responseSummary());
        assertEquals("Bearer top-secret", authorization.get());
        assertFalse(result.toString().contains("top-secret"));
    }

    @Test
    void boundedCompletionReturnsAssistantText() {
        String result = client.complete(
            chatModel(), endpoint("v1"), "top-secret", "只返回JSON", "生成推荐问题"
        );

        assertEquals("pong", result);
        assertEquals("Bearer top-secret", authorization.get());
    }

    @Test
    void discoveryDeduplicatesAndDropsInvalidEntries() {
        List<ModelOptionView> result = client.discover(endpoint("v1"), "secret");

        assertEquals(2, result.size());
        assertEquals("model-a", result.get(0).modelName());
        assertEquals("Model B", result.get(1).displayName());
    }

    @Test
    void redirectsAndOversizedResponsesAreRejectedWithoutFollowing() {
        ModelConnectionView redirect = client.test(chatModel(), endpoint("redirect"), "secret");
        ModelConnectionView large = client.test(chatModel(), endpoint("large"), "secret");

        assertFalse(redirect.success());
        assertTrue(redirect.message().contains("重定向"));
        assertFalse(large.success());
        assertTrue(large.message().contains("64KB"));
    }

    @Test
    void providerErrorBodyAndCredentialNeverReachFailureMessage() {
        ModelConnectionView result = client.test(chatModel(), endpoint("unauthorized"), "top-secret");

        assertFalse(result.success());
        assertTrue(result.message().contains("鉴权失败"));
        assertFalse(result.message().contains("server-body-secret"));
        assertFalse(result.message().contains("top-secret"));
    }

    private AgentModel chatModel() {
        AgentModel model = new AgentModel();
        model.setModelName("test-chat");
        model.setModelType("chat");
        return model;
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path);
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        if ("/v1/models".equals(path)) {
            respond(exchange, 200, """
                {"data":[
                  {"id":"model-a"},
                  {"id":"model-a","name":"duplicate"},
                  {"model":"model-b","name":"Model B"},
                  {"name":"missing-id"},
                  42
                ]}
                """);
            return;
        }
        if ("/v1/chat/completions".equals(path)) {
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}");
            return;
        }
        if ("/redirect/chat/completions".equals(path)) {
            exchange.getResponseHeaders().add("Location", endpoint("v1/chat/completions").toString());
            respond(exchange, 302, "redirect");
            return;
        }
        if ("/large/chat/completions".equals(path)) {
            respond(exchange, 200, "x".repeat(70 * 1024));
            return;
        }
        if ("/unauthorized/chat/completions".equals(path)) {
            respond(exchange, 401, "server-body-secret");
            return;
        }
        respond(exchange, 404, "not found");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
