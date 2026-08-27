package group.aitools.nhs.platform.sandbox.web;

/**
 * 封装沙箱RunnerRegistration相关的不可变数据。
 */
public record SandboxRunnerRegistrationView(
    Long runnerId,
    String runnerKey,
    String runnerSecret
) {
}
