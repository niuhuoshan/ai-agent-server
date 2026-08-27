package group.aitools.nhs.platform.nhs.portal.chatbi;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提供门户对话BI查询相关的 HTTP 接口，并负责请求校验与结果返回。
 * Natural-language ChatBI query and owner-only history endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/chatbi")
public class PortalChatBIQueryController {

    private final PortalChatBIQueryService service;

    public PortalChatBIQueryController(PortalChatBIQueryService service) {
        this.service = service;
    }

    /**
     * 获取查询。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/queries")
    public R<Map<String, Object>> query(@Valid @RequestBody QueryRequest request) {
        return R.ok(service.query(toServiceRequest(request)));
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping(value = "/queries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(15).toMillis());
        AtomicLong sequence = new AtomicLong();
        PortalChatBIProgressSink sink = event -> send(emitter, sequence.incrementAndGet(), event);
        Thread.ofVirtual().name("chatbi-query-stream").start(() -> {
            try {
                Map<String, Object> result = service.query(toServiceRequest(request), sink);
                send(emitter, sequence.incrementAndGet(), Map.of(
                    "type", "chatbi_result", "data", result
                ));
                done(emitter, sequence.incrementAndGet());
                emitter.complete();
            } catch (RuntimeException exception) {
                try {
                    send(emitter, sequence.incrementAndGet(), Map.of(
                        "type", "error",
                        "data", Map.of("message", safeReason(exception), "retryable", false)
                    ));
                    done(emitter, sequence.incrementAndGet());
                } catch (Exception ignored) {
                    // The durable task/repair facts remain queryable after a closed browser stream.
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/queries")
    public R<List<Map<String, Object>>> history(
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.history(limit));
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param queryId 资源标识
     * @return 处理结果
     */
    @GetMapping("/queries/{queryId}")
    public R<Map<String, Object>> detail(@PathVariable @Positive Long queryId) {
        return R.ok(service.detail(queryId));
    }

    /**
     * 处理结果Stack并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/conversations/{conversationId}/results")
    public R<List<Map<String, Object>>> resultStack(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "10") @Min(1) @Max(10) int limit
    ) {
        return R.ok(service.resultStack(conversationId, limit));
    }

    /**
     * 处理任务Plan并返回对应结果。
     *
     * @param planKey {@code planKey}参数
     * @return 处理结果
     */
    @GetMapping("/task-plans/{planKey}")
    public R<Map<String, Object>> taskPlan(@PathVariable @Size(max = 64) String planKey) {
        return R.ok(service.taskPlan(planKey));
    }

    /**
     * 处理任务PlanEvents并返回对应结果。
     *
     * @param planKey {@code planKey}参数
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/task-plans/{planKey}/events")
    public R<Map<String, Object>> taskPlanEvents(
        @PathVariable @Size(max = 64) String planKey,
        @RequestParam(defaultValue = "0") @Min(0) Long afterCursor,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.taskPlanEvents(planKey, afterCursor, limit));
    }

    /**
     * 处理{@code federatedRun}并返回对应结果。
     *
     * @param runKey {@code runKey}参数
     * @return 处理结果
     */
    @GetMapping("/federated-runs/{runKey}")
    public R<Map<String, Object>> federatedRun(
        @PathVariable @Size(min = 5, max = 64) String runKey
    ) {
        return R.ok(service.federatedRun(runKey));
    }

