package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;

import java.util.Map;

final class RuntimeFixtures {

    private RuntimeFixtures() {
    }

    static AgentRunRequest runRequest() {
        return runRequest("execution-1");
    }

    static AgentRunRequest runRequest(String executionId) {
        return new AgentRunRequest(
            new RuntimeExecutionKey(executionId, "trace-1"),
            101L,
            201L,
            301L,
            401L,
            501L,
            601L,
            "research-agent",
            "session-1",
            "prepare a report",
            "Follow the approved task scope.",
            new RuntimeModelConfig(
                "openai-compatible",
                "test-model",
                "https://model.example/v1",
                "credential:model-main",
                Map.of("temperature", 0.1)
            ),
            "workspace-1",
            12,
            Map.of("tools", Map.of("write_file", "approval_required")),
            Map.of("source", "test")
        );
    }

    static AgentResumeRequest resumeRequest(RuntimeResumeDecision decision) {
        return new AgentResumeRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            101L,
            201L,
            301L,
            401L,
            501L,
            "session-1",
            "reply-approval-1",
            decision,
            Map.of(
                "id", "tool-call-1",
                "name", "write_file",
                "input", Map.of("path", "report.txt", "content", "approved content"),
                "metadata", Map.of("source", "task")
            ),
            Map.of("approvedBy", 101L)
        );
    }
}
