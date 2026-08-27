package group.aitools.nhs.sandbox.runner.client;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示沙箱Protocol相关的领域对象。
 */
public final class SandboxProtocol {

    /**
     * 创建 {@code SandboxProtocol} 实例并初始化所需依赖。
     */
    private SandboxProtocol() {
    }

    /**
     * 封装{@code Registration}相关的不可变数据。
     */
    public record RegistrationRequest(
        String runnerKey,
        String name,
        Set<String> capabilities,
        Integer maxConcurrency,
        String version
    ) {
    }

    /**
     * 封装{@code Registration}相关的不可变数据。
     */
    public record Registration(
        Long runnerId,
        String runnerKey,
        String runnerSecret
    ) {
    }

    /**
     * 封装{@code Heartbeat}相关的不可变数据。
     */
    public record Heartbeat(
        Set<String> capabilities,
        Integer maxConcurrency,
        Integer activeJobCount,
        String version
    ) {
    }

    /**
     * 封装Claimed作业相关的不可变数据。
     */
    public record ClaimedJob(
        Long jobId,
        Long taskId,
        Long runId,
        Long stepId,
        Long toolId,
        String traceId,
        String jobToken,
        String templateKey,
        List<String> argv,
        String workspacePath,
        String workspaceAccess,
        String networkPolicy,
        List<String> allowedHosts,
        Integer timeoutSeconds,
        Integer memoryMb,
        Integer cpuMillis,
        Integer pidsLimit,
        Integer maxOutputBytes,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime leaseUntil,
        Integer attemptNo,
        String sourceType,
        Long ownerUserId,
        Long conversationId,
        String scriptLanguage,
        String scriptText,
        String workspaceKey,
        String skillManifestJson,
        String skillManifestHash
    ) {
        /**
         * 创建 {@code ClaimedJob} 实例并初始化所需依赖。
         *
         * @param jobId 资源标识
         * @param taskId 资源标识
         * @param runId 资源标识
         * @param stepId 资源标识
         * @param toolId 资源标识
         * @param traceId 资源标识
         * @param jobToken 作业令牌参数
         * @param templateKey 模板Key参数
         * @param argv {@code argv}参数
         * @param workspacePath 工作空间Path参数
         * @param workspaceAccess 工作空间Access参数
         * @param networkPolicy network策略参数
         * @param allowedHosts {@code allowedHosts}参数
         * @param timeoutSeconds {@code timeoutSeconds}参数
         * @param memoryMb 记忆Mb参数
         * @param cpuMillis {@code cpuMillis}参数
         * @param pidsLimit 数量上限
         * @param maxOutputBytes {@code maxOutputBytes}参数
         * @param leaseUntil {@code leaseUntil}参数
         * @param attemptNo {@code attemptNo}参数
         * @param sourceType 业务类型
         * @param ownerUserId 资源标识
         * @param conversationId 资源标识
         * @param scriptLanguage {@code scriptLanguage}参数
         * @param scriptText 待处理内容
         */
        public ClaimedJob(
            Long jobId,
            Long taskId,
            Long runId,
            Long stepId,
            Long toolId,
            String traceId,
            String jobToken,
            String templateKey,
            List<String> argv,
            String workspacePath,
            String workspaceAccess,
            String networkPolicy,
            List<String> allowedHosts,
            Integer timeoutSeconds,
            Integer memoryMb,
            Integer cpuMillis,
            Integer pidsLimit,
            Integer maxOutputBytes,
            LocalDateTime leaseUntil,
            Integer attemptNo,
            String sourceType,
            Long ownerUserId,
            Long conversationId,
            String scriptLanguage,
            String scriptText
        ) {
            this(
                jobId, taskId, runId, stepId, toolId, traceId, jobToken, templateKey,
                argv, workspacePath, workspaceAccess, networkPolicy, allowedHosts,
                timeoutSeconds, memoryMb, cpuMillis, pidsLimit, maxOutputBytes,
                leaseUntil, attemptNo, sourceType, ownerUserId, conversationId,
                scriptLanguage, scriptText, null, null, null
            );
        }

        /**
         * 创建 {@code ClaimedJob} 实例并初始化所需依赖。
         *
         * @param jobId 资源标识
         * @param taskId 资源标识
         * @param runId 资源标识
         * @param stepId 资源标识
         * @param toolId 资源标识
         * @param traceId 资源标识
         * @param jobToken 作业令牌参数
         * @param templateKey 模板Key参数
         * @param argv {@code argv}参数
         * @param workspacePath 工作空间Path参数
         * @param workspaceAccess 工作空间Access参数
         * @param networkPolicy network策略参数
         * @param allowedHosts {@code allowedHosts}参数
         * @param timeoutSeconds {@code timeoutSeconds}参数
         * @param memoryMb 记忆Mb参数
         * @param cpuMillis {@code cpuMillis}参数
         * @param pidsLimit 数量上限
         * @param maxOutputBytes {@code maxOutputBytes}参数
         * @param leaseUntil {@code leaseUntil}参数
         * @param attemptNo {@code attemptNo}参数
         */
        public ClaimedJob(
            Long jobId,
            Long taskId,
            Long runId,
            Long stepId,
            Long toolId,
            String traceId,
            String jobToken,
            String templateKey,
            List<String> argv,
            String workspacePath,
            String workspaceAccess,
            String networkPolicy,
            List<String> allowedHosts,
            Integer timeoutSeconds,
            Integer memoryMb,
            Integer cpuMillis,
            Integer pidsLimit,
            Integer maxOutputBytes,
            LocalDateTime leaseUntil,
            Integer attemptNo
        ) {
            this(
                jobId, taskId, runId, stepId, toolId, traceId, jobToken, templateKey,
                argv, workspacePath, workspaceAccess, networkPolicy, allowedHosts,
                timeoutSeconds, memoryMb, cpuMillis, pidsLimit, maxOutputBytes,
                leaseUntil, attemptNo, null, null, null, null, null, null, null, null
            );
        }
    }

    /**
     * 封装{@code OutputChunk}相关的不可变数据。
     */
    public record OutputChunk(
        Long sequenceNo,
        String stream,
        String content
    ) {
    }

    /**
     * 封装{@code Completion}相关的不可变数据。
     */
    public record Completion(
        Boolean succeeded,
        Integer exitCode,
        String stdout,
        String stderr,
        List<Map<String, Object>> outputManifest,
        Map<String, Object> resourceUsage,
        String failureCode,
        String failureMessage
    ) {
    }

    /**
     * 封装平台相关的不可变数据。
     */
    public record PlatformResponse<T>(Integer code, String msg, T data) {
    }
}