    /**
     * 更新{@code Presentation}。
     *
     * @param queryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/queries/{queryId}/presentation")
    public R<Map<String, Object>> updatePresentation(
        @PathVariable @Positive Long queryId,
        @Valid @RequestBody PresentationRequest request
    ) {
        return R.ok(service.updatePresentation(
            queryId,
            new PortalChatBIResultService.PresentationUpdate(
                request.expected_revision(), chart(request.chart()), pivot(request.pivot())
            )
        ));
    }

    /**
     * 处理{@code drilldown}并返回对应结果。
     *
     * @param queryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/queries/{queryId}/drilldowns")
    public R<Map<String, Object>> drilldown(
        @PathVariable @Positive Long queryId,
        @Valid @RequestBody DrilldownRequest request
    ) {
        return R.ok(service.drilldown(
            queryId,
            new PortalChatBIResultService.DrilldownRequest(
                request.dimension(), request.value(), request.question()
            )
        ));
    }

    /**
     * 处理{@code chart}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private PortalChatBIPresentationService.ChartConfig chart(ChartConfig request) {
        return request == null ? null : new PortalChatBIPresentationService.ChartConfig(
            request.type(), request.dimension(), request.measures(), request.aggregation()
        );
    }

    /**
     * 处理{@code pivot}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private PortalChatBIPresentationService.PivotConfig pivot(PivotConfig request) {
        return request == null ? null : new PortalChatBIPresentationService.PivotConfig(
            request.row_dimensions(), request.column_dimension(),
            request.value_column(), request.aggregation()
        );
    }

    /**
     * 将输入数据转换为{@code ServiceRequest}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private PortalChatBIQueryService.QueryRequest toServiceRequest(QueryRequest request) {
        return new PortalChatBIQueryService.QueryRequest(
            request.dataset_id(), request.conversation_id(), request.question(),
            request.parent_result_id(), request.result_reference(), request.dataset_ids()
        );
    }

    /**
     * 处理{@code send}相关逻辑。
     *
     * @param emitter {@code emitter}参数
     * @param sequence 起始位置或序号
     * @param event 事件参数
     */
    private void send(SseEmitter emitter, long sequence, Map<String, Object> event) {
        try {
            Object type = event.get("type");
            emitter.send(SseEmitter.event()
                .id(String.valueOf(sequence))
                .name(type == null ? "message" : String.valueOf(type))
                .data(event));
        } catch (Exception exception) {
            throw new IllegalStateException("ChatBI 事件流已断开", exception);
        }
    }

    /**
     * 处理{@code done}相关逻辑。
     *
     * @param emitter {@code emitter}参数
     * @param sequence 起始位置或序号
     */
    private void done(SseEmitter emitter, long sequence) {
        try {
            emitter.send(SseEmitter.event().id(String.valueOf(sequence)).data("[DONE]"));
        } catch (Exception exception) {
            throw new IllegalStateException("ChatBI 事件流已断开", exception);
        }
    }

    /**
     * 处理{@code safeReason}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "ChatBI 查询失败";
        String normalized = message.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    /**
     * 封装查询相关的不可变数据。
     */
    public record QueryRequest(
        @NotNull @Positive Long dataset_id,
        @Size(min = 2, max = 5) List<@Positive Long> dataset_ids,
        @Positive Long conversation_id,
        @NotBlank @Size(max = 4000) String question,
        @Positive Long parent_result_id,
        @Size(max = 255) String result_reference
    ) {
        /**
         * 处理{@code rejectUnknownField}相关逻辑。
         *
         * @param field {@code field}参数
         * @param ignored {@code ignored}参数
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的 ChatBI 查询字段：" + field);
        }
    }

    /**
     * 封装{@code Presentation}相关的不可变数据。
     */
    public record PresentationRequest(
        @NotNull @Min(1) Integer expected_revision,
        @Valid ChartConfig chart,
        @Valid PivotConfig pivot
    ) {
        /**
         * 处理{@code rejectUnknownField}相关逻辑。
         *
         * @param field {@code field}参数
         * @param ignored {@code ignored}参数
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的 ChatBI 展示配置字段：" + field);
        }
    }

    /**
     * 封装{@code Chart}相关的不可变数据。
     */
    public record ChartConfig(
        @NotBlank @Size(max = 16) String type,
        @Size(max = 255) String dimension,
        @Size(max = 4) List<@NotBlank @Size(max = 255) String> measures,
        @NotBlank @Size(max = 16) String aggregation
    ) {
        /**
         * 处理{@code rejectUnknownField}相关逻辑。
         *
         * @param field {@code field}参数
         * @param ignored {@code ignored}参数
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的图表配置字段：" + field);
        }
    }

    /**
     * 封装{@code Pivot}相关的不可变数据。
     */
    public record PivotConfig(
        @NotNull @Size(min = 1, max = 3) List<@NotBlank @Size(max = 255) String> row_dimensions,
        @Size(max = 255) String column_dimension,
        @Size(max = 255) String value_column,
        @NotBlank @Size(max = 16) String aggregation
    ) {
        /**
         * 处理{@code rejectUnknownField}相关逻辑。
         *
         * @param field {@code field}参数
         * @param ignored {@code ignored}参数
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的透视配置字段：" + field);
        }
    }

    /**
     * 封装{@code Drilldown}相关的不可变数据。
     */
    public record DrilldownRequest(
        @NotBlank @Size(max = 255) String dimension,
        Object value,
        @Size(max = 2000) String question
    ) {
        /**
         * 处理{@code rejectUnknownField}相关逻辑。
         *
         * @param field {@code field}参数
         * @param ignored {@code ignored}参数
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的 ChatBI 下钻字段：" + field);
        }
    }
}
