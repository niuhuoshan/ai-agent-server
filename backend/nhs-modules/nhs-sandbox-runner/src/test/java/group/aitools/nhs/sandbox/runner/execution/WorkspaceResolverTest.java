package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import group.aitools.nhs.sandbox.runner.execution.WorkspaceResolver.WorkspacePolicyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class WorkspaceResolverTest {

    @TempDir
    Path temporary;

    @Test
    void resolvesOnlyInsideTaskRunWorkspace() {
        WorkspaceResolver resolver = resolver();

        Path result = resolver.resolve(job("outputs/report"));

        assertTrue(result.startsWith(temporary.toAbsolutePath().normalize()));
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void rejectsTraversalAbsoluteAndWindowsStylePaths() {
        WorkspaceResolver resolver = resolver();

        assertThrows(WorkspacePolicyException.class, () -> resolver.resolve(job("../secret")));
        assertThrows(WorkspacePolicyException.class, () -> resolver.resolve(job("/etc")));
        assertThrows(WorkspacePolicyException.class, () -> resolver.resolve(job("C:\\temp")));
    }

    @Test
    void rejectsExistingSymlinkEscape() throws IOException {
        WorkspaceResolver resolver = resolver();
        Path runRoot = temporary.resolve("task-20/run-30");
        Files.createDirectories(runRoot);
        Path outside = temporary.resolveSibling("outside-" + System.nanoTime());
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(runRoot.resolve("escape"), outside);

            assertThrows(WorkspacePolicyException.class, () -> resolver.resolve(job("escape")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void workspaceKeyUsesTheSharedAgentScopeWorkspaceBeforeLegacyTaskIdentity() {
        WorkspaceResolver resolver = resolver();

        Path result = resolver.resolve(job("outputs/report", "run-shared-30"));

        assertEquals(
            temporary.resolve("run-shared-30/outputs/report").toAbsolutePath().normalize(),
            result
        );
    }

    @Test
    void rejectsPathShapedWorkspaceKeys() {
        WorkspaceResolver resolver = resolver();

        assertThrows(
            WorkspacePolicyException.class,
            () -> resolver.resolve(job(".", "../outside"))
        );
    }

    @Test
    void chatCodeWithWorkspaceKeyRunsInTheConversationWorkspace() throws IOException {
        WorkspaceResolver resolver = resolver();
        ClaimedJob job = chatJob("python", "print('safe')", "conversation-31");

        Path result = resolver.resolve(job);
        resolver.materializeChatScript(job, result);

        assertEquals(temporary.resolve("conversation-31").toAbsolutePath().normalize(), result);
        assertEquals(
            "print('safe')",
            Files.readString(result.resolve(".agent-chat-code-10-2.py"))
        );
    }

    @Test
    void chatCodeGetsAnAttemptIsolatedWorkspaceAndAReadOnlyScript() throws IOException {
        WorkspaceResolver resolver = resolver();
        ClaimedJob job = chatJob("shell", "printf '%s\\n' safe");

        Path result = resolver.resolve(job);
        resolver.materializeChatScript(job, result);

        assertTrue(result.endsWith(
            Path.of("chat-code/user-21/conversation-31/job-10/attempt-2")
        ));
        assertEquals(
            "printf '%s\\n' safe",
            Files.readString(result.resolve(".agent-chat-code.sh"))
        );
        assertThrows(
            WorkspacePolicyException.class,
            () -> resolver.materializeChatScript(job, result)
        );
    }

    private WorkspaceResolver resolver() {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setWorkspaceRoot(temporary);
        return new WorkspaceResolver(properties);
    }

    private ClaimedJob job(String workspace) {
        return job(workspace, null);
    }

    private ClaimedJob job(String workspace, String workspaceKey) {
        return new ClaimedJob(
            10L, 20L, 30L, null, 40L, "a".repeat(64), "token",
            "python-3.11", List.of("python"), workspace, "read_write", "none",
            List.of(), 300, 512, 1000, 128, 1048576, null, 1,
            "task_tool", null, null, null, null,
            workspaceKey, null, null
        );
    }

    private ClaimedJob chatJob(String language, String script) {
        return chatJob(language, script, null);
    }

    private ClaimedJob chatJob(String language, String script, String workspaceKey) {
        return new ClaimedJob(
            10L, null, null, null, null, "a".repeat(64), "token",
            "shell", null, "ignored", "read_write", "none", List.of(),
            300, 512, 1000, 128, 1048576, null, 2,
            "chat_code", 21L, 31L, language, script,
            workspaceKey, null, null
        );
    }
}
