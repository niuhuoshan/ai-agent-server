package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责执行事件Sse相关的业务编排与领域规则处理。
 */
@Service
public class ExecutionEventSseService {

    private static final int BATCH_SIZE = 200;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final NhsSseEventMapper NHS_EVENT_MAPPER = new NhsSseEventMapper();

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param reader {@code reader}参数
     * @param afterCursor {@code afterCursor}参数
     * @return 处理结果
     */
    public SseEmitter stream(
        ExecutionEventQueryService.EventStreamReader reader,
        long afterCursor
    ) {
        return stream(reader, afterCursor, false);
    }

    /**
 * 处理{@code streamNhs}并返回对应结果。
 * Emits Nhs-compatible JSON chunks while retaining the durable cursor contract. */
    public SseEmitter streamNhs(
        ExecutionEventQueryService.EventStreamReader reader,
        long afterCursor
    ) {
        return stream(reader, afterCursor, true);
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param reader {@code reader}参数
     * @param afterCursor {@code afterCursor}参数
     * @param nhsCompatibility {@code nhsCompatibility}参数
     * @return 处理结果
     */
    private SseEmitter stream(
        ExecutionEventQueryService.EventStreamReader reader,
        long afterCursor,
        boolean nhsCompatibility
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean();
        Thread worker = Thread.ofVirtual()
            .name("agent-event-sse-" + afterCursor)
            .start(() -> poll(reader, afterCursor, emitter, closed, nhsCompatibility));
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
     * @param nhsCompatibility {@code nhsCompatibility}参数
     */
    private void poll(
        ExecutionEventQueryService.EventStreamReader reader,
        long initialCursor,
        SseEmitter emitter,
        AtomicBoolean closed,
        boolean nhsCompatibility
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long cursor = initialCursor;
        long lastHeartbeat = System.nanoTime();
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                List<ExecutionEventView> events = reader.read(cursor, BATCH_SIZE);
                for (ExecutionEventView event : events) {
                    if (nhsCompatibility) {
                        for (Map<String, Object> chunk : NHS_EVENT_MAPPER.map(event)) {
                            emitter.send(SseEmitter.event()
                                .id(Long.toString(event.cursor()))
                                .data(chunk));
                        }
                    } else {
                        emitter.send(SseEmitter.event()
                            .id(Long.toString(event.cursor()))
                            .name(event.eventType())
                            .data(event));
                    }
                    cursor = event.cursor();
                    if (terminal(event.eventType())) {
                        // Nhs clients use the sentinel to replace the
                        // streaming draft. It is emitted only after the
                        // terminal fact itself has been persisted.
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        closed.set(true);
                        emitter.complete();
                        return;
                    }
                }
                if (events.size() == BATCH_SIZE) {
                    continue;
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
     * 处理{@code terminal}并返回对应结果。
     *
     * @param eventType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean terminal(String eventType) {
        return "run_finished".equals(eventType)
            || "failed".equals(eventType)
            || "cancelled".equals(eventType)
            || "permission_denied".equals(eventType)
            || "iteration_limit_reached".equals(eventType);
    }
}
