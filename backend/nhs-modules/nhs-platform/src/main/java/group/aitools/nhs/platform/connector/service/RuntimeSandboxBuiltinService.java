package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.platform.sandbox.service.SandboxJobQueueService;
import group.aitools.nhs.platform.sandbox.service.SandboxJobSubmission;
import group.aitools.nhs.platform.sandbox.service.SandboxSkillManifest;
import group.aitools.nhs.platform.sandbox.web.ChatCodeExecutionRequest;
import group.aitools.nhs.platform.sandbox.web.ChatCodeExecutionView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责运行时沙箱Builtin相关的业务编排与领域规则处理。
 * Routes command/process builtins into the durable external Sandbox Runner. */
@Service
public class RuntimeSandboxBuiltinService {

    private final SandboxJobQueueService sandboxQueue;
    private final ChatCodeExecutionService chatCodeExecutionService;
    private final JsonMapper jsonMapper;

    @Autowired
    public RuntimeSandboxBuiltinService(
        SandboxJobQueueService sandboxQueue,
        ChatCodeExecutionService chatCodeExecutionService,
        JsonMapper jsonMapper
    ) {
        this.sandboxQueue = sandboxQueue;
        this.chatCodeExecutionService = chatCodeExecutionService;
        this.jsonMapper = jsonMapper;
    }

    /**
 * 创建 {@code RuntimeSandboxBuiltinService} 实例并初始化所需依赖。
 * Backwards-compatible constructor for focused runtime tests. */
    public RuntimeSandboxBuiltinService(
        SandboxJobQueueService sandboxQueue,
        ChatCodeExecutionService chatCodeExecutionService
    ) {
        this(
            sandboxQueue, chatCodeExecutionService,
            JsonMapper.builder().findAndAddModules().build()
        );
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param request 请求参数
     * @param principal 当前操作主体
     * @param toolId 资源标识
     * @param builtin {@code builtin}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> execute(
        AgentRunRequest request,
        CurrentPrincipal principal,
        Long toolId,
        String builtin,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("服务账号不能执行主机命令或进程操作", HttpStatus.FORBIDDEN);
        }
        String command = command(builtin, arguments);
        if (request.conversationId() != null) {
            String resolvedWorkspaceKey = request.workspaceKey();
            if (resolvedWorkspaceKey == null || resolvedWorkspaceKey.isBlank()) {
                resolvedWorkspaceKey = "conversation-" + request.conversationId();
            }
            SandboxSkillManifest.Normalized skillManifest = SandboxSkillManifest.fromAttributes(
                request.attributes(), resolvedWorkspaceKey, jsonMapper
            );
            String workspaceKey = resolvedWorkspaceKey;
            ChatCodeExecutionView execution = chatCodeExecutionService.runAsRuntimePrincipal(
                principal,
                () -> chatCodeExecutionService.submitRuntime(new ChatCodeExecutionRequest(
                    "bash", command, String.valueOf(request.conversationId()), null,
                    skillManifest.empty() ? null : workspaceKey,
                    skillManifest.empty() ? "[]" : skillManifest.json()
                ))
            );
            return chatExecution(execution, builtin);
        }
        if (request.taskId() == null || request.runId() == null) {
            throw new ServiceException("当前运行没有可关联的沙箱任务", HttpStatus.CONFLICT);
        }
        SandboxJobQueueService.SandboxJobTicket ticket = sandboxQueue.enqueueWithRunAttributes(
            new SandboxJobSubmission(
            request.taskId(), request.runId(), request.stepId(), toolId,
            null, request.executionKey().executionId(), builtin,
            "code", List.of("/bin/sh", "-lc", command),
            relativeText(arguments, "workspace_path", "workspacePath", "."),
            enumText(arguments, "workspace_access", "workspaceAccess", "read_write",
                "read_only", "read_write"),
            enumText(arguments, "network_policy", "networkPolicy", "none", "none", "allowlist"),
            hostList(arguments),
            boundedInt(arguments, "timeout_seconds", "timeoutSeconds", 60, 1, 3600),
            boundedInt(arguments, "memory_mb", "memoryMb", 512, 64, 32768),
            boundedInt(arguments, "cpu_millis", "cpuMillis", 1000, 100, 16000),
            boundedInt(arguments, "pids_limit", "pidsLimit", 128, 16, 2048),
            boundedInt(arguments, "max_output_bytes", "maxOutputBytes", 1_048_576, 1024, 10_485_760),
            0, request.workspaceKey(), "[]"
            ), request.attributes()
        );
        return queueResult(ticket, builtin);
    }

