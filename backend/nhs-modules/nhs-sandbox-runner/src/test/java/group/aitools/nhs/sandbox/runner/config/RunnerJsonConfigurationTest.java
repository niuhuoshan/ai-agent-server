package group.aitools.nhs.sandbox.runner.config;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class RunnerJsonConfigurationTest {

    @Test
    void readsPlatformLocalDateTimeFormatFromClaimResponse() throws Exception {
        String json = """
            {
              "jobId": 1,
              "taskId": 2,
              "runId": 3,
              "toolId": 4,
              "traceId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "jobToken": "token",
              "templateKey": "code",
              "argv": ["python", "-V"],
              "workspacePath": ".",
              "workspaceKey": "run-3",
              "workspaceAccess": "read_write",
              "networkPolicy": "none",
              "allowedHosts": [],
              "timeoutSeconds": 30,
              "memoryMb": 128,
              "cpuMillis": 500,
              "pidsLimit": 32,
              "maxOutputBytes": 4096,
              "skillManifestJson": "{\\\"version\\\":1,\\\"workspaceKey\\\":\\\"run-3\\\",\\\"skills\\\":[]}",
              "skillManifestHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "leaseUntil": "2026-08-14 21:18:34",
              "attemptNo": 1
            }
            """;

        ClaimedJob job = new RunnerJsonConfiguration().runnerJsonMapper()
            .readValue(json, ClaimedJob.class);

        assertEquals(LocalDateTime.of(2026, 8, 14, 21, 18, 34), job.leaseUntil());
        assertEquals("run-3", job.workspaceKey());
        assertEquals("b".repeat(64), job.skillManifestHash());
    }
}
