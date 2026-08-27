package group.aitools.nhs.platform.sandbox.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装沙箱作业Claim相关的不可变数据。
 */
public record SandboxJobClaimView(
    Long jobId,
    String sourceType,
    Long ownerUserId,
    Long conversationId,
    Long taskId,
    Long runId,
    Long stepId,
    Long toolId,
    String traceId,
    String jobToken,
    String templateKey,
    String scriptLanguage,
    String scriptText,
    List<String> argv,
    String workspacePath,
    String workspaceKey,
    String workspaceAccess,
    String networkPolicy,
    List<String> allowedHosts,
    String skillManifestJson,
    String skillManifestHash,
    Integer timeoutSeconds,
    Integer memoryMb,
    Integer cpuMillis,
    Integer pidsLimit,
    Integer maxOutputBytes,
    LocalDateTime leaseUntil,
    Integer attemptNo
) {
}
