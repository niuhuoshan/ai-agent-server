package group.aitools.nhs.platform.sandbox.web;

import java.util.Set;

/**
 * 封装Register沙箱Runner相关的不可变数据。
 */
public record RegisterSandboxRunnerRequest(
    String runnerKey,
    String name,
    Set<String> capabilities,
    Integer maxConcurrency,
    String version
) {
}
