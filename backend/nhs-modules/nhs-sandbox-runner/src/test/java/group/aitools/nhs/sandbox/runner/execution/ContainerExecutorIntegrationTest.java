package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
@Tag("container")
@EnabledIfEnvironmentVariable(named = "NHS_SANDBOX_TEST_IMAGE", matches = ".+@sha256:[0-9a-f]{64}")
class ContainerExecutorIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void realContainerKeepsArgvLiteralRunsNonRootAndHasNoHostNetwork() throws IOException {
        makeContainerAccessible(workspace);
        ContainerExecutor executor = executor();

        var injection = executor.execute(job(
            1L, 10, 4096, List.of(
                "python", "-c",
                "import os,sys; open('sandbox-write.txt','w').write('ok'); print(os.geteuid()); print(sys.argv[1])",
                ";echo INJECTION"
            )
        ), () -> true);
        var network = executor.execute(job(
            2L, 10, 4096, List.of(
                "python", "-c",
                "import socket\ntry:\n socket.create_connection(('127.0.0.1',5432),1); print('open')\nexcept OSError:\n print('blocked')"
            )
        ), () -> true);

        assertTrue(injection.succeeded());
        assertEquals("65532\n;echo INJECTION\n", injection.stdout());
        assertFalse(injection.stdout().contains("uid="));
        assertEquals("ok", Files.readString(workspace.resolve("task-20/run-31/sandbox-write.txt")));
        assertTrue(network.succeeded());
        assertEquals("blocked\n", network.stdout());
    }

    @Test
    void realContainerEnforcesTimeoutAndBoundedOutput() throws IOException {
        makeContainerAccessible(workspace);
        ContainerExecutor executor = executor();

        var timeout = executor.execute(job(
            3L, 1, 4096, List.of("python", "-c", "import time; time.sleep(5)")
        ), () -> true);
        var output = executor.execute(job(
            4L, 10, 1024, List.of("python", "-c", "print('x' * 10000)")
        ), () -> true);

        assertFalse(timeout.succeeded());
        assertEquals("EXECUTION_TIMEOUT", timeout.failureCode());
        assertFalse(output.succeeded());
        assertEquals("OUTPUT_LIMIT_EXCEEDED", output.failureCode());
        assertTrue(output.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
    }

    private ContainerExecutor executor() {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setEngine("docker");
        properties.setWorkspaceRoot(workspace);
        properties.setTemplates(Map.of("python-3.11", System.getenv("NHS_SANDBOX_TEST_IMAGE")));
        ContainerCommandBuilder commandBuilder = new ContainerCommandBuilder(properties);
        return new ContainerExecutor(commandBuilder, new WorkspaceResolver(properties));
    }

    private ClaimedJob job(Long id, int timeoutSeconds, int outputBytes, List<String> argv) {
        return new ClaimedJob(
            id, 20L, 30L + id, null, 40L, "a".repeat(64), "token",
            "python-3.11", argv, ".", "read_write", "none", List.of(),
            timeoutSeconds, 128, 500, 32, outputBytes, null, 1,
            "task_tool", null, null, null, null
        );
    }

    private void makeContainerAccessible(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Container integration runs on a POSIX host in the supported deployment profile.
        }
    }
}
