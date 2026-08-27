package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;
import group.aitools.nhs.platform.runtime.question.domain.AgentRuntimeUserQuestion;
import group.aitools.nhs.platform.conversation.service.ConversationTurnPersistenceService.TurnStart;
import group.aitools.nhs.platform.conversation.web.ConversationTurnView;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.conversation.web.RetryConversationTurnRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 负责会话会话回合相关的业务编排与领域规则处理。
 * Human control-plane facade for starting, observing and stopping real Agent turns. */
@Service
public class ConversationTurnApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ConversationTurnPersistenceService persistence;
    private final ConversationTurnExecutionCoordinator coordinator;
    private JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final String CHATBI_DATASET_SELECTION_PURPOSE = "chatbi_dataset_selection";
    private static final TypeReference<List<Map<String, Object>>> QUESTION_OPTIONS = new TypeReference<>() { };
    private static final TypeReference<List<String>> SELECTED_OPTION_IDS = new TypeReference<>() { };

    public ConversationTurnApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ConversationTurnPersistenceService persistence,
        ConversationTurnExecutionCoordinator coordinator
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.persistence = persistence;
        this.coordinator = coordinator;
    }

    /**
 * 设置{@code JsonMapper}。
 * Allows the platform JsonMapper to be used while keeping focused embedders source-compatible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJsonMapper(JsonMapper jsonMapper) {
        if (jsonMapper != null) {
            this.jsonMapper = jsonMapper;
        }
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    public ConversationTurnView start(
        Long conversationId,
        CreateConversationTurnRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, conversationId, "invoke");
        TurnStart start = persistence.begin(principal, conversationId, request);
        if (start.replayed()) {
            return ConversationTurnView.from(start.turn(), true);
        }
        try {
            coordinator.launch(start.turn().getId(), start.runtimeRequest());
        } catch (RuntimeException exception) {
            persistence.finish(start.turn().getId(), "failed", "", exception);
            throw exception;
        }
        return ConversationTurnView.from(start.turn(), false);
    }

    /**
     * 获取{@code get}。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    public ConversationTurnView get(Long conversationId, Long turnId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, conversationId, "view");
        return persistence.ownedTurn(principal, conversationId, turnId);
    }

    /**
 * 处理{@code retry}并返回对应结果。
 * Starts a new owner-bound turn while retaining the failed source trace. */
    public ConversationTurnView retry(Long conversationId, String sourceTraceId, RetryConversationTurnRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, conversationId, "invoke");
        TurnStart start = persistence.retry(principal, conversationId, sourceTraceId, request);
        if (start.replayed()) {
            return ConversationTurnView.from(start.turn(), true);
        }
        try {
            coordinator.launch(start.turn().getId(), start.runtimeRequest());
        } catch (RuntimeException exception) {
            persistence.finish(start.turn().getId(), "failed", "", exception);
            throw exception;
        }
        return ConversationTurnView.from(start.turn(), false);
    }

    /**
     * 处理{@code active}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    public ConversationTurnView active(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, conversationId, "view");
        return persistence.ownedActiveTurn(principal, conversationId);
    }

    /**
     * 处理{@code stop}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    public ConversationTurnView stop(Long conversationId, Long turnId, String reason) {
        return stopWithOutcome(conversationId, turnId, reason).turn();
    }

    /**
 * 处理{@code resumeFromConfirmation}并返回对应结果。
 * Resumes the original private-chat runtime after its confirmation card is decided. */
    @Transactional(rollbackFor = Exception.class)
    public ConversationTurnView resumeFromConfirmation(
        AgentRuntimeConfirmation confirmation,
        CurrentPrincipal principal,
        List<Map<String, Object>> actions,
        RuntimeResumeDecision decision
    ) {
        AgentRunRequest frozen = persistence.claimConfirmationResume(confirmation, principal);
        AgentResumeRequest resume = new AgentResumeRequest(
            frozen.executionKey(), frozen.userId(), frozen.conversationId(), null, null, null,
            frozen.sessionId(), confirmation.getReplyId(), decision, actions,
            Map.of("source", "runtime_confirmation", "confirmationId", confirmation.getConfirmationKey(),
                "actorId", principal.id()), RuntimeResumeMode.APPROVAL
        ).withRuntimeContext(frozen);
        Runnable launch = () -> coordinator.launchResume(confirmation.getConversationTurnId(), resume, frozen);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 处理{@code afterCommit}相关逻辑。
                 */
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            launch.run();
        }
        return persistence.ownedTurn(principal, frozen.conversationId(), confirmation.getConversationTurnId());
    }

    /**
 * 处理resumeFrom用户追问并返回对应结果。
 * Resumes the original private-chat runtime with the answer to ask_user_question. */
    @Transactional(rollbackFor = Exception.class)
    public ConversationTurnView resumeFromUserQuestion(
        AgentRuntimeUserQuestion question,
        CurrentPrincipal principal,
        Map<String, Object> toolResult
    ) {
        AgentRunRequest frozen = persistence.claimUserQuestionResume(question, principal);
        AgentRunRequest effectiveFrozen = withDatasetSelectionScope(
            frozen, datasetSelectionScope(question)
        );
        Map<String, Object> action = new LinkedHashMap<>();
        String toolCallId = question.getToolCallId();
        action.put("id", toolCallId == null || toolCallId.isBlank()
            ? question.getQuestionId() : toolCallId);
        action.put("name", "ask_user_question");
        action.put("result", toolResult == null ? Map.of() : Map.copyOf(toolResult));
        action.put("succeeded", "submitted".equals(question.getStatus()));
        RuntimeResumeDecision decision = "submitted".equals(question.getStatus())
            ? RuntimeResumeDecision.APPROVE : RuntimeResumeDecision.REJECT;
        AgentResumeRequest resume = new AgentResumeRequest(
            effectiveFrozen.executionKey(), effectiveFrozen.userId(), effectiveFrozen.conversationId(),
            null, null, null, effectiveFrozen.sessionId(), question.getQuestionId(), decision,
            List.of(action), resumeMetadata(question, principal.id()), RuntimeResumeMode.EXTERNAL_EXECUTION
        ).withRuntimeContext(effectiveFrozen);
        Runnable launch = () -> coordinator.launchResume(
            question.getConversationTurnId(), resume, effectiveFrozen
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 处理{@code afterCommit}相关逻辑。
                 */
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            launch.run();
        }
        return persistence.ownedTurn(
            principal, effectiveFrozen.conversationId(), question.getConversationTurnId()
        );
    }

    /**
     * 处理with数据集Selection范围并返回对应结果。
     *
     * @param frozen {@code frozen}参数
     * @param scope 范围参数
     * @return 处理结果
     */
    private AgentRunRequest withDatasetSelectionScope(
        AgentRunRequest frozen,
        Map<String, Object> scope
    ) {
        if (scope == null) {
            return frozen;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(frozen.attributes());
        attributes.put("runtimeResumeDatasetScope", scope);
        return new AgentRunRequest(
            frozen.executionKey(), frozen.userId(), frozen.conversationId(), frozen.taskId(),
            frozen.runId(), frozen.stepId(), frozen.agentVersionId(), frozen.agentName(),
            frozen.sessionId(), frozen.input(), frozen.systemPrompt(), frozen.model(),
            frozen.workspaceKey(), frozen.maxIterations(), frozen.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理resume元数据并返回对应结果。
     *
     * @param question 追问参数
     * @param actorId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> resumeMetadata(AgentRuntimeUserQuestion question, long actorId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "runtime_user_question");
        metadata.put("questionId", question.getQuestionId());
        metadata.put("status", question.getStatus());
        metadata.put("actorId", actorId);
        Map<String, Object> scope = datasetSelectionScope(question);
        if (CHATBI_DATASET_SELECTION_PURPOSE.equals(question.getPurpose()) && scope == null) {
            throw new ServiceException("ChatBI 数据集选择回执无效，无法恢复查询", HttpStatus.CONFLICT);
        }
        if (scope != null) {
            metadata.put("metadata_dataset_scope", scope);
        }
        return Map.copyOf(metadata);
    }

    /**
     * 处理数据集Selection范围并返回对应结果。
     *
     * @param question 追问参数
     * @return 处理结果
     */
    private Map<String, Object> datasetSelectionScope(AgentRuntimeUserQuestion question) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (question == null || !"submitted".equals(question.getStatus())
            || !CHATBI_DATASET_SELECTION_PURPOSE.equals(question.getPurpose())
            || question.isMultiSelect() || question.isAllowCustomInput()) {
            return null;
        }
        List<Map<String, Object>> options = readList(question.getOptionsJson(), QUESTION_OPTIONS);
        List<String> selected = readList(question.getSelectedOptionIdsJson(), SELECTED_OPTION_IDS);
        if (options.isEmpty() || selected.size() != 1) {
            return null;
        }
        Set<String> allowed = new HashSet<>();
        for (Map<String, Object> option : options) {
            if (option == null) {
                continue;
            }
            String id = text(option.get("id"));
            if (!id.isBlank()) {
                allowed.add(id);
            }
        }
        List<String> datasetIds = new ArrayList<>();
        for (String value : selected) {
            String id = value == null ? "" : value.strip();
            if (id.isBlank() || !allowed.contains(id) || !id.matches("[0-9]+")) {
                return null;
            }
            try {
                long numeric = Long.parseLong(id);
                if (numeric <= 0) {
                    return null;
                }
                String canonical = Long.toString(numeric);
                if (!datasetIds.contains(canonical)) {
                    datasetIds.add(canonical);
                }
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return datasetIds.isEmpty() ? null : Map.of(
            "source", "user_question",
            "question_id", question.getQuestionId(),
            "dataset_ids", List.copyOf(datasetIds)
        );
    }

    /**
     * 处理{@code readList}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param type 业务类型
     * @return 符合条件的数据集合
     */
    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> parsed = jsonMapper.readValue(json, type);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
 * 处理{@code stopWithOutcome}并返回对应结果。
 * Stops a turn while exposing whether this JVM actually interrupted its runtime lane. */
    public StopOutcome stopWithOutcome(Long conversationId, Long turnId, String reason) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, conversationId, "stop");
        ConversationTurnView turn = persistence.requestStop(principal, conversationId, turnId);
        if (!"stopping".equals(turn.status())) {
            return new StopOutcome(turn, false);
        }
        String normalizedReason = reason == null || reason.isBlank()
            ? "用户停止会话回复" : reason.strip();
        boolean runtimeInterrupted = coordinator.requestStop(turnId, normalizedReason);
        if (!runtimeInterrupted) {
            // The stop fact is durable.  A different JVM may own the runtime
            // and will observe it through the coordinator's cancellation
            // watcher.  Do not finalize the turn here and race that worker.
        }
        return new StopOutcome(
            persistence.ownedTurn(principal, conversationId, turnId), runtimeInterrupted
        );
    }

    /**
     * 封装{@code StopOutcome}相关的不可变数据。
     */
    public record StopOutcome(
        ConversationTurnView turn,
        boolean runtimeInterrupted
    ) {
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param action {@code action}参数
     */
    private void require(CurrentPrincipal principal, Long conversationId, String action) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, action,
            ResourceState.ACTIVE, true, Set.of(), null
        ));
    }
}
