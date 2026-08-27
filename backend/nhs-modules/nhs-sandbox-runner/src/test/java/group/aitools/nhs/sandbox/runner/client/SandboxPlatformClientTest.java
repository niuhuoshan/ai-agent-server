package group.aitools.nhs.sandbox.runner.client;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.OutputChunk;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class SandboxPlatformClientTest {

    @TempDir
    Path temporary;

    @Test
    void appendsOutputWithJobAuthenticationAndTheExactProtocolBody() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> jobToken = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            jobToken.set(exchange.getRequestHeaders().getFirst("X-Sandbox-Job-Token"));
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"code\":200,\"msg\":\"ok\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            SandboxRunnerProperties properties = properties(server.getAddress().getPort());
            RunnerCredentialStore credentialStore = new RunnerCredentialStore(properties);
            credentialStore.write("runner-secret");
            JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
            SandboxPlatformClient client = new SandboxPlatformClient(
                properties, credentialStore, mapper
            );
            client.ensureRegistered();

            client.appendOutput(chatJob(), new OutputChunk(7L, "stderr", "failure text"));

            JsonNode json = mapper.readTree(body.get());
            assertEquals("/internal/sandbox/v1/jobs/10/output", path.get());
            assertEquals("Bearer runner-secret", authorization.get());
            assertEquals("job-token", jobToken.get());
            assertEquals(7, json.get("sequenceNo").asInt());
            assertEquals("stderr", json.get("stream").asText());
            assertEquals("failure text", json.get("content").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsRawSkillBundleWithJobAuthenticationAndManifestHash() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> jobToken = new AtomicReference<>();
        byte[] archive = new byte[] {31, (byte) 139, 8, 0, 1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sandbox/v1/jobs/10/skill-bundle", exchange -> {
            method.set(exchange.getRequestMethod());
            jobToken.set(exchange.getRequestHeaders().getFirst("X-Sandbox-Job-Token"));
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.getResponseHeaders().set("X-Sandbox-Skill-Manifest-Hash", "b".repeat(64));
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        try {
            SandboxRunnerProperties properties = properties(server.getAddress().getPort());
            RunnerCredentialStore credentialStore = new RunnerCredentialStore(properties);
            credentialStore.write("runner-secret");
            SandboxPlatformClient client = new SandboxPlatformClient(
                properties, credentialStore, JsonMapper.builder().findAndAddModules().build()
            );

            SandboxPlatformClient.SkillBundle bundle = client.downloadSkillBundle(chatJob());

            assertEquals("GET", method.get());
            assertEquals("job-token", jobToken.get());
            assertEquals("b".repeat(64), bundle.manifestHash());
            assertArrayEquals(archive, bundle.bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsSkillBundleWhenResponseHashDoesNotMatchClaimedSnapshot() throws Exception {
        assertSkillBundleRejected("b".repeat(64), "a".repeat(64));
    }

    @Test
    void rejectsSkillBundleWhenResponseHashIsMalformed() throws Exception {
        assertSkillBundleRejected("b".repeat(64), "not-a-sha256");
    }

    private void assertSkillBundleRejected(String claimedHash, String responseHash) throws Exception {
        byte[] archive = new byte[] {31, (byte) 139, 8, 0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sandbox/v1/jobs/10/skill-bundle", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.getResponseHeaders().set("X-Sandbox-Skill-Manifest-Hash", responseHash);
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        try {
            SandboxRunnerProperties properties = properties(server.getAddress().getPort());
            RunnerCredentialStore credentialStore = new RunnerCredentialStore(properties);
            credentialStore.write("runner-secret");
            SandboxPlatformClient client = new SandboxPlatformClient(
                properties, credentialStore, JsonMapper.builder().findAndAddModules().build()
            );

            assertThrows(
                IllegalStateException.class,
                () -> client.downloadSkillBundle(chatJob(claimedHash))
            );
        } finally {
            server.stop(0);
        }
    }

    private SandboxRunnerProperties properties(int port) throws Exception {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setPlatformBaseUrl("http://127.0.0.1:" + port);
        properties.setRunnerKey("test-runner");
        Path credential = temporary.resolve("credential");
        Files.createFile(credential);
        try {
            Files.setPosixFilePermissions(credential, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX test filesystems rely on their native ACLs.
        }
        properties.setCredentialFile(credential);
        return properties;
    }

    private ClaimedJob chatJob() {
        return chatJob("b".repeat(64));
    }

    private ClaimedJob chatJob(String manifestHash) {
        return new ClaimedJob(
            10L, null, null, null, null, "a".repeat(64), "job-token",
            "python-3.11", null, ".", "read_write", "none", List.of(),
            30, 128, 500, 32, 4096, null, 1,
            "chat_code", 20L, 30L, "python", "print('safe')",
            "conversation-30", "{\"skills\":[],\"version\":1}", manifestHash
        );
    }
}
