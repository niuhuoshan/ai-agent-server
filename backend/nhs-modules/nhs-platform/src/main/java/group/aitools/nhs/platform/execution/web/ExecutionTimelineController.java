package group.aitools.nhs.platform.execution.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.execution.service.ExecutionTimelineSnapshotService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供执行时间线相关的 HTTP 接口，并负责请求校验与结果返回。
 * Shared semantic execution timeline API for chat, tasks, debug and Embed. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform")
public class ExecutionTimelineController {

    private final ExecutionTimelineSnapshotService service;

    public ExecutionTimelineController(ExecutionTimelineSnapshotService service) {
        this.service = service;
    }

    /**
     * 处理会话链路追踪并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/conversations/traces/{traceId}/timeline")
    public R<ExecutionTimelineView> conversationTrace(@PathVariable String traceId) {
        return R.ok(service.conversationTrace(traceId));
    }

    /**
     * 处理{@code cached}并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/conversations/traces/{traceId}/timeline/cached")
    public R<ExecutionTimelineView> cached(@PathVariable String traceId) {
        return R.ok(service.cached(traceId));
    }

    /**
     * 处理任务Run并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @GetMapping("/tasks/{taskId}/runs/{runId}/timeline")
    public R<ExecutionTimelineView> taskRun(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId
    ) {
        return R.ok(service.taskRun(taskId, runId));
    }
}
