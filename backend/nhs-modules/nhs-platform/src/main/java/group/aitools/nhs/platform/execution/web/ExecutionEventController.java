package group.aitools.nhs.platform.execution.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 提供执行事件相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform")
public class ExecutionEventController {

    private final ExecutionEventQueryService queryService;
    private final ExecutionEventSseService sseService;

    /**
     * 创建 {@code ExecutionEventController} 实例并初始化所需依赖。
     *
     * @param queryService 查询Service参数
     * @param sseService {@code sseService}参数
     */
    public ExecutionEventController(
        ExecutionEventQueryService queryService,
        ExecutionEventSseService sseService
    ) {
        this.queryService = queryService;
        this.sseService = sseService;
    }

    /**
     * 处理会话Events并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/conversations/{conversationId}/events")
    public R<List<ExecutionEventView>> conversationEvents(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(queryService.listConversation(conversationId, cursor, limit));
    }

    /**
     * 处理会话事件Stream并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param cursor {@code cursor}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @GetMapping(
        value = "/conversations/{conversationId}/events/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter conversationEventStream(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.stream(
            queryService.conversationReader(conversationId),
            resumeCursor(cursor, lastEventId)
        );
    }

    /**
     * 处理任务RunEvents并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/tasks/{taskId}/runs/{runId}/events")
    public R<List<ExecutionEventView>> taskRunEvents(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(queryService.listTaskRun(taskId, runId, cursor, limit));
    }

    /**
     * 处理任务Run事件Stream并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param cursor {@code cursor}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @GetMapping(
        value = "/tasks/{taskId}/runs/{runId}/events/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter taskRunEventStream(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.stream(
            queryService.taskRunReader(taskId, runId),
            resumeCursor(cursor, lastEventId)
        );
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
