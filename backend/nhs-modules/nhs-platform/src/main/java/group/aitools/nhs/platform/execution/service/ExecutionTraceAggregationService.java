package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责执行链路追踪Aggregation相关的业务编排与领域规则处理。
 * Collapses trusted model/tool query projections into bounded semantic trace steps. */
@Service
public class ExecutionTraceAggregationService {

    private static final int MAX_ACCUMULATED_CHARS = 64 * 1024;
    private static final int MAX_ACCUMULATED_DATA_BYTES = 64 * 1024;
    private static final TypeReference<Object> VALUE_TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ExecutionTraceAggregationService} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ExecutionTraceAggregationService(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code aggregate}并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> aggregate(List<ExecutionEventView> events) {
        TraceAccumulator accumulator = accumulator();
        accumulator.accept(events);
        return accumulator.finish();
    }

    /**
     * 处理{@code accumulator}并返回对应结果。
     *
     * @return 处理结果
     */
    public TraceAccumulator accumulator() {
        return new TraceAccumulator();
    }

    /**
 * 处理{@code accept}相关逻辑。
 *
 * 表示链路追踪Accumulator相关的领域对象。
 * Incremental trace reducer used by paged event readers. */
    public final class TraceAccumulator {
        private final Map<String, StepAccumulator> steps = new LinkedHashMap<>();
        private int uncorrelated;

        public void accept(List<ExecutionEventView> events) {
            if (events == null) {
                return;
            }
            for (ExecutionEventView event : events) {
                StepKind kind = kind(event);
                String key = correlationKey(event, kind);
                if (key == null) {
                    key = "event:" + event.cursor() + ":" + uncorrelated++;
                    kind = StepKind.PASSTHROUGH;
                }
                StepKind resolvedKind = kind;
                steps.computeIfAbsent(key, ignored -> new StepAccumulator(resolvedKind, event))
                    .accept(event);
            }
        }

        /**
         * 处理{@code size}并返回对应结果。
         *
         * @return 处理结果
         */
        public int size() {
            return steps.size();
        }

        /**
         * 处理{@code finish}并返回对应结果。
         *
         * @return 符合条件的数据集合
         */
        public List<ExecutionEventView> finish() {
            return steps.values().stream().map(StepAccumulator::view).toList();
        }
    }

    /**
     * 处理{@code kind}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private StepKind kind(ExecutionEventView event) {
        if (event.projection().isEmpty()) {
            return StepKind.PASSTHROUGH;
        }
        if (event.eventType().startsWith("model_call_")) {
            return StepKind.MODEL;
        }
        if (event.eventType().startsWith("tool_call_")
            || event.eventType().startsWith("tool_result_")) {
            return StepKind.TOOL;
        }
        return StepKind.PASSTHROUGH;
    }

    /**
     * 处理{@code correlationKey}并返回对应结果。
     *
     * @param event 事件参数
     * @param kind {@code kind}参数
     * @return 处理结果
     */
    private String correlationKey(ExecutionEventView event, StepKind kind) {
        if (kind == StepKind.MODEL) {
            String replyId = text(event.projection(), "replyId");
            return replyId == null ? null : "model:" + replyId;
        }
        if (kind == StepKind.TOOL) {
            String toolCallId = text(event.projection(), "toolCallId");
            return toolCallId == null ? null : "tool:" + toolCallId;
        }
        return null;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /**
     * 表示{@code StepAccumulator}相关的领域对象。
     */
    private final class StepAccumulator {

        private final StepKind kind;
        private final ExecutionEventView first;
        private final Map<String, Object> projection = new LinkedHashMap<>();
        private final StringBuilder input = new StringBuilder();
        private final StringBuilder output = new StringBuilder();
        private final List<Object> outputData = new ArrayList<>();
        private LocalDateTime lastAt;
        private LocalDateTime resultStartedAt;
        private LocalDateTime resultFinishedAt;
        private int outputDataBytes;
        private boolean inputTruncated;
        private boolean outputTruncated;
        private boolean pending;
        private boolean failed;
        private boolean started;
        private boolean finished;

        /**
         * 创建 {@code StepAccumulator} 实例并初始化所需依赖。
         *
         * @param kind {@code kind}参数
         * @param first {@code first}参数
         */
        private StepAccumulator(StepKind kind, ExecutionEventView first) {
            this.kind = kind;
            this.first = first;
            this.lastAt = first.occurredAt();
        }

        /**
         * 处理{@code accept}相关逻辑。
         *
         * @param event 事件参数
         */
        private void accept(ExecutionEventView event) {
            // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
            if (kind == StepKind.PASSTHROUGH) {
                return;
            }
            if (isStart(event)) {
                started = true;
            }
            if (isFinish(event)) {
                finished = true;
            }
            Map<String, Object> values = event.projection();
            copyText(values, "agentName");
            copyText(values, "model");
            if (kind == StepKind.MODEL || event.eventType().startsWith("tool_call_")
                || !projection.containsKey("replyId")) {
                copyText(values, "replyId");
            }
            copyText(values, "toolCallId");
            copyText(values, "toolName");
            copyText(values, "toolState");
            copyText(values, "evidenceType");
            copyText(values, "evidenceStatus");
            copyNumber(values, "temperature");
            copyNumber(values, "promptTokens");
            copyNumber(values, "completionTokens");
            copyNumber(values, "cachedTokens");
            copyNumber(values, "totalTokens");
            copyNumber(values, "durationMs");
            if (Boolean.TRUE.equals(values.get("truncated"))) {
                projection.put("projectionTruncated", true);
            }

            inputTruncated |= append(input, text(values, "inputDelta"));
            outputTruncated |= append(output, text(values, "outputDelta"));
            if (values.containsKey("outputData") && values.get("outputData") != null) {
                appendOutputData(values.get("outputData"));
            }
            if ("tool_result_started".equals(event.eventType())) {
                resultStartedAt = event.occurredAt();
            } else if ("tool_result_finished".equals(event.eventType())) {
                resultFinishedAt = event.occurredAt();
            }
            if (event.occurredAt() != null
                && (lastAt == null || event.occurredAt().isAfter(lastAt))) {
                lastAt = event.occurredAt();
            }
            pending |= "pending".equals(event.eventStatus());
            failed |= "failed".equals(event.eventStatus());
        }

        /**
         * 处理{@code copyText}相关逻辑。
         *
         * @param source 数据源参数
         * @param key {@code key}参数
         */
        private void copyText(Map<String, Object> source, String key) {
            String value = text(source, key);
            if (value != null) {
                projection.put(key, value);
            }
        }

        /**
         * 处理{@code copyNumber}相关逻辑。
         *
         * @param source 数据源参数
         * @param key {@code key}参数
         */
        private void copyNumber(Map<String, Object> source, String key) {
            Object value = source.get(key);
            if (value instanceof Number) {
                projection.put(key, value);
            }
        }

        /**
         * 处理{@code append}并返回对应结果。
         *
         * @param target {@code target}参数
         * @param delta {@code delta}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean append(StringBuilder target, String delta) {
            if (delta == null || delta.isEmpty()) {
                return false;
            }
            int remaining = MAX_ACCUMULATED_CHARS - target.length();
            if (remaining <= 0) {
                return true;
            }
            target.append(delta, 0, Math.min(delta.length(), remaining));
            return delta.length() > remaining;
        }

        /**
         * 处理appendOutput数据相关逻辑。
         *
         * @param data 数据参数
         */
        private void appendOutputData(Object data) {
            try {
                byte[] encoded = jsonMapper.writeValueAsBytes(data);
                int separatorBytes = outputData.isEmpty() ? 0 : 1;
                if (outputDataBytes + separatorBytes + encoded.length
                    > MAX_ACCUMULATED_DATA_BYTES) {
                    outputTruncated = true;
                    return;
                }
                outputData.add(data);
                outputDataBytes += separatorBytes + encoded.length;
            } catch (RuntimeException exception) {
                outputTruncated = true;
            }
        }

        /**
         * 处理{@code view}并返回对应结果。
         *
         * @return 处理结果
         */
        private ExecutionEventView view() {
            // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
            if (kind == StepKind.PASSTHROUGH) {
                return first;
            }
            if (kind == StepKind.TOOL) {
                Map<String, Object> toolInput = toolInput();
                Object toolOutput = toolOutput();
                if (toolInput != null) {
                    projection.put("toolInput", toolInput);
                }
                if (toolOutput != null) {
                    projection.put("toolOutput", toolOutput);
                }
                if (inputTruncated) {
                    projection.put("toolInputTruncated", true);
                }
                if (outputTruncated) {
                    projection.put("toolOutputTruncated", true);
                }
                projection.put("executionTimeMs", toolDurationMs());
                putCorrelation("spanId", "toolCallId");
                putCorrelation("parentSpanId", "replyId");
            } else {
                Object duration = projection.get("durationMs");
                projection.put(
                    "executionTimeMs",
                    duration instanceof Number ? duration : elapsed(first.occurredAt(), lastAt)
                );
                putCorrelation("spanId", "replyId");
            }
            String status = status();
            Map<String, Object> safeProjection = RuntimeSecretScrubber.sanitizeMap(projection);
            return new ExecutionEventView(
                first.eventId(), first.traceId(), first.conversationId(), first.runId(),
                first.stepId(), first.cursor(), kind == StepKind.TOOL ? "tool_call" : "model_call",
                status, summary(), Map.of("redacted", true), "internal", first.occurredAt(),
                safeProjection
            );
        }

        /**
         * 将输入数据转换为{@code olInput}。
         *
         * @return 处理结果
         */
        private Map<String, Object> toolInput() {
            if (input.isEmpty()) {
                return null;
            }
            Object parsed = parse(input.toString());
            if (parsed == null) {
                return Map.of("raw", input.toString());
            }
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            return Map.of("raw", parsed);
        }

        /**
         * 处理{@code putCorrelation}相关逻辑。
         *
         * @param targetKey {@code targetKey}参数
         * @param sourceKey 数据源Key参数
         */
        private void putCorrelation(String targetKey, String sourceKey) {
            Object value = projection.get(sourceKey);
            if (value != null) {
                projection.put(targetKey, value);
            }
        }

        /**
         * 将输入数据转换为{@code olOutput}。
         *
         * @return 处理结果
         */
        private Object toolOutput() {
            Object textOutput = output.isEmpty() ? null : parse(output.toString());
            Object dataOutput = outputData.isEmpty() ? null
                : outputData.size() == 1 ? outputData.getFirst() : List.copyOf(outputData);
            if (textOutput == null) {
                return dataOutput;
            }
            if (dataOutput == null) {
                return textOutput;
            }
            return Map.of("text", textOutput, "data", dataOutput);
        }

        /**
         * 处理{@code parse}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        private Object parse(String value) {
            try {
                return jsonMapper.readValue(value, VALUE_TYPE);
            } catch (RuntimeException exception) {
                return value;
            }
        }

        /**
         * 将输入数据转换为{@code olDurationMs}。
         *
         * @return 处理结果
         */
        private double toolDurationMs() {
            if (resultStartedAt != null && resultFinishedAt != null) {
                return elapsed(resultStartedAt, resultFinishedAt);
            }
            return elapsed(first.occurredAt(), lastAt);
        }

        /**
         * 处理{@code elapsed}并返回对应结果。
         *
         * @param start {@code start}参数
         * @param end {@code end}参数
         * @return 处理结果
         */
        private double elapsed(LocalDateTime start, LocalDateTime end) {
            return start == null || end == null ? 0D : Duration.between(start, end).toNanos() / 1_000_000D;
        }

        /**
         * 处理{@code status}并返回对应结果。
         *
         * @return 处理结果
         */
        private String status() {
            String toolState = text(projection, "toolState");
            if (failed || "error".equals(toolState) || "denied".equals(toolState)
                || "interrupted".equals(toolState)) {
                return "failed";
            }
            if (pending || "running".equals(toolState)) {
                return "pending";
            }
            // AgentScope marks start events as SUCCESS too. A semantic step is
            // not successful until its matching finish event has been observed.
            return finished ? "success" : "pending";
        }

        /**
         * 判断{@code Start}是否满足要求。
         *
         * @param event 事件参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean isStart(ExecutionEventView event) {
            return kind == StepKind.MODEL
                ? "model_call_started".equals(event.eventType())
                : "tool_call_started".equals(event.eventType())
                    || "tool_result_started".equals(event.eventType());
        }

        /**
         * 判断{@code Finish}是否满足要求。
         *
         * @param event 事件参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean isFinish(ExecutionEventView event) {
            return kind == StepKind.MODEL
                ? "model_call_finished".equals(event.eventType())
                : "tool_result_finished".equals(event.eventType())
                    || ("tool_call_finished".equals(event.eventType()) && !hasResultStarted());
        }

        /**
         * 判断结果Started是否满足要求。
         *
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean hasResultStarted() {
            return resultStartedAt != null;
        }

        /**
         * 处理{@code summary}并返回对应结果。
         *
         * @return 处理结果
         */
        private String summary() {
            if (kind == StepKind.TOOL) {
                String toolName = text(projection, "toolName");
                return toolName == null ? "Tool call" : "Tool call: " + toolName;
            }
            String model = text(projection, "model");
            return model == null ? "Model call" : "Model call: " + model;
        }
    }

    /**
     * 定义{@code StepKind}相关的可选值。
     */
    private enum StepKind {
        PASSTHROUGH,
        MODEL,
        TOOL
    }
}
