package group.aitools.nhs.platform.openapi.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.artifact.service.AcceptanceApplicationService;
import group.aitools.nhs.platform.artifact.service.ArtifactApplicationService;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionRequest;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionResult;
import group.aitools.nhs.platform.artifact.web.ArtifactView;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService.ApiCallContext;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 提供Open任务接口相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@RestController
@RequestMapping("/open/v1/tasks")
public class OpenTaskApiController {

    private static final Set<String> APP_TYPES = Set.of("open_api", "internal");
    private final MachineApiGatewayService gateway;
    private final TaskApplicationService taskService;
    private final TaskRunApplicationService runService;
    private final ExecutionEventQueryService eventService;
    private final ArtifactApplicationService artifactService;
    private final AcceptanceApplicationService acceptanceService;

    /**
     * 创建 {@code OpenTaskApiController} 实例并初始化所需依赖。
     *
     * @param gateway {@code gateway}参数
     * @param taskService 任务Service参数
     * @param runService {@code runService}参数
     * @param eventService 事件Service参数
     * @param artifactService 制品Service参数
     * @param acceptanceService 验收Service参数
     */
    public OpenTaskApiController(
        MachineApiGatewayService gateway,
        TaskApplicationService taskService,
        TaskRunApplicationService runService,
        ExecutionEventQueryService eventService,
        ArtifactApplicationService artifactService,
        AcceptanceApplicationService acceptanceService
    ) {
        this.gateway = gateway;
        this.taskService = taskService;
        this.runService = runService;
        this.eventService = eventService;
        this.artifactService = artifactService;
        this.acceptanceService = acceptanceService;
    }

    /**
     * 创建并保存任务。
     *
     * @param authorization 授权参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public OpenApiResponse<TaskMutationResult> createTask(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody CreateTaskRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "tasks:create", "task_create", "POST",
            "task", null, contentLength == null ? 0 : contentLength
        );
        try {
            TaskMutationResult result = taskService.createAs(call.authenticated().principal(), request);
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 创建并保存{@code Run}。
     *
     * @param taskId 资源标识
     * @param authorization 授权参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{taskId}/runs")
    public OpenApiResponse<TaskRunActionResult> createRun(
        @PathVariable @Positive Long taskId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody OpenCreateRunRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "tasks:run", "task_run_create", "POST",
            "task", taskId, contentLength == null ? 0 : contentLength
        );
        try {
            TaskRunActionResult created = runService.createAs(
                call.authenticated().principal(), taskId, request.taskVersionId(),
                new CreateTaskRunRequest(request.idempotencyKey(), request.input())
            );
            TaskRunActionResult result = request.startImmediately()
                ? runService.startAs(
                    call.authenticated().principal(), taskId, created.run().id(), request.taskVersionId()
                ) : created;
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param authorization 授权参数
     * @return 处理结果
     */
    @GetMapping("/{taskId}/runs/{runId}")
    public OpenApiResponse<TaskRunView> run(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "tasks:read", "task_run_get", "GET",
            "task", taskId, 0
        );
        try {
            TaskRunView result = runService.getAs(call.authenticated().principal(), taskId, runId);
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code events}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param authorization 授权参数
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{taskId}/runs/{runId}/events")
    public OpenApiResponse<List<ExecutionEventView>> events(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "events:read", "task_run_events", "GET",
            "task", taskId, 0
        );
        try {
            List<ExecutionEventView> result = eventService.listTaskRunAs(
                call.authenticated().principal(), taskId, runId, cursor, limit
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code artifacts}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param authorization 授权参数
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{taskId}/artifacts")
    public OpenApiResponse<List<ArtifactView>> artifacts(
        @PathVariable @Positive Long taskId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestParam(required = false) @Positive Long runId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "artifacts:read", "task_artifacts", "GET",
            "task", taskId, 0
        );
        try {
            List<ArtifactView> result = artifactService.listAs(
                call.authenticated().principal(), taskId, runId, limit
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code accept}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param authorization 授权参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{taskId}/runs/{runId}/acceptance")
    public OpenApiResponse<AcceptanceDecisionResult> accept(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody AcceptanceDecisionRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "acceptance:write", "task_acceptance", "POST",
            "task", taskId, contentLength == null ? 0 : contentLength
        );
        try {
            AcceptanceDecisionResult result = acceptanceService.decideAs(
                call.authenticated().principal(), taskId, runId, request
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }
}
