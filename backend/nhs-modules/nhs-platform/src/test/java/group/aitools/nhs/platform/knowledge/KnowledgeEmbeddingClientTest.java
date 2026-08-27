package group.aitools.nhs.platform.knowledge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import group.aitools.nhs.platform.knowledge.service.KnowledgeEmbeddingClient;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class KnowledgeEmbeddingClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private KnowledgeEmbeddingClient client;
    private volatile String response;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handle);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        client = new KnowledgeEmbeddingClient(
            new ModelEndpointPolicy(true, true),
            ignored -> "secret",
            JsonMapper.builder().build()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.close();
    }

    @Test
    void restoresProviderIndexesAndValidatesExpectedDimension() {
        response = """
            {"data":[
              {"index":1,"embedding":[0.3,0.4]},
              {"index":0,"embedding":[0.1,0.2]}
            ]}
            """;

        var vectors = client.embed(model(), List.of("first", "second"), 2);

        assertEquals("[0.1,0.2]", vectors.getFirst().postgresValue());
        assertEquals("[0.3,0.4]", vectors.get(1).postgresValue());
    }

    @Test
    void rejectsDuplicateIndexesAndDimensionMismatch() {
        response = """
            {"data":[
              {"index":0,"embedding":[0.1,0.2]},
              {"index":0,"embedding":[0.3,0.4]}
            ]}
            """;
        assertThrows(
            IllegalStateException.class,
            () -> client.embed(model(), List.of("first", "second"), 2)
        );

        response = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        assertThrows(
            IllegalStateException.class,
            () -> client.embed(model(), List.of("first"), 2)
        );
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setProviderType("openai-compatible");
        model.setModelName("embedding-model");
        model.setModelType("embedding");
        model.setEndpointUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        model.setCredentialRef("env:TEST_KEY");
        model.setStatus("active");
        return model;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
