package group.aitools.nhs.platform.sandbox.web;

import java.util.Set;

/**
 * 封装沙箱RunnerHeartbeat相关的不可变数据。
 */
public record SandboxRunnerHeartbeatRequest(
    Set<String> capabilities,
    Integer maxConcurrency,
    Integer activeJobCount,
    String version
) {
}
