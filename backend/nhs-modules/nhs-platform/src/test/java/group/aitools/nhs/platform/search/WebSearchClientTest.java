package group.aitools.nhs.platform.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.platform.search.service.SearchProviderConfig;
import group.aitools.nhs.platform.search.service.SearchProviderException;
import group.aitools.nhs.platform.search.service.WebSearchClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class WebSearchClientTest {

    @Test
    void callsBraveCompatibleGetWithCredentialAndReturnsStructuredCitations() throws Exception {
        HttpServer server = server();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> credential = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            credential.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            respond(exchange, 200, """
                {"web":{"results":[
                  {"title":"Result A","url":"https://example.com/a","description":"A snippet"},
                  {"title":"Result B","url":"javascript:alert(1)","description":"blocked"}
                ]}}
                """);
        });
        server.start();
        try {
            List<WebSearchClient.SearchHit> result = client().search(
                connector(server), config("brave", "GET"), "agent platform", 5
            );

            assertTrue(query.get().contains("q=agent%20platform"));
            assertTrue(query.get().contains("count=5"));
            assertEquals("test-secret", credential.get());
            assertEquals(1, result.size());
            assertEquals("https://example.com/a", result.getFirst().url());
            assertEquals("example.com", result.getFirst().source());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callsSerperCompatiblePostAndParsesOrganicResults() throws Exception {
        HttpServer server = server();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"organic":[{"title":"Docs","link":"https://docs.example.org/guide","snippet":"Guide"}]}
                """);
        });
        server.start();
        try {
            List<WebSearchClient.SearchHit> result = client().search(
                connector(server), config("serper", "POST"), "private agent", 3
            );

            assertTrue(body.get().contains("\"q\":\"private agent\""));
            assertTrue(body.get().contains("\"num\":3"));
            assertEquals("Docs", result.getFirst().title());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void supportsPrivateSearxngJsonWithoutProviderCredential() throws Exception {
        HttpServer server = server();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                {"results":[{"title":"Internal result","url":"https://example.net/page","content":"Summary"}]}
                """);
        });
        server.start();
        try {
            SearchProviderConfig config = new SearchProviderConfig(
                "searxng", "GET", "q", "count", 10, 60, 3, 60,
                "none", null, Duration.ofSeconds(1), Duration.ofSeconds(3)
            );
            List<WebSearchClient.SearchHit> result = client().search(
                connector(server), config, "private knowledge", 5
            );

            assertTrue(query.get().contains("format=json"));
            assertTrue(!query.get().contains("count="));
            assertEquals("Summary", result.getFirst().snippet());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectsWithoutForwardingCredential() throws Exception {
        HttpServer server = server();
        server.createContext("/search", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://other.example/steal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            SearchProviderException error = assertThrows(
                SearchProviderException.class,
                () -> client().search(connector(server), config("custom", "GET"), "query", 2)
            );
            assertEquals("search_redirect_rejected", error.errorCode());
        } finally {
            server.stop(0);
        }
    }

    private WebSearchClient client() {
        return new WebSearchClient(
            new ConnectorEndpointPolicy(true, true), ignored -> "test-secret",
            JsonMapper.builder().build()
        );
    }

    private AgentConnector connector(HttpServer server) {
        AgentConnector connector = new AgentConnector();
        connector.setProviderType("search");
        connector.setEndpointUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        connector.setCredentialRef("env:SEARCH_KEY");
        return connector;
    }

    private SearchProviderConfig config(String engine, String method) {
        return new SearchProviderConfig(
            engine, method, "q", "serper".equals(engine) ? "num" : "count",
            10, 60, 3, 60, "header", "X-Subscription-Token",
            Duration.ofSeconds(1), Duration.ofSeconds(3)
        );
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
