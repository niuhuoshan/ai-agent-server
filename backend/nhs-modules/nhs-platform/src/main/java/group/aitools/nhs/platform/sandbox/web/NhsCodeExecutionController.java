package group.aitools.nhs.platform.sandbox.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionSseService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供NhsCode执行相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/chat/code-executions")
public class NhsCodeExecutionController {

    private final ChatCodeExecutionService executionService;
    private final ChatCodeExecutionSseService sseService;

    /**
     * 创建 {@code NhsCodeExecutionController} 实例并初始化所需依赖。
     *
     * @param executionService 执行Service参数
     * @param sseService {@code sseService}参数
     */
    public NhsCodeExecutionController(
        ChatCodeExecutionService executionService,
        ChatCodeExecutionSseService sseService
    ) {
        this.executionService = executionService;
        this.sseService = sseService;
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param request 请求参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(
        @RequestBody ChatCodeExecutionRequest request,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        if (request == null) {
            throw new ServiceException("代码执行请求不能为空", HttpStatus.BAD_REQUEST);
        }
        long cursor = resumeCursor(0, lastEventId);
        ChatCodeExecutionView execution;
        if (request.execution_id() == null || request.execution_id().isBlank()) {
            if (cursor > 0) {
                throw new ServiceException("新执行不能携带非零Last-Event-ID", HttpStatus.BAD_REQUEST);
            }
            execution = executionService.submit(request);
        } else {
            Long executionId = positiveId(request.execution_id());
            execution = executionService.status(executionId);
        }
        return sseService.stream(
            executionService.reader(executionId(execution), request.conversation_id()),
            cursor
        );
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param executionId 资源标识
     * @param conversation_id 资源标识
     * @param cursor {@code cursor}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @GetMapping(value = "/{executionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
        @PathVariable @Positive Long executionId,
        @RequestParam(required = false) String conversation_id,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.stream(
            executionService.reader(executionId, conversation_id),
            resumeCursor(cursor, lastEventId)
        );
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param executionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{executionId}")
    public R<ChatCodeExecutionView> status(@PathVariable @Positive Long executionId) {
        return R.ok(executionService.status(executionId));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param conversation_id 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<java.util.List<ChatCodeExecutionView>> list(
        @RequestParam String conversation_id,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return R.ok(executionService.list(conversation_id, limit));
    }

    /**
     * 处理{@code stop}并返回对应结果。
     *
     * @param executionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{executionId}/stop")
    public R<ChatCodeExecutionView> stop(
        @PathVariable @Positive Long executionId,
        @RequestBody StopChatCodeExecutionRequest request
    ) {
        if (request == null) {
            throw new ServiceException("停止请求不能为空", HttpStatus.BAD_REQUEST);
        }
        return R.ok(executionService.cancel(executionId, request.conversation_id()));
    }

    /**
 * 处理{@code serviceError}并返回对应结果。
 * Streaming callers must receive a real HTTP error before an SSE response is committed. */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> serviceError(ServiceException exception) {
        int status = exception.getCode() == null ? 500 : exception.getCode();
        if (status < 400 || status > 599) {
            status = 500;
        }
        String message = exception.getMessage() == null ? "代码执行请求失败" : exception.getMessage();
        String errorCode = message.startsWith("sandbox_unavailable")
            ? "sandbox_unavailable" : "code_execution_rejected";
        if (message.startsWith(errorCode + ":")) {
            message = message.substring(errorCode.length() + 1).strip();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", errorCode);
        body.put("message", message);
        body.put("detail", message);
        return ResponseEntity.status(status).body(body);
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
            long header = Long.parseLong(lastEventId.strip());
            if (header < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return Math.max(queryCursor, header);
        } catch (NumberFormatException exception) {
            throw new ServiceException("Last-Event-ID无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理执行Id并返回对应结果。
     *
     * @param execution 执行参数
     * @return 处理结果
     */
    private Long executionId(ChatCodeExecutionView execution) {
        return positiveId(execution.executionId());
    }

    /**
     * 处理{@code positiveId}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long positiveId(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.strip());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ServiceException("执行ID无效", HttpStatus.BAD_REQUEST);
        }
    }
}
