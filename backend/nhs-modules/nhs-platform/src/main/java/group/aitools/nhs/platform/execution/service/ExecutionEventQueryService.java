package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/**
 * 负责执行事件查询相关的业务编排与领域规则处理。
 */
@Service
public class ExecutionEventQueryService {

    private static final int MAX_TRACE_STEPS = 1_000;
    private static final int TRACE_EVENT_PAGE_SIZE = 500;

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentConversationMapper conversationMapper;
    private final ConversationTurnMapper conversationTurnMapper;
    private final TaskQueryService taskQueryService;
    private final AgentExecutionEventMapper eventMapper;
    private final JsonMapper jsonMapper;
    private final ExecutionTraceAggregationService traceAggregationService;

    /**
     * 创建 {@code ExecutionEventQueryService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param conversationMapper 会话Mapper参数
     * @param conversationTurnMapper 会话会话回合Mapper参数
     * @param taskQueryService 任务查询Service参数
     * @param eventMapper 事件Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param traceAggregationService 链路追踪AggregationService参数
     */
    public ExecutionEventQueryService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentConversationMapper conversationMapper,
        ConversationTurnMapper conversationTurnMapper,
        TaskQueryService taskQueryService,
        AgentExecutionEventMapper eventMapper,
        JsonMapper jsonMapper,
        ExecutionTraceAggregationService traceAggregationService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.conversationMapper = conversationMapper;
        this.conversationTurnMapper = conversationTurnMapper;
        this.taskQueryService = taskQueryService;
        this.eventMapper = eventMapper;
        this.jsonMapper = jsonMapper;
        this.traceAggregationService = traceAggregationService;
    }

    /**
     * 查询会话列表。
     *
     * @param conversationId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> listConversation(
        Long conversationId,
        long afterCursor,
        int limit
    ) {
        return conversationReader(conversationId).read(afterCursor, limit);
    }

    /**
     * 查询任务Run列表。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> listTaskRun(
        Long taskId,
        Long runId,
        long afterCursor,
        int limit
    ) {
        return taskRunReader(taskId, runId).read(afterCursor, limit);
    }

    /**
     * 查询任务RunAs列表。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> listTaskRunAs(
        CurrentPrincipal principal,
        Long taskId,
        Long runId,
        long afterCursor,
        int limit
    ) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task", taskId, null, "view", ResourceState.ACTIVE, false, Set.of(), taskId
        ));
        Long actualTaskId = eventMapper.selectTaskIdForRun(runId);
        if (!taskId.equals(actualTaskId)) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        return eventMapper.selectTaskRunEvents(taskId, runId, afterCursor, limit).stream()
            .map(event -> ExecutionEventView.forExternal(event, jsonMapper)).toList();
    }

    /**
     * 处理链路追踪会话并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    public ConversationTrace traceConversation(String traceId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String normalizedTraceId = traceId == null ? "" : traceId.strip();
        if (normalizedTraceId.isEmpty() || normalizedTraceId.length() > 64
            || !normalizedTraceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new ServiceException("Trace ID无效", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversationTurn turn = conversationTurnMapper.selectOwnedTurnByTrace(
            normalizedTraceId, principal.id()
        );
        if (turn == null) {
            throw new ServiceException("执行链路不存在", HttpStatus.NOT_FOUND);
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", turn.getConversationId(), null, "view", ResourceState.ACTIVE,
            true, Set.of(), null
        ));
        ExecutionTraceAggregationService.TraceAccumulator accumulator =
            traceAggregationService.accumulator();
        long afterCursor = 0L;
        boolean legacyPage = false;
        while (true) {
            List<AgentExecutionEvent> page = eventMapper.selectConversationTraceEventsAfter(
                normalizedTraceId, turn.getConversationId(), afterCursor, TRACE_EVENT_PAGE_SIZE
            );
            // Compatibility for an already-created mapper proxy during rolling deployment.
            if (page == null || (page.isEmpty() && afterCursor == 0L)) {
                List<AgentExecutionEvent> legacy = eventMapper.selectConversationTraceEvents(
                    normalizedTraceId, turn.getConversationId(), Integer.MAX_VALUE
                );
                if (legacy != null && !legacy.isEmpty()) {
                    page = legacy;
                    legacyPage = true;
                }
            }
            if (page == null || page.isEmpty()) {
                break;
            }
            accumulator.accept(page.stream()
                .map(event -> ExecutionEventView.forConversationOwner(event, jsonMapper)).toList());
            if (accumulator.size() > MAX_TRACE_STEPS) {
                throw new ServiceException("执行链路语义步骤超过1000条限制", 413);
            }
            long nextCursor = page.getLast().getCursor();
            if (legacyPage || nextCursor <= afterCursor || page.size() < TRACE_EVENT_PAGE_SIZE) {
                break;
            }
            afterCursor = nextCursor;
        }
        List<ExecutionEventView> events = accumulator.finish();
        List<ConversationMessageRow> messages = conversationTurnMapper.selectTraceMessages(
            turn.getConversationId(), normalizedTraceId
        );
        return new ConversationTrace(
            normalizedTraceId, principal.username(), turn, List.copyOf(messages), events
        );
    }

    /**
     * 处理会话Reader并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    public EventStreamReader conversationReader(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation",
            conversationId,
            null,
            "view",
            ResourceState.ACTIVE,
            true,
            Set.of(),
            null
        ));
        AgentConversation conversation = conversationMapper.selectOwnedConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        return (afterCursor, limit) -> eventMapper.selectConversationEvents(
            conversationId, afterCursor, limit
        ).stream().map(event -> ExecutionEventView.forConversationOwner(event, jsonMapper)).toList();
    }

    /**
     * 处理任务RunReader并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    public EventStreamReader taskRunReader(Long taskId, Long runId) {
        taskQueryService.get(taskId);
        Long actualTaskId = eventMapper.selectTaskIdForRun(runId);
        if (!taskId.equals(actualTaskId)) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        return (afterCursor, limit) -> eventMapper.selectTaskRunEvents(
            taskId, runId, afterCursor, limit
        ).stream().map(event -> ExecutionEventView.forExternal(event, jsonMapper)).toList();
    }

    /**
     * 定义事件StreamReader相关能力的服务契约。
     */
    @FunctionalInterface
    public interface EventStreamReader {

        /**
         * 处理{@code read}并返回对应结果。
         *
         * @param afterCursor {@code afterCursor}参数
         * @param limit 数量上限
         * @return 符合条件的数据集合
         */
        List<ExecutionEventView> read(long afterCursor, int limit);
    }

    /**
     * 封装会话链路追踪相关的不可变数据。
     */
    public record ConversationTrace(
        String traceId,
        String username,
        AgentConversationTurn turn,
        List<ConversationMessageRow> messages,
        List<ExecutionEventView> events
    ) {
    }
}
