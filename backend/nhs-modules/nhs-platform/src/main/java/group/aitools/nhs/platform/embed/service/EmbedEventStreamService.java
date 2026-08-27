package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Set;

/**
 * 负责嵌入式会话事件Stream相关的业务编排与领域规则处理。
 * Replays persisted Embed events and follows a running turn without owning its execution. */
@Service
public class EmbedEventStreamService {

    private static final int BATCH_SIZE = 200;
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    private final EmbedChatPersistenceService persistence;

    public EmbedEventStreamService(EmbedChatPersistenceService persistence) {
        this.persistence = persistence;
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param afterCursor {@code afterCursor}参数
     * @return 处理结果
     */
    public Flux<ExecutionEventView> stream(
        EmbedSession session,
        EmbedTurn turn,
        long afterCursor
    ) {
        return Flux.create(sink -> {
            Thread worker = Thread.ofVirtual()
                .name("agent-embed-event-stream-" + turn.getId())
                .start(() -> poll(session, turn, Math.max(0, afterCursor), sink));
            sink.onCancel(worker::interrupt);
            sink.onDispose(worker::interrupt);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 处理{@code poll}相关逻辑。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param initialCursor {@code initialCursor}参数
     * @param sink {@code sink}参数
     */
    private void poll(
        EmbedSession session,
        EmbedTurn turn,
        long initialCursor,
        FluxSink<ExecutionEventView> sink
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long cursor = initialCursor;
        try {
            while (!sink.isCancelled() && !Thread.currentThread().isInterrupted()) {
                List<ExecutionEventView> events = persistence.eventsAfter(
                    session, turn, cursor, BATCH_SIZE
                );
                for (ExecutionEventView event : events) {
                    sink.next(event);
                    cursor = event.cursor();
                }
                if (events.size() == BATCH_SIZE) continue;
                EmbedTurn current = persistence.currentTurn(session.getId(), turn.getId());
                if (current == null || TERMINAL.contains(current.getStatus())) {
                    sink.complete();
                    return;
                }
                Thread.sleep(250L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            if (!sink.isCancelled()) sink.error(exception);
        }
    }
}
