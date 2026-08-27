package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import group.aitools.nhs.sandbox.runner.execution.ContainerCommandBuilder.SandboxExecutionPolicyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ContainerCommandBuilderTest {

    private static final String IMAGE = "registry.local/agent/python@sha256:" + "a".repeat(64);

    @Test
    void untrustedArgumentsAppearOnlyAfterImmutableImageAndNeverUseAShell() {
        ContainerCommandBuilder builder = builder();
        List<String> argv = List.of(
            "python", "-c", "print('safe')", ";rm -rf /", "$(id)", "`id`", "--privileged"
        );

        List<String> command = builder.build(job(argv, "none", List.of()), Path.of("/safe/workspace")).command();

        int imageIndex = command.indexOf(IMAGE);
        assertTrue(imageIndex > 0);
        assertEquals(argv, command.subList(imageIndex + 1, command.size()));
        assertFalse(command.contains("sh"));
        assertFalse(command.contains("bash"));
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--cap-drop=ALL"));
        assertTrue(command.contains("--security-opt=no-new-privileges"));
        assertTrue(command.indexOf("--privileged") > imageIndex);
    }

    @Test
    void rejectsMutableImagesAndUnenforcedNetworkAllowlist() {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setTemplates(Map.of("python-3.11", "registry.local/agent/python:latest"));
        assertThrows(IllegalArgumentException.class, () -> new ContainerCommandBuilder(properties));

        assertThrows(SandboxExecutionPolicyException.class, () -> builder().build(
            job(List.of("python"), "allowlist", List.of("example.com")), Path.of("/safe/workspace")
        ));
    }

    @Test
    void chatCodeUsesOnlyTheAllowlistedInterpreterAndWorkspaceScriptPath() {
        ClaimedJob job = chatJob("python", "print('safe'); $(touch /host)");

        List<String> command = builder().build(job, Path.of("/safe/workspace")).command();

        int imageIndex = command.indexOf(IMAGE);
        assertEquals(
            List.of("python", "/workspace/.agent-chat-code.py"),
            command.subList(imageIndex + 1, command.size())
        );
        assertFalse(command.stream().anyMatch(value -> value.contains("touch /host")));
    }

    @Test
    void chatCodeRejectsLanguagesOutsideTheExplicitAllowlist() {
        assertThrows(RuntimeException.class, () -> builder().build(
            chatJob("javascript", "console.log('no')"), Path.of("/safe/workspace")
        ));
    }

    @Test
    void bashUsesAFixedContainerInterpreterWithoutCommandStringInterpolation() {
        List<String> command = builder().build(
            chatJob("bash", "echo \"$HOME\"; rm -rf /"), Path.of("/safe/workspace")
        ).command();

        int imageIndex = command.indexOf(IMAGE);
        assertEquals(
            List.of("/bin/bash", "/workspace/.agent-chat-code.sh"),
            command.subList(imageIndex + 1, command.size())
        );
        assertFalse(command.stream().anyMatch(value -> value.contains("rm -rf")));
    }

    @Test
    void skillMountIsReadOnlyAndOnlyDerivesFixedInterpreterPaths() {
        SkillSandboxBridge.PreparedMount mount = new SkillSandboxBridge.PreparedMount(
            Path.of("/var/lib/nhs/.skill-staging/job-10-attempt-1"),
            Path.of("/var/lib/nhs/.skill-staging/job-10-attempt-1/skills"),
            List.of("reviewer", "finance")
        );
        List<String> command = builder().build(
            job(List.of("python", "scripts/run.py"), "none", List.of()),
            Path.of("/safe/workspace"), mount
        ).command();

        assertTrue(command.contains(
            "--volume=/var/lib/nhs/.skill-staging/job-10-attempt-1/skills:/workspace/skills:ro,Z"
        ));
        assertTrue(command.contains(
            "--env=PYTHONPATH=/workspace/skills/reviewer/.agent-dependencies/python:/workspace/skills/finance/.agent-dependencies/python"
        ));
        assertTrue(command.contains(
            "--env=NODE_PATH=/workspace/skills/reviewer/.agent-dependencies/node/node_modules:/workspace/skills/finance/.agent-dependencies/node/node_modules"
        ));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("--env=") && value.contains("HOME")));
    }

    private ContainerCommandBuilder builder() {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setEngine("podman");
        properties.setTemplates(Map.of("python-3.11", IMAGE));
        return new ContainerCommandBuilder(properties);
    }

    private ClaimedJob job(List<String> argv, String network, List<String> hosts) {
        return new ClaimedJob(
            10L, 20L, 30L, null, 40L, "a".repeat(64), "token",
            "python-3.11", argv, ".", "read_write", network, hosts,
            300, 512, 1000, 128, 1048576, null, 1,
            null, null, null, null, null
        );
    }

    private ClaimedJob chatJob(String language, String script) {
        return new ClaimedJob(
            11L, null, null, null, null, "b".repeat(64), "token",
            "python-3.11", List.of("ignored", "$(id)"), ".", "read_write",
            "none", List.of(), 300, 512, 1000, 128, 1048576, null, 1,
            "chat_code", 21L, 31L, language, script
        );
    }
}