    /**
     * 处理命令并返回对应结果。
     *
     * @param builtin {@code builtin}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private String command(String builtin, Map<String, Object> arguments) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("exec_command".equals(builtin)) {
            String command = text(arguments, "command", "cmd");
            if (command.length() > 32_768) {
                throw new ServiceException("命令超过 32KB 限制", HttpStatus.BAD_REQUEST);
            }
            return command;
        }
        if ("list_process".equals(builtin)) {
            return "ps -eo pid,ppid,stat,comm,%cpu,%mem --sort=-%cpu | head -n 101";
        }
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        long pid = positiveLong(first(arguments, "pid", "process_id", "processId"), "进程ID");
        if (!SetLike.ALLOWED_PROCESS_ACTIONS.contains(action)) {
            throw new ServiceException("进程操作仅支持 list、terminate 或 kill", HttpStatus.BAD_REQUEST);
        }
        if (pid == 1 || pid == ProcessHandle.current().pid()) {
            throw new ServiceException("禁止操作平台保护进程", HttpStatus.FORBIDDEN);
        }
        return switch (action) {
            case "list" -> "ps -p " + pid + " -o pid,ppid,stat,comm,%cpu,%mem";
            case "terminate" -> "kill -TERM -- " + pid;
            case "kill" -> "kill -KILL -- " + pid;
            default -> throw new ServiceException("进程操作无效", HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * 处理对话执行并返回对应结果。
     *
     * @param execution 执行参数
     * @param builtin {@code builtin}参数
     * @return 处理结果
     */
    private Map<String, Object> chatExecution(ChatCodeExecutionView execution, String builtin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("builtin", builtin);
        result.put("execution_id", execution.executionId());
        result.put("conversation_id", execution.conversationId());
        result.put("trace_id", execution.traceId());
        result.put("status", execution.status());
        result.put("queued_at", execution.queuedAt());
        result.put("async", true);
        return result;
    }

    /**
     * 处理queue结果并返回对应结果。
     *
     * @param ticket {@code ticket}参数
     * @param builtin {@code builtin}参数
     * @return 处理结果
     */
    private Map<String, Object> queueResult(SandboxJobQueueService.SandboxJobTicket ticket, String builtin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("builtin", builtin);
        result.put("job_id", ticket.jobId());
        result.put("trace_id", ticket.traceId());
        result.put("status", ticket.status());
        result.put("async", true);
        return result;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param names 名称
     * @return 处理结果
     */
    private String text(Map<String, Object> arguments, String... names) {
        Object value = first(arguments, names);
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0) {
            throw new ServiceException("命令参数无效", HttpStatus.BAD_REQUEST);
        }
        return text.strip();
    }

    /**
     * 处理{@code relativeText}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param first {@code first}参数
     * @param second {@code second}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String relativeText(Map<String, Object> arguments, String first, String second, String fallback) {
        Object value = first(arguments, first, second);
        if (value == null) return fallback;
        String text = text(arguments, first, second);
        if (text.length() > 512 || text.startsWith("/") || text.startsWith("\\") || text.contains(":")) {
            throw new ServiceException("工作区路径无效", HttpStatus.BAD_REQUEST);
        }
        java.nio.file.Path path = java.nio.file.Path.of(text).normalize();
        if (path.startsWith("..")) throw new ServiceException("工作区路径越界", HttpStatus.BAD_REQUEST);
        return path.toString().isBlank() ? fallback : path.toString();
    }

    /**
     * 处理{@code enumText}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param first {@code first}参数
     * @param second {@code second}参数
     * @param fallback {@code fallback}参数
     * @param values {@code values}参数
     * @return 处理结果
     */
    private String enumText(Map<String, Object> arguments, String first, String second, String fallback, String... values) {
        Object raw = first(arguments, first, second);
        String value = raw == null ? fallback : text(arguments, first, second).toLowerCase(java.util.Locale.ROOT);
        for (String allowed : values) if (allowed.equals(value)) return value;
        throw new ServiceException("沙箱策略参数无效", HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code hostList}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<String> hostList(Map<String, Object> arguments) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object raw = first(arguments, "allowed_hosts", "allowedHosts");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > 32) {
            throw new ServiceException("网络白名单无效", HttpStatus.BAD_REQUEST);
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String host) || host.isBlank()) {
                throw new ServiceException("网络白名单无效", HttpStatus.BAD_REQUEST);
            }
            result.add(host.strip());
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code boundedInt}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param first {@code first}参数
     * @param second {@code second}参数
     * @param fallback {@code fallback}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private int boundedInt(Map<String, Object> arguments, String first, String second, int fallback, int min, int max) {
        Object raw = first(arguments, first, second);
        if (raw == null) return fallback;
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()
            || number.intValue() < min || number.intValue() > max) {
            throw new ServiceException("沙箱资源参数无效", HttpStatus.BAD_REQUEST);
        }
        return number.intValue();
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return number.longValue();
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param names 名称
     * @return 处理结果
     */
    private Object first(Map<String, Object> arguments, String... names) {
        if (arguments == null) return null;
        for (String name : names) if (arguments.containsKey(name)) return arguments.get(name);
        return null;
    }

    /**
     * 表示{@code SetLike}相关的领域对象。
     */
    private static final class SetLike {
        private static final java.util.Set<String> ALLOWED_PROCESS_ACTIONS =
            java.util.Set.of("list", "terminate", "kill");
    }
}
