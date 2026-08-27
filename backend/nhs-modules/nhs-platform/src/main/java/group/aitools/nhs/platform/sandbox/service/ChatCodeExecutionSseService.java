package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobOutputRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责对话Code执行Sse相关的业务编排与领域规则处理。
 * Replays durable sandbox output and terminal state using a per-execution SSE cursor. */
@Service
public class ChatCodeExecutionSseService {

    private static final Set<String> TERMINAL = Set.of(
        "succeeded", "failed", "cancelled", "expired"
    );
    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int BATCH_SIZE = 200;

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param reader {@code reader}参数
     * @param afterCursor {@code afterCursor}参数
     * @return 处理结果
     */
    public SseEmitter stream(
        ChatCodeExecutionService.EventStreamReader reader,
        long afterCursor
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean();
        Thread worker = Thread.ofVirtual()
            .name("chat-code-sse-" + afterCursor)
            .start(() -> poll(reader, afterCursor, emitter, closed));
        emitter.onCompletion(() -> close(closed, worker));
        emitter.onTimeout(() -> close(closed, worker));
        emitter.onError(ignored -> close(closed, worker));
        return emitter;
    }

    /**
     * 处理{@code poll}相关逻辑。
     *
     * @param reader {@code reader}参数
     * @param initialCursor {@code initialCursor}参数
     * @param emitter {@code emitter}参数
     * @param closed {@code closed}参数
     */
    private void poll(
        ChatCodeExecutionService.EventStreamReader reader,
        long initialCursor,
        SseEmitter emitter,
        AtomicBoolean closed
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long cursor = initialCursor;
        long lastHeartbeat = System.nanoTime();
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                ChatCodeExecutionService.EventBatch batch = reader.read(
                    Math.max(0, cursor - 1), BATCH_SIZE
                );
                SandboxJobRow job = batch.job();
                if (cursor < 1) {
                    emitter.send(SseEmitter.event()
                        .id("1")
                        .name("started")
                        .data(started(job)));
                    cursor = 1;
                }
                for (SandboxJobOutputRow output : batch.outputs()) {
                    long eventCursor = output.getSequenceNo() + 1;
                    if (eventCursor <= cursor) {
                        continue;
                    }
                    emitter.send(SseEmitter.event()
                        .id(Long.toString(eventCursor))
                        .name("output")
                        .data(Map.of(
                            "stream", output.getStream(),
                            "chunk", output.getContent(),
                            "sequence", output.getSequenceNo() - 1
                        )));
                    cursor = eventCursor;
                }
                if (batch.outputs().size() == BATCH_SIZE) {
                    continue;
                }
                if (TERMINAL.contains(job.getStatus())) {
                    long terminalCursor = (job.getOutputSequence() == null
                        ? 0 : job.getOutputSequence()) + 2;
                    if (cursor < terminalCursor) {
                        TerminalEvent terminal = terminal(job);
                        emitter.send(SseEmitter.event()
                            .id(Long.toString(terminalCursor))
                            .name(terminal.name())
                            .data(terminal.data()));
                    }
                    break;
                }
                long now = System.nanoTime();
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL.toNanos()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            closed.set(true);
        } catch (RuntimeException exception) {
            if (closed.compareAndSet(false, true)) {
                emitter.completeWithError(exception);
            }
        } finally {
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    /**
     * 处理{@code started}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Map<String, Object> started(SandboxJobRow job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("execution_id", job.getId().toString());
        data.put("language", job.getScriptLanguage());
        data.put("started_at", job.getStartedAt() == null ? job.getCreatedAt() : job.getStartedAt());
        data.put("status", job.getStatus());
        return data;
    }

    /**
     * 处理{@code terminal}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private TerminalEvent terminal(SandboxJobRow job) {
        if ("cancelled".equals(job.getStatus())) {
            return new TerminalEvent("stopped", terminalData(job, "stopped"));
        }
        if ("EXECUTION_TIMEOUT".equals(job.getFailureCode())) {
            Map<String, Object> data = terminalData(job, "timed_out");
            data.put("message", valueOr(job.getFailureMessage(), "代码执行超时（60 秒）。"));
            return new TerminalEvent("timeout", data);
        }
        if ("succeeded".equals(job.getStatus())
            || "NON_ZERO_EXIT".equals(job.getFailureCode())
            || "OUTPUT_LIMIT_EXCEEDED".equals(job.getFailureCode())) {
            return new TerminalEvent("finished", terminalData(job, job.getStatus()));
        }
        Map<String, Object> data = terminalData(job, job.getStatus());
        data.put("code", valueOr(job.getFailureCode(), "execution_error").toLowerCase());
        data.put("message", valueOr(job.getFailureMessage(), "代码执行失败。"));
        return new TerminalEvent("error", data);
    }

    /**
     * 处理terminal数据并返回对应结果。
     *
     * @param job 作业参数
     * @param status 目标状态
     * @return 处理结果
     */
    private Map<String, Object> terminalData(SandboxJobRow job, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("execution_id", job.getId().toString());
        data.put("status", status);
        data.put("exit_code", job.getExitCode());
        data.put("elapsed_ms", elapsed(job.getStartedAt(), job.getFinishedAt()));
        data.put("truncated", Boolean.TRUE.equals(job.getOutputTruncated())
            || "OUTPUT_LIMIT_EXCEEDED".equals(job.getFailureCode()));
        return data;
    }

    /**
     * 处理{@code elapsed}并返回对应结果。
     *
     * @param start {@code start}参数
     * @param finish {@code finish}参数
     * @return 处理结果
     */
    private long elapsed(LocalDateTime start, LocalDateTime finish) {
        if (start == null || finish == null || finish.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, finish).toMillis();
    }

    /**
     * 处理{@code valueOr}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 处理{@code close}相关逻辑。
     *
     * @param closed {@code closed}参数
     * @param worker 工作进程参数
     */
    private void close(AtomicBoolean closed, Thread worker) {
        if (closed.compareAndSet(false, true)) {
            worker.interrupt();
        }
    }

    /**
     * 封装{@code Terminal}相关的不可变数据。
     */
    private record TerminalEvent(String name, Map<String, Object> data) {
    }
}
