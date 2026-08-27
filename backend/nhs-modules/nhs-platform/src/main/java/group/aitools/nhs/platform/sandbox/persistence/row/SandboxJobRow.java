package group.aitools.nhs.platform.sandbox.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示沙箱作业相关的领域对象。
 */
@Data
public class SandboxJobRow {
    private Long id;
    private String sourceType;
    private Long ownerUserId;
    private Long conversationId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private Long toolId;
    private String externalReplyId;
    private String toolCallId;
    private String toolName;
    private String traceId;
    private String requestHash;
    private String templateKey;
    private String scriptLanguage;
    private String scriptText;
    private String argvJson;
    private String workspacePath;
    private String workspaceKey;
    private String workspaceAccess;
    private String networkPolicy;
    private String allowedHostsJson;
    private String skillManifestJson;
    private String skillManifestHash;
    private Integer timeoutSeconds;
    private Integer memoryMb;
    private Integer cpuMillis;
    private Integer pidsLimit;
    private Integer maxOutputBytes;
    private Long outputSequence;
    private Integer outputBytes;
    private Boolean outputTruncated;
    private String status;
    private Integer priority;
    private Long assignedRunnerId;
    private String jobTokenHash;
    private LocalDateTime leaseUntil;
    private Integer attemptNo;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime resumeDispatchedAt;
    private Integer exitCode;
    private String stdoutText;
    private String stderrText;
    private String outputManifestJson;
    private String resourceUsageJson;
    private String failureCode;
    private String failureMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
