package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.Map;

/**
 * 负责沙箱作业Queue相关的业务编排与领域规则处理。
 */
@Service
public class SandboxJobQueueService {

    private static final Pattern WORKSPACE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final SandboxRunnerMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final SandboxPolicyValidator validator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code SandboxJobQueueService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param validator {@code validator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public SandboxJobQueueService(
        SandboxRunnerMapper mapper,
        PlatformIdGenerator idGenerator,
        SandboxPolicyValidator validator,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code enqueue}并返回对应结果。
     *
     * @param submission {@code submission}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxJobTicket enqueue(SandboxJobSubmission submission) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requirePositive(submission.taskId(), "任务ID");
        requirePositive(submission.runId(), "运行ID");
        requirePositive(submission.toolId(), "工具ID");
        if (submission.stepId() != null) {
            requirePositive(submission.stepId(), "步骤ID");
        }
        SandboxPolicyValidator.ValidatedPolicy policy = validator.validate(
            submission.templateKey(), submission.argv(), submission.workspacePath(),
            submission.workspaceAccess(), submission.networkPolicy(), submission.allowedHosts(),
            submission.timeoutSeconds(), submission.memoryMb(), submission.cpuMillis(),
            submission.pidsLimit(), submission.maxOutputBytes(), submission.priority()
        );
        String workspaceKey = normalizeWorkspaceKey(submission.workspaceKey());
        SandboxSkillManifest.Normalized skillManifest = SandboxSkillManifest.fromJson(
            submission.skillManifestJson(), jsonMapper
        );
        if (!skillManifest.empty() && workspaceKey == null) {
            throw new ServiceException("包含 Skill 的沙箱作业必须绑定工作区", HttpStatus.BAD_REQUEST);
        }
        if (!skillManifest.empty() && !workspaceKey.equals(skillManifest.workspaceKey())) {
            throw new ServiceException("Skill manifest 与工作区标识不一致", HttpStatus.BAD_REQUEST);
        }
        Long id = idGenerator.nextId();
        String traceId = ContentHashing.sha256(idGenerator.nextUuid());
        String argvJson = jsonMapper.writeValueAsString(policy.argv());
        String allowedHostsJson = jsonMapper.writeValueAsString(policy.allowedHosts());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("taskId", submission.taskId());
        canonical.put("runId", submission.runId());
        canonical.put("stepId", submission.stepId());
        canonical.put("toolId", submission.toolId());
        canonical.put("externalReplyId", submission.externalReplyId());
        canonical.put("toolCallId", submission.toolCallId());
        canonical.put("toolName", submission.toolName());
        canonical.put("traceId", traceId);
        canonical.put("template", policy.templateKey());
        canonical.put("argv", policy.argv());
        canonical.put("workspace", policy.workspacePath());
        canonical.put("workspaceKey", workspaceKey);
        canonical.put("skillManifest", skillManifest.entries());
        canonical.put("workspaceAccess", policy.workspaceAccess());
        canonical.put("network", policy.networkPolicy());
        canonical.put("allowedHosts", policy.allowedHosts());
        canonical.put("timeoutSeconds", policy.timeoutSeconds());
        canonical.put("memoryMb", policy.memoryMb());
        canonical.put("cpuMillis", policy.cpuMillis());
        canonical.put("pidsLimit", policy.pidsLimit());
        canonical.put("maxOutputBytes", policy.maxOutputBytes());
        String requestHash = ContentHashing.sha256(jsonMapper.writeValueAsString(canonical));
        LocalDateTime now = utcNow();
        if (mapper.insertJobWithManifest(
            id, submission.taskId(), submission.runId(), submission.stepId(), submission.toolId(),
            optionalExternalText(submission.externalReplyId(), 128, "外部执行回复ID"),
            optionalExternalText(submission.toolCallId(), 128, "工具调用ID"),
            optionalExternalText(submission.toolName(), 160, "工具名称"),
            traceId, requestHash, policy.templateKey(), argvJson, policy.workspacePath(),
            workspaceKey, policy.workspaceAccess(), policy.networkPolicy(), allowedHostsJson,
            skillManifest.json(), skillManifest.hash(),
            policy.timeoutSeconds(), policy.memoryMb(), policy.cpuMillis(), policy.pidsLimit(),
            policy.maxOutputBytes(), policy.priority(), now
        ) != 1) {
            throw new ServiceException("沙箱作业入队冲突", HttpStatus.CONFLICT);
        }
        return new SandboxJobTicket(id, traceId, "queued", now);
    }

    /**
 * 处理{@code enqueueWithRunAttributes}并返回对应结果。
 * Adds the immutable run binding snapshot before queue validation and hashing. */
    public SandboxJobTicket enqueueWithRunAttributes(
        SandboxJobSubmission submission,
        Map<String, Object> attributes
    ) {
        SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.fromAttributes(
            attributes, submission.workspaceKey(), jsonMapper
        );
        return enqueue(submission.withSkillManifest(manifest.json()));
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param jobId 资源标识
     * @return 处理结果
     */
    public SandboxJobStatus status(Long jobId) {
        SandboxJobRow row = mapper.selectJob(jobId);
        if (row == null) {
            throw new ServiceException("沙箱作业不存在", HttpStatus.NOT_FOUND);
        }
        return new SandboxJobStatus(
            row.getId(), row.getTraceId(), row.getStatus(), row.getExitCode(),
            row.getStdoutText(), row.getStderrText(), row.getFailureCode(),
            row.getFailureMessage(), row.getCreatedAt(), row.getStartedAt(), row.getFinishedAt()
        );
    }

    /**
     * 校验{@code Positive}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     */
    private void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code optionalExternalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalExternalText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength
            || normalized.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理normalize工作空间Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeWorkspaceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128 || !WORKSPACE_KEY.matcher(normalized).matches()
            || ".".equals(normalized) || "..".equals(normalized)) {
            throw new ServiceException("工作区标识无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 封装沙箱作业Ticket相关的不可变数据。
     */
    public record SandboxJobTicket(Long jobId, String traceId, String status, LocalDateTime queuedAt) {
    }

    /**
     * 封装沙箱作业Status相关的不可变数据。
     */
    public record SandboxJobStatus(
        Long jobId,
        String traceId,
        String status,
        Integer exitCode,
        String stdout,
        String stderr,
        String failureCode,
        String failureMessage,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
    ) {
    }
}
