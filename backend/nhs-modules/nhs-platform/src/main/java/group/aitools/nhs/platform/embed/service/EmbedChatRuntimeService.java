package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService.TurnStart;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 负责嵌入式会话对话运行时相关的业务编排与领域规则处理。
 */
@Service
public class EmbedChatRuntimeService {

    private final EmbedChatPersistenceService persistence;
    private final EmbedRuntimeSnapshotFactory snapshotFactory;
    private final ObjectProvider<AgentRuntimeExecutionService> runtimeProvider;
    private final EmbedChatExecutionCoordinator coordinator;
    private final EmbedEventStreamService eventStream;

    /**
     * 创建 {@code EmbedChatRuntimeService} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param snapshotFactory 快照Factory参数
     * @param runtimeProvider 运行时提供方参数
     * @param coordinator {@code coordinator}参数
     * @param eventStream 事件Stream参数
     */
    @Autowired
    public EmbedChatRuntimeService(
        EmbedChatPersistenceService persistence,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        EmbedChatExecutionCoordinator coordinator,
        EmbedEventStreamService eventStream
    ) {
        this.persistence = persistence;
        this.snapshotFactory = snapshotFactory;
        this.runtimeProvider = runtimeProvider;
        this.coordinator = coordinator;
        this.eventStream = eventStream;
    }

    /**
     * 创建 {@code EmbedChatRuntimeService} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param snapshotFactory 快照Factory参数
     * @param runtimeProvider 运行时提供方参数
     */
    EmbedChatRuntimeService(
        EmbedChatPersistenceService persistence,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider
    ) {
        this.persistence = persistence;
        this.snapshotFactory = snapshotFactory;
        this.runtimeProvider = runtimeProvider;
        this.coordinator = null;
        this.eventStream = null;
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    public EmbedInvocation invoke(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        String idempotencyKey,
        String input
    ) {
        TurnStart start = persistence.beginTurn(authenticated, sessionId, idempotencyKey, input);
        if (start.replayed()) {
            return new EmbedInvocation(
                start.turn().getId(), true,
                Flux.fromIterable(persistence.replayEvents(start.session(), start.turn()))
            );
        }
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            ServiceException unavailable = new ServiceException("AgentScope运行时未启用", 503);
            persistence.fail(start.turn().getId(), unavailable);
            throw unavailable;
        }
        AgentRunRequest request;
        try {
            request = snapshotFactory.build(
                authenticated.principal(), start.session(), start.turn(), start.input()
            );
        } catch (RuntimeException exception) {
            persistence.fail(start.turn().getId(), exception);
            throw exception;
        }
        Flux<ExecutionEventView> events = Flux.defer(() -> runtime.run(request))
            .doOnComplete(() -> persistence.complete(start.turn().getId()))
            .doOnError(error -> persistence.fail(start.turn().getId(), error))
            .doOnCancel(() -> persistence.cancel(start.turn().getId()));
        return new EmbedInvocation(start.turn().getId(), false, events);
    }

    /**
     * 执行{@code Widget}相关的处理流程。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @param attachmentIds 资源标识集合
     * @param context 待处理内容
     * @param afterCursor {@code afterCursor}参数
     * @return 处理结果
     */
    public EmbedInvocation invokeWidget(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        String idempotencyKey,
        String input,
        java.util.List<Long> attachmentIds,
        java.util.Map<String, Object> context,
        long afterCursor
    ) {
        if (coordinator == null || eventStream == null) {
            throw new IllegalStateException("Embed后台执行协调器未启用");
        }
        TurnStart start = persistence.beginWidgetTurn(
            authenticated, sessionId, idempotencyKey, input, attachmentIds, context
        );
        if (!start.replayed()) {
            try {
                coordinator.launch(authenticated, start);
            } catch (RuntimeException exception) {
                persistence.fail(start.turn().getId(), exception);
                throw exception instanceof ServiceException ? exception
                    : new ServiceException("AgentScope运行时未启用", 503);
            }
        }
        return new EmbedInvocation(
            start.turn().getId(), start.replayed(),
            eventStream.stream(start.session(), start.turn(), afterCursor)
        );
    }

    /**
     * 处理{@code resumeWidget}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @return 处理结果
     */
    public EmbedInvocation resumeWidget(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        Long turnId,
        long afterCursor
    ) {
        if (eventStream == null) throw new IllegalStateException("Embed事件流未启用");
        var session = persistence.ownedActiveSession(authenticated, sessionId);
        var turn = persistence.ownedTurn(authenticated, sessionId, turnId);
        return new EmbedInvocation(
            turn.getId(), true, eventStream.stream(session, turn, afterCursor)
        );
    }

    /**
     * 处理{@code stopWidget}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    public group.aitools.nhs.platform.embed.domain.EmbedTurn stopWidget(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        Long turnId
    ) {
        var turn = persistence.requestStop(authenticated, sessionId, turnId);
        if ("stopping".equals(turn.getStatus()) && coordinator != null) {
            coordinator.requestStop(turnId, "用户停止Embed回复");
        }
        return persistence.ownedTurn(authenticated, sessionId, turnId);
    }

    /**
     * 封装嵌入式会话调用相关的不可变数据。
     */
    public record EmbedInvocation(
        Long turnId,
        boolean replayed,
        Flux<ExecutionEventView> events
    ) {
    }
}
