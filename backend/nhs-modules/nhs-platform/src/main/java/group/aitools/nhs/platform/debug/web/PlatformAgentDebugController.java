package group.aitools.nhs.platform.debug.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.debug.service.AgentDebugApplicationService;
import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 提供平台智能体Debug相关的 HTTP 接口，并负责请求校验与结果返回。
 * Private Agent Debug / Playground endpoints over the governed durable runtime. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/agent-debug", "/api/portal/agent-debug"})
public class PlatformAgentDebugController {

    private final AgentDebugApplicationService service;
    private final ExecutionEventSseService sseService;

    public PlatformAgentDebugController(
        AgentDebugApplicationService service,
        ExecutionEventSseService sseService
    ) {
        this.service = service;
        this.sseService = sseService;
    }

    /**
     * 处理{@code options}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/options")
    public R<List<AgentDebugOptionView>> options() {
        return R.ok(service.options());
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/runs")
    public R<List<AgentDebugRunSummaryView>> list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.list(limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runs")
    public R<AgentDebugRunDetailView> create(
        @Valid @RequestBody CreateAgentDebugRunRequest request
    ) {
        return R.ok(service.create(request));
    }

    /**
     * 获取{@code get}。
     *
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    @GetMapping("/runs/{debugRunId}")
    public R<AgentDebugRunDetailView> get(@PathVariable @Positive Long debugRunId) {
        return R.ok(service.get(debugRunId));
    }

    /**
     * 处理{@code events}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/runs/{debugRunId}/events")
    public R<List<ExecutionEventView>> events(
        @PathVariable @Positive Long debugRunId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.events(debugRunId, cursor, limit));
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param cursor {@code cursor}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @GetMapping(
        value = "/runs/{debugRunId}/events/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream(
        @PathVariable @Positive Long debugRunId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.stream(service.eventReader(debugRunId), resumeCursor(cursor, lastEventId));
    }

    /**
     * 处理{@code stop}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runs/{debugRunId}/stop")
    public R<AgentDebugRunDetailView> stop(
        @PathVariable @Positive Long debugRunId,
        @Valid @RequestBody(required = false) StopAgentDebugRunRequest request
    ) {
        return R.ok(service.stop(
            debugRunId, request == null ? null : request.reason()
        ));
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    @PostMapping("/runs/{debugRunId}/resume")
    public R<AgentDebugRunDetailView> resume(@PathVariable @Positive Long debugRunId) {
        return R.ok(service.resume(debugRunId));
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runs/{debugRunId}/retry")
    public R<AgentDebugRunDetailView> retry(
        @PathVariable @Positive Long debugRunId,
        @Valid @RequestBody RetryAgentDebugRunRequest request
    ) {
        return R.ok(service.retry(debugRunId, request));
    }

    /**
     * 处理{@code resumeCursor}并返回对应结果。
     *
     * @param queryCursor 查询Cursor参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    static long resumeCursor(long queryCursor, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return queryCursor;
        }
        try {
            long headerCursor = Long.parseLong(lastEventId.strip());
            if (headerCursor < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return Math.max(queryCursor, headerCursor);
        } catch (NumberFormatException exception) {
            throw new ServiceException("Last-Event-ID无效", HttpStatus.BAD_REQUEST);
        }
    }
}
