package group.aitools.nhs.platform.sandbox.service;

import java.util.List;

/**
 * 封装沙箱作业Submission相关的不可变数据。
 */
public record SandboxJobSubmission(
    Long taskId,
    Long runId,
    Long stepId,
    Long toolId,
    String externalReplyId,
    String toolCallId,
    String toolName,
    String templateKey,
    List<String> argv,
    String workspacePath,
    String workspaceAccess,
    String networkPolicy,
    List<String> allowedHosts,
    int timeoutSeconds,
    int memoryMb,
    int cpuMillis,
    int pidsLimit,
    int maxOutputBytes,
    int priority,
    String workspaceKey,
    String skillManifestJson
) {

    /**
 * 创建 {@code SandboxJobSubmission} 实例并初始化所需依赖。
 * Backwards-compatible constructor for callers that do not carry a Skill snapshot. */
    public SandboxJobSubmission(
        Long taskId,
        Long runId,
        Long stepId,
        Long toolId,
        String externalReplyId,
        String toolCallId,
        String toolName,
        String templateKey,
        List<String> argv,
        String workspacePath,
        String workspaceAccess,
        String networkPolicy,
        List<String> allowedHosts,
        int timeoutSeconds,
        int memoryMb,
        int cpuMillis,
        int pidsLimit,
        int maxOutputBytes,
        int priority
    ) {
        this(
            taskId, runId, stepId, toolId, externalReplyId, toolCallId, toolName,
            templateKey, argv, workspacePath, workspaceAccess, networkPolicy, allowedHosts,
            timeoutSeconds, memoryMb, cpuMillis, pidsLimit, maxOutputBytes, priority,
            null, "[]"
        );
    }

    /**
     * 处理with技能Manifest并返回对应结果。
     *
     * @param manifestJson {@code manifestJson}参数
     * @return 处理结果
     */
    public SandboxJobSubmission withSkillManifest(String manifestJson) {
        return new SandboxJobSubmission(
            taskId, runId, stepId, toolId, externalReplyId, toolCallId, toolName,
            templateKey, argv, workspacePath, workspaceAccess, networkPolicy, allowedHosts,
            timeoutSeconds, memoryMb, cpuMillis, pidsLimit, maxOutputBytes, priority,
            workspaceKey, manifestJson
        );
    }
}
