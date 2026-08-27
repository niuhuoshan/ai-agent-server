package group.aitools.nhs.platform.execution.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台任务Run相关的 HTTP 接口，并负责请求校验与结果返回。
 * Durable TaskRun entrypoints; event streaming remains on ExecutionEventController. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/tasks/{taskId}/runs")
public class PlatformTaskRunController {

    private final TaskRunApplicationService runService;

    public PlatformTaskRunController(TaskRunApplicationService runService) {
        this.runService = runService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<TaskRunActionResult> create(
        @PathVariable @Positive Long taskId,
        @Valid @RequestBody CreateTaskRunRequest request
    ) {
        return R.ok(runService.create(taskId, request));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<TaskRunView>> list(
        @PathVariable @Positive Long taskId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(runService.list(taskId, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{runId}")
    public R<TaskRunView> get(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId
    ) {
        return R.ok(runService.get(taskId, runId));
    }

    /**
     * 处理{@code steps}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{runId}/steps")
    public R<List<RunStepView>> steps(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId
    ) {
        return R.ok(runService.steps(taskId, runId));
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{runId}/start")
    public R<TaskRunActionResult> start(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId
    ) {
        return R.ok(runService.start(taskId, runId));
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{runId}/cancel")
    public R<TaskRunActionResult> cancel(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @Valid @RequestBody(required = false) CancelTaskRunRequest request
    ) {
        return R.ok(runService.cancel(taskId, runId, request == null ? null : request.reason()));
    }

    /**
     * 处理{@code pause}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{runId}/pause")
    public R<TaskRunActionResult> pause(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @Valid @RequestBody(required = false) PauseTaskRunRequest request
    ) {
        return R.ok(runService.pause(taskId, runId, request == null ? null : request.reason()));
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{runId}/resume")
    public R<TaskRunActionResult> resume(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId
    ) {
        return R.ok(runService.resume(taskId, runId));
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{runId}/retry")
    public R<TaskRunActionResult> retry(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @Valid @RequestBody RetryTaskRunRequest request
    ) {
        return R.ok(runService.retry(taskId, runId, request));
    }
}
