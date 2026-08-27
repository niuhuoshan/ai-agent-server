package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;
import group.aitools.nhs.platform.runtime.question.domain.AgentRuntimeUserQuestion;
import group.aitools.nhs.platform.connector.mapper.RuntimeAuxiliaryBuiltinMapper;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.service.ConversationAgentRoutingService.RoutedAgent;
import group.aitools.nhs.platform.conversation.web.ConversationTurnView;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.conversation.web.RetryConversationTurnRequest;
import group.aitools.nhs.platform.embed.service.EmbedRuntimeSnapshotFactory;
import group.aitools.nhs.platform.execution.service.PersistedRuntimeEvent;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.memory.service.MemoryRuntimeSnapshotService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeCatalogRoutingService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责会话会话回合Persistence相关的业务编排与领域规则处理。
 * Keeps turn idempotency, messages, attachment binding and terminal state atomic. */
@Service
public class ConversationTurnPersistenceService {

    private static final int MAX_RUNTIME_SNAPSHOT_BYTES = 256 * 1024;
    private static final int MAX_CONTEXT_BYTES = 128 * 1024;
    private static final int MAX_ASSISTANT_BYTES = 1024 * 1024;
    private static final int MAX_SUMMARY_CHARS = 12_000;

    private final PlatformIdGenerator idGenerator;
    private final ConversationTurnMapper mapper;
    private final ConversationAgentRoutingService routingService;
    private final ConversationAttachmentService attachmentService;
    private final ConversationVisionSidecarService visionSidecarService;
    private final EmbedRuntimeSnapshotFactory snapshotFactory;
    private final MemoryRuntimeSnapshotService memorySnapshotService;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<ConversationGovernanceService> governanceProvider;
    private final RuntimeAuxiliaryBuiltinMapper auxiliaryMapper;
    private final KnowledgeCatalogRoutingService knowledgeCatalogRoutingService;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationTurnPersistenceService(
        PlatformIdGenerator idGenerator,
        ConversationTurnMapper mapper,
        ConversationAgentRoutingService routingService,
        ConversationAttachmentService attachmentService,
        ConversationVisionSidecarService visionSidecarService,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        MemoryRuntimeSnapshotService memorySnapshotService,
        JsonMapper jsonMapper,
        ObjectProvider<ConversationGovernanceService> governanceProvider,
        RuntimeAuxiliaryBuiltinMapper auxiliaryMapper,
        KnowledgeCatalogRoutingService knowledgeCatalogRoutingService
    ) {
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.routingService = routingService;
        this.attachmentService = attachmentService;
        this.visionSidecarService = visionSidecarService;
        this.snapshotFactory = snapshotFactory;
        this.memorySnapshotService = memorySnapshotService;
        this.jsonMapper = jsonMapper;
        this.governanceProvider = governanceProvider;
        this.auxiliaryMapper = auxiliaryMapper;
        this.knowledgeCatalogRoutingService = knowledgeCatalogRoutingService;
    }

    /**
     * 创建 {@code ConversationTurnPersistenceService} 实例并初始化所需依赖。
     *
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param routingService {@code routingService}参数
     * @param attachmentService 附件Service参数
     * @param snapshotFactory 快照Factory参数
     * @param memorySnapshotService 记忆快照Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    ConversationTurnPersistenceService(
        PlatformIdGenerator idGenerator,
        ConversationTurnMapper mapper,
        ConversationAgentRoutingService routingService,
        ConversationAttachmentService attachmentService,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        MemoryRuntimeSnapshotService memorySnapshotService,
        JsonMapper jsonMapper
    ) {
        this(
            idGenerator, mapper, routingService, attachmentService, null, snapshotFactory,
            memorySnapshotService, jsonMapper, null, null, null
        );
    }

    /**
 * 处理{@code suspendForConfirmation}并返回对应结果。
 *
     * Persists a private-chat confirmation before releasing the runtime worker.
     * Formal task approvals use the task approval table; private conversations
     * have no task/run identity and therefore keep their immutable action
     * snapshot in the runtime confirmation table instead.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean suspendForConfirmation(
        Long turnId,
        AgentRunRequest request,
        PersistedRuntimeEvent persisted,
        String responseDraft
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (auxiliaryMapper == null || request == null || persisted == null
            || persisted.source().type() != RuntimeEventType.APPROVAL_REQUIRED) {
            return false;
        }
        AgentConversationTurn turn = mapper.lockTurn(turnId);
        if (turn == null || !"running".equals(turn.getStatus())
            || !turnId.equals(positiveLong(request.attributes().get("conversationTurnId")))) {
            return false;
        }
        RuntimeEvent source = persisted.source();
        List<Map<String, Object>> actions = confirmationActions(source.payload().get("toolCalls"));
        if (actions.size() != 1
            || !"request_user_confirmation".equals(normalizedActionName(actions.getFirst().get("name")))) {
            return false;
        }
        Map<String, Object> action = actions.getFirst();
        Map<String, Object> input = action.get("input") instanceof Map<?, ?> raw
            ? stringMap(raw) : Map.of();
        String title = required(textValue(input.get("title")), 255, "确认标题");
        Object rawFields = input.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty() || fields.size() > 32) {
            return false;
        }
        String replyId = required(textValue(source.payload().get("replyId")), 128, "确认回复标识");
        String fieldsJson = jsonMapper.writeValueAsString(fields);
        String uiJson = jsonMapper.writeValueAsString(input);
        String actionsJson = jsonMapper.writeValueAsString(actions);
        LocalDateTime now = utcNow();
        int inserted = auxiliaryMapper.insertConversationConfirmation(
            idGenerator.nextId(), replyId, request.userId(), request.executionKey().executionId(),
            request.conversationId(), turnId, persisted.view().eventId(), replyId,
            required(textValue(action.get("id")), 128, "确认工具调用标识"),
            "request_user_confirmation", title, fieldsJson, uiJson, actionsJson,
            now.plusHours(24), now
        );
        if (inserted == 0) {
            AgentRuntimeConfirmation existing = auxiliaryMapper.lockConfirmation(replyId, request.userId());
            if (existing == null || !turnId.equals(existing.getConversationTurnId())) {
                throw conflict("业务确认持久化发生幂等冲突");
            }
        }
        if (mapper.markWaitingConfirmation(turnId, boundedResponse(responseDraft)) != 1
            && !"waiting_confirmation".equals(mapper.lockTurn(turnId).getStatus())) {
            throw conflict("会话回合无法进入待确认状态");
        }
        return true;
    }

    /**
 * 处理suspendFor用户追问并返回对应结果。
 * Pauses a private conversation after ask_user_question has been persisted. */
    @Transactional(rollbackFor = Exception.class)
    public boolean suspendForUserQuestion(
        Long turnId,
        AgentRunRequest request,
        PersistedRuntimeEvent persisted,
        String responseDraft
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (request == null || persisted == null
            || (persisted.source().type() != RuntimeEventType.TOOL_RESULT_DELTA
                && persisted.source().type() != RuntimeEventType.TOOL_RESULT_FINISHED)) {
            return false;
        }
        Map<String, Object> projection = persisted.view().projection();
        if (!(projection.get("userQuestion") instanceof Map<?, ?> rawQuestion)) {
            return false;
        }
        Map<String, Object> question = stringMap(rawQuestion);
        if (!"pending".equalsIgnoreCase(String.valueOf(question.get("status")))) {
            return false;
        }
        String questionId = textValue(question.get("questionId"));
        if (questionId.isBlank()) {
            questionId = textValue(question.get("question_id"));
        }
        Long expectedTurnId = positiveLong(request.attributes().get("conversationTurnId"));
        if (questionId.isBlank() || !turnId.equals(expectedTurnId)) {
            return false;
        }
        AgentConversationTurn turn = mapper.lockTurn(turnId);
        if (turn == null || !"running".equals(turn.getStatus())) {
            return false;
        }
        persistRuntimeSnapshot(turnId, request);
        if (mapper.markWaitingUserQuestion(turnId, boundedResponse(responseDraft)) != 1
            && !"waiting_user_question".equals(mapper.lockTurn(turnId).getStatus())) {
            throw conflict("会话回合无法进入待回答状态");
        }
        return true;
    }

    /**
     * 处理persist运行时快照相关逻辑。
     *
     * @param turnId 资源标识
     * @param request 请求参数
     */
    private void persistRuntimeSnapshot(Long turnId, AgentRunRequest request) {
        String snapshot;
        try {
            snapshot = jsonMapper.writeValueAsString(request);
        } catch (RuntimeException exception) {
            throw conflict("会话恢复快照无法序列化");
        }
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_RUNTIME_SNAPSHOT_BYTES) {
            throw conflict("会话恢复快照超过256KB");
        }
        if (mapper.updateRuntimeSnapshot(turnId, snapshot) != 1) {
            throw conflict("会话恢复快照保存失败");
        }
    }

    /**
     * 处理{@code claimConfirmationResume}并返回对应结果。
     *
     * @param confirmation {@code confirmation}参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunRequest claimConfirmationResume(
        group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation confirmation,
        CurrentPrincipal principal
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (confirmation == null || confirmation.getConversationTurnId() == null
            || !principal.id().equals(confirmation.getOwnerId())) {
            throw new ServiceException("业务确认不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        AgentConversationTurn turn = mapper.lockTurn(confirmation.getConversationTurnId());
        if (turn == null || !principal.id().equals(turn.getUserId())
            || !"waiting_confirmation".equals(turn.getStatus())) {
            throw conflict("业务确认关联的会话回合不再等待恢复");
        }
        AgentRunRequest frozen;
        try {
            frozen = jsonMapper.readValue(turn.getRuntimeSnapshotJson(), AgentRunRequest.class);
        } catch (RuntimeException exception) {
            throw conflict("会话运行快照无法解析");
        }
        if (!frozen.userId().equals(principal.id())
            || !frozen.executionKey().executionId().equals(confirmation.getExecutionId())
            || !frozen.conversationId().equals(confirmation.getConversationId())) {
            throw new ServiceException("业务确认运行身份不一致", HttpStatus.FORBIDDEN);
        }
        if (mapper.claimConfirmationResume(turn.getId(), principal.id()) != 1) {
            throw conflict("业务确认关联的会话回合已被其他操作恢复");
        }
        return frozen;
    }

    /**
     * 处理claim用户追问Resume并返回对应结果。
     *
     * @param question 追问参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunRequest claimUserQuestionResume(
        AgentRuntimeUserQuestion question,
        CurrentPrincipal principal
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (question == null || question.getConversationTurnId() == null
            || principal == null || !principal.id().equals(question.getOwnerId())) {
            throw new ServiceException("用户提问不存在或无权恢复", HttpStatus.NOT_FOUND);
        }
        AgentConversationTurn turn = mapper.lockTurn(question.getConversationTurnId());
        if (turn == null || !principal.id().equals(turn.getUserId())
            || !"waiting_user_question".equals(turn.getStatus())) {
            throw conflict("用户提问关联的会话回合不再等待恢复");
        }
        AgentRunRequest frozen;
        try {
            frozen = jsonMapper.readValue(turn.getRuntimeSnapshotJson(), AgentRunRequest.class);
        } catch (RuntimeException exception) {
            throw conflict("会话运行快照无法解析");
        }
        if (!frozen.userId().equals(principal.id())
            || !frozen.executionKey().executionId().equals(question.getExecutionId())
            || !frozen.conversationId().equals(question.getConversationId())) {
            throw new ServiceException("用户提问运行身份不一致", HttpStatus.FORBIDDEN);
        }
        if (mapper.claimUserQuestionResume(turn.getId(), principal.id()) != 1) {
            throw conflict("用户提问关联的会话回合已被其他操作恢复");
        }
        return frozen;
    }

    /**
     * 处理{@code begin}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TurnStart begin(
        CurrentPrincipal principal,
        Long conversationId,
        CreateConversationTurnRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        AgentConversation conversation = mapper.lockOwnedActiveConversation(conversationId, principal.id());
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        String normalizedKey = required(request.idempotencyKey(), 128, "会话回合幂等键");
        String keyHash = ContentHashing.sha256(normalizedKey);
        String requestHash = requestHash(request);
        AgentConversationTurn existing = mapper.selectTurnByKey(conversationId, keyHash);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw conflict("同一会话回合幂等键不能用于不同请求");
            }
            return new TurnStart(existing, null, true);
        }
        AgentConversationTurn active = mapper.selectActiveTurn(conversationId);
        if (active != null) {
            throw conflict("当前会话仍有回合在执行");
        }

        RoutedAgent routed = routingService.route(principal, conversation, request);
        List<AgentConversationAttachment> attachments = attachmentService.requireReady(
            conversationId, principal.id(), request.attachmentIds()
        );
        Long turnId = idGenerator.nextId();
        String traceId = ContentHashing.sha256(
            "conversation-turn\0" + conversationId + "\0" + principal.id() + "\0" + normalizedKey
        );
        String runtimeInput = contextInput(conversation, routed.input(), attachments);
        ConversationTurnDecision decision = ConversationTurnDecision.classify(
            routed.input(), routed.routeSource(), routed.definition().getAgentKey(),
            knowledgeCatalogRoutingService == null
                ? null : knowledgeCatalogRoutingService.snapshot(principal)
        );
        Map<String, Object> routingDecision = new LinkedHashMap<>(decision.toMap());
        routingDecision.put("agentRouteSource", routed.routeSource());
        routingDecision.put("agentRouteConfidence", routed.routeConfidence());
        routingDecision.put("agentRouteReason", routed.routeReason());
        routingDecision.put("candidateCount", routed.candidateCount());
        routingDecision.put("agentId", routed.definition().getAgentId());
        routingDecision.put("agentVersionId", routed.definition().getAgentVersionId());
        List<Map<String, Object>> media = attachmentService.runtimeMedia(attachments);
        String effectiveInput = routed.input();
        AgentRunRequest runtimeRequest = snapshotFactory.buildHumanConversation(
            principal, conversationId, conversation.getSessionKey(), turnId, traceId,
            routed.definition().getAgentVersionId(), runtimeInput,
            memorySnapshotService.snapshotConversation(principal, conversation.getProjectId()),
            routingDecision
        );
        runtimeRequest = applyConversationScope(principal, conversationId, runtimeRequest);
        ConversationVisionSidecarService.Prepared vision = visionSidecarService == null
            ? new ConversationVisionSidecarService.Prepared(effectiveInput, "", "", !media.isEmpty())
            : visionSidecarService.prepare(principal, runtimeRequest, effectiveInput, media);
        if (!effectiveInput.equals(vision.input())) {
            effectiveInput = vision.input();
            runtimeInput = contextInput(conversation, effectiveInput, attachments);
            runtimeRequest = snapshotFactory.buildHumanConversation(
                principal, conversationId, conversation.getSessionKey(), turnId, traceId,
                routed.definition().getAgentVersionId(), runtimeInput,
                memorySnapshotService.snapshotConversation(principal, conversation.getProjectId()),
                routingDecision
            );
            runtimeRequest = applyConversationScope(principal, conversationId, runtimeRequest);
        }
        runtimeRequest = withVisionMetadata(runtimeRequest, vision);
        AgentRunRequest persistedRequest = stripRuntimeMedia(runtimeRequest);
        runtimeRequest = withRuntimeMedia(runtimeRequest, vision.attachMedia() ? media : List.of());
        String runtimeJson = jsonMapper.writeValueAsString(persistedRequest);
        if (runtimeJson.getBytes(StandardCharsets.UTF_8).length > MAX_RUNTIME_SNAPSHOT_BYTES) {
            throw new ServiceException("会话运行快照超过256KB", HttpStatus.BAD_REQUEST);
        }

        LocalDateTime now = utcNow();
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(turnId);
        turn.setConversationId(conversationId);
        turn.setUserId(principal.id());
        turn.setIdempotencyHash(keyHash);
        turn.setRequestHash(requestHash);
        turn.setTraceId(traceId);
        turn.setAgentId(routed.definition().getAgentId());
        turn.setAgentVersionId(routed.definition().getAgentVersionId());
        turn.setStatus("running");
        turn.setRuntimeSnapshotJson(runtimeJson);
        turn.setStartedAt(now);
        if (mapper.insertTurn(turn) != 1) {
            AgentConversationTurn raced = mapper.selectTurnByKey(conversationId, keyHash);
            if (raced != null && requestHash.equals(raced.getRequestHash())) {
                return new TurnStart(raced, null, true);
            }
            throw conflict("会话回合幂等写入冲突");
        }
        for (AgentConversationAttachment attachment : attachments) {
            if (mapper.bindAttachment(
                attachment.getId(), conversationId, principal.id(), turnId
            ) != 1) {
                throw conflict("附件在回合创建期间被并发使用");
            }
        }
        int sequence = mapper.nextMessageSequence(conversationId);
        Map<String, Object> messageMetadata = new LinkedHashMap<>();
        messageMetadata.put("source", "platform");
        messageMetadata.put("turnId", turnId);
        messageMetadata.put("attachmentIds", request.attachmentIds());
        messageMetadata.put("routingDecision", routingDecision);
        Map<String, Object> visionMetadata = visionSidecarService == null
            ? Map.of() : visionSidecarService.metadata(vision);
        if (!visionMetadata.isEmpty()) {
            messageMetadata.put("visionSidecar", visionMetadata);
        }
        if (routed.mentionToken() != null) {
            messageMetadata.put("mention", routed.mentionToken());
        }
        if (mapper.insertMessage(
            idGenerator.nextId(), conversationId, sequence, traceId, "user",
            effectiveInput.strip(), jsonMapper.writeValueAsString(messageMetadata),
            routed.definition().getAgentId(), routed.definition().getAgentVersionId(),
            "completed", now
        ) != 1 || mapper.touchConversation(
            conversationId, principal.id(), routed.definition().getAgentId(),
            routed.definition().getAgentVersionId(), now
        ) != 1) {
            throw conflict("会话消息持久化失败");
        }
        return new TurnStart(turn, runtimeRequest, false);
    }

    /**
 * 处理{@code retry}并返回对应结果。
 *
     * Replays the original user input as a new durable turn.  The failed turn
     * remains immutable, including its messages and trace; only text-only
     * turns are eligible because bound attachments cannot be silently reused.
     */
    @Transactional(rollbackFor = Exception.class)
    public TurnStart retry(
        CurrentPrincipal principal,
        Long conversationId,
        String sourceTraceId,
        RetryConversationTurnRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String trace = required(sourceTraceId, 64, "失败回合 Trace");
        if (!trace.matches("[0-9a-fA-F]{64}")) {
            throw new ServiceException("失败回合 Trace 无效", HttpStatus.BAD_REQUEST);
        }
        AgentConversationTurn source = mapper.selectOwnedTurnByTrace(trace, principal.id());
        if (source == null || !conversationId.equals(source.getConversationId())) {
            throw new ServiceException("失败回合不存在", HttpStatus.NOT_FOUND);
        }
        if (!"failed".equals(source.getStatus())) {
            throw conflict("只有失败回合可以重试");
        }
        List<AgentConversationAttachment> bound = mapper.selectOwnedAttachments(
            source.getConversationId(), principal.id(), 100
        ).stream().filter(item -> source.getId().equals(item.getTurnId())).toList();
        if (!bound.isEmpty()) {
            throw conflict("包含附件的失败回合不能直接重试，请从原用户消息创建分支");
        }
        ConversationMessageRow original = mapper.selectUserMessageByTrace(
            source.getConversationId(), source.getTraceId()
        );
        if (original == null || original.getContent() == null || original.getContent().isBlank()) {
            throw conflict("失败回合缺少可重试的用户消息");
        }
        CreateConversationTurnRequest replay = new CreateConversationTurnRequest(
            request.idempotencyKey(), original.getContent(), source.getAgentId(),
            source.getAgentVersionId(), List.of()
        );
        return begin(principal, conversationId, replay);
    }

    /**
     * 处理owned会话回合并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    public ConversationTurnView ownedTurn(
        CurrentPrincipal principal,
        Long conversationId,
        Long turnId
    ) {
        AgentConversationTurn turn = mapper.selectOwnedTurn(conversationId, turnId, principal.id());
        if (turn == null) {
            throw new ServiceException("会话回合不存在", HttpStatus.NOT_FOUND);
        }
        return ConversationTurnView.from(turn, false);
    }

    /**
     * 处理ownedActive会话回合并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @return 处理结果
     */
    public ConversationTurnView ownedActiveTurn(
        CurrentPrincipal principal,
        Long conversationId
    ) {
        AgentConversation conversation = mapper.selectOwnedActiveConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        AgentConversationTurn turn = mapper.selectActiveTurn(conversationId);
        if (turn == null) {
            return null;
        }
        if (!principal.id().equals(turn.getUserId())) {
            throw new SecurityException("活跃会话回合所有者不一致");
        }
        return ConversationTurnView.from(turn, false);
    }

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationTurnView requestStop(
        CurrentPrincipal principal,
        Long conversationId,
        Long turnId
    ) {
        AgentConversationTurn turn = mapper.selectOwnedTurn(conversationId, turnId, principal.id());
        if (turn == null) {
            throw new ServiceException("会话回合不存在", HttpStatus.NOT_FOUND);
        }
        boolean waitingConfirmation = "waiting_confirmation".equals(turn.getStatus());
        boolean waitingUserQuestion = "waiting_user_question".equals(turn.getStatus());
        if ("running".equals(turn.getStatus()) || waitingConfirmation || waitingUserQuestion) {
            mapper.requestStop(turnId, principal.id(), utcNow());
            turn.setStatus("stopping");
            if (waitingConfirmation || waitingUserQuestion) {
                finish(turnId, "cancelled", "", new IllegalStateException(
                    waitingConfirmation ? "用户取消待确认会话" : "用户取消待回答会话"
                ));
                turn.setStatus("cancelled");
            }
        }
        return ConversationTurnView.from(turn, false);
    }

    /**
 * 处理{@code stopRequested}并返回对应结果。
 * Returns the durable cancellation fact used by cross-JVM workers. */
    public boolean stopRequested(Long turnId) {
        return Boolean.TRUE.equals(mapper.stopRequested(turnId));
    }

    /**
     * 处理{@code finish}相关逻辑。
     *
     * @param turnId 资源标识
     * @param requestedStatus 目标状态
     * @param response {@code response}参数
     * @param error {@code error}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(
        Long turnId,
        String requestedStatus,
        String response,
        Throwable error
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        AgentConversationTurn turn = mapper.lockTurn(turnId);
        if (turn == null || !List.of("running", "stopping").contains(turn.getStatus())) {
            return;
        }
        String status = "stopping".equals(turn.getStatus()) ? "cancelled" : requestedStatus;
        if (!List.of("succeeded", "failed", "cancelled").contains(status)) {
            status = "failed";
        }
        String draft = turn.getResponseDraft() == null ? "" : turn.getResponseDraft().strip();
        String continuation = response == null ? "" : response.strip();
        String content = boundedResponse(draft.isBlank() ? continuation
            : continuation.isBlank() ? draft : draft + continuation);
        String messageStatus = switch (status) {
            case "succeeded" -> "completed";
            case "cancelled" -> "cancelled";
            default -> "failed";
        };
        LocalDateTime now = utcNow();
        AgentConversation conversation = mapper.lockOwnedActiveConversation(
            turn.getConversationId(), turn.getUserId()
        );
        if (conversation == null) {
            throw conflict("会话在回合结束前已不可用");
        }
        int sequence = mapper.nextMessageSequence(turn.getConversationId());
        Map<String, Object> metadata = Map.of("source", "platform", "turnId", turnId);
        if (mapper.insertMessage(
            idGenerator.nextId(), turn.getConversationId(), sequence, turn.getTraceId(),
            "assistant", content, jsonMapper.writeValueAsString(metadata), turn.getAgentId(),
            turn.getAgentVersionId(), messageStatus, now
        ) != 1 || mapper.finishTurn(turnId, status, safeError(error), now) != 1) {
            throw conflict("会话回合终态持久化失败");
        }
        String summary = rollingSummary(conversation.getSummary(), content, status);
        mapper.updateConversationSummary(
            turn.getConversationId(), turn.getUserId(), summary, now
        );
    }

    /**
     * 处理上下文Input并返回对应结果。
     *
     * @param conversation 会话参数
     * @param input {@code input}参数
     * @param attachments {@code attachments}参数
     * @return 处理结果
     */
    private String contextInput(
        AgentConversation conversation,
        String input,
        List<AgentConversationAttachment> attachments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String current = "[当前用户请求]\n" + input + attachmentService.promptSection(attachments);
        if (bytes(current) > MAX_CONTEXT_BYTES) {
            throw new ServiceException("消息与附件上下文合计超过128KB", HttpStatus.BAD_REQUEST);
        }
        List<String> optional = new ArrayList<>();
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            optional.add("[会话滚动摘要]\n" + conversation.getSummary().strip());
        }
        List<ConversationMessageRow> recent = new ArrayList<>(conversation.getParentConversationId() == null
            ? mapper.selectRecentMessages(conversation.getId(), 24)
            : mapper.selectContextMessages(conversation.getId(), 24));
        recent.sort(Comparator.comparingInt(row -> row.getSequenceNo() == null ? 0 : row.getSequenceNo()));
        if (!recent.isEmpty()) {
            StringBuilder history = new StringBuilder("[近期对话]\n");
            for (ConversationMessageRow message : recent) {
                if (message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                history.append("user".equals(message.getRole()) ? "用户: " : "助手: ")
                    .append(message.getContent().strip()).append('\n');
            }
            optional.add(history.toString().strip());
        }
        StringBuilder result = new StringBuilder();
        for (String section : optional) {
            String candidate = result + section + "\n\n" + current;
            if (bytes(candidate) <= MAX_CONTEXT_BYTES) {
                result.append(section).append("\n\n");
            }
        }
        result.append(current);
        return result.toString();
    }

    /**
     * 处理apply会话范围并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    private AgentRunRequest applyConversationScope(
        CurrentPrincipal principal,
        Long conversationId,
        AgentRunRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        ConversationGovernanceService governance = governanceProvider == null
            ? null : governanceProvider.getIfAvailable();
        if (governance == null) {
            return request;
        }
        Map<String, List<Long>> scope = governance.runtimeScope(principal, conversationId);
        if (scope.isEmpty()) {
            return request;
        }
        List<Long> allowedVersions = scope.get("agent_version_ids");
        if (allowedVersions != null && !allowedVersions.contains(request.agentVersionId())) {
            throw new ServiceException("当前Agent版本不在会话资源范围内", HttpStatus.FORBIDDEN);
        }

        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        List<Map<String, Object>> bindings = filterBindings(attributes.get("resourceBindings"), scope);
        attributes.put("resourceBindings", bindings);
        attributes.put("conversationResourceScope", scope);
        Object rawSnapshot = attributes.get("taskResourceSnapshot");
        if (rawSnapshot instanceof Map<?, ?> snapshot) {
            Map<String, Object> filteredSnapshot = stringMap(snapshot);
            filteredSnapshot.put("resources", filterBindings(snapshot.get("resources"), scope));
            attributes.put("taskResourceSnapshot", Map.copyOf(filteredSnapshot));
        }

        Map<String, Object> authorization = new LinkedHashMap<>(request.authorizationSnapshot());
        authorization.put("conversationResourceScope", scope);
        return new AgentRunRequest(
            request.executionKey(), request.userId(), request.conversationId(), request.taskId(),
            request.runId(), request.stepId(), request.agentVersionId(), request.agentName(),
            request.sessionId(), request.input(), request.systemPrompt(), request.model(),
            request.workspaceKey(), request.maxIterations(), Map.copyOf(authorization),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理with运行时Media并返回对应结果。
     *
     * @param request 请求参数
     * @param media {@code media}参数
     * @return 处理结果
     */
    private AgentRunRequest withRuntimeMedia(
        AgentRunRequest request,
        List<Map<String, Object>> media
    ) {
        if (media.isEmpty()) {
            return request;
        }
        if (!Boolean.TRUE.equals(request.attributes().get("modelSupportsVision"))) {
            throw new ServiceException(
                "当前Agent模型不支持图片理解，请切换到多模态模型后重试",
                HttpStatus.CONFLICT
            );
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        attributes.put("embedMedia", media);
        return new AgentRunRequest(
            request.executionKey(), request.userId(), request.conversationId(), request.taskId(),
            request.runId(), request.stepId(), request.agentVersionId(), request.agentName(),
            request.sessionId(), request.input(), request.systemPrompt(), request.model(),
            request.workspaceKey(), request.maxIterations(), request.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理withVision元数据并返回对应结果。
     *
     * @param request 请求参数
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private AgentRunRequest withVisionMetadata(
        AgentRunRequest request,
        ConversationVisionSidecarService.Prepared prepared
    ) {
        if (prepared == null || !prepared.usedSidecar()) {
            return request;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        attributes.put("visionSidecar", Map.of(
            "modelKey", prepared.modelKey(),
            "captionChars", prepared.caption().length(),
            "source", "vision_sidecar"
        ));
        return new AgentRunRequest(
            request.executionKey(), request.userId(), request.conversationId(), request.taskId(),
            request.runId(), request.stepId(), request.agentVersionId(), request.agentName(),
            request.sessionId(), request.input(), request.systemPrompt(), request.model(),
            request.workspaceKey(), request.maxIterations(), request.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理strip运行时Media并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private AgentRunRequest stripRuntimeMedia(AgentRunRequest request) {
        if (!request.attributes().containsKey("embedMedia")) {
            return request;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        attributes.remove("embedMedia");
        return new AgentRunRequest(
            request.executionKey(), request.userId(), request.conversationId(), request.taskId(),
            request.runId(), request.stepId(), request.agentVersionId(), request.agentName(),
            request.sessionId(), request.input(), request.systemPrompt(), request.model(),
            request.workspaceKey(), request.maxIterations(), request.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理{@code filterBindings}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param scope 范围参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> filterBindings(
        Object value,
        Map<String, List<Long>> scope
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> binding)) {
                continue;
            }
            Map<String, Object> normalized = stringMap(binding);
            String type = String.valueOf(normalized.get("resourceType"));
            String scopeKey = switch (type) {
                case "tool" -> "tool_ids";
                case "skill" -> "skill_ids";
                case "knowledge_base" -> "knowledge_base_ids";
                case "dataset" -> "dataset_ids";
                default -> null;
            };
            List<Long> allowed = scopeKey == null ? null : scope.get(scopeKey);
            Long id = positiveLong(normalized.get("resourceId"));
            if (allowed == null || id != null && allowed.contains(id)) {
                result.add(Map.copyOf(normalized));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            return null;
        }
        return number.longValue();
    }

    /**
     * 处理{@code textValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String textValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).strip();
        return text.equalsIgnoreCase("null") ? "" : text;
    }

    /**
     * 处理{@code normalizedActionName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizedActionName(Object value) {
        return textValue(value).toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }

    /**
     * 处理{@code confirmationActions}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> confirmationActions(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(value instanceof List<?> raw) || raw.isEmpty() || raw.size() > 32) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> source)) {
                return List.of();
            }
            Map<String, Object> action = stringMap(source);
            if (textValue(action.get("id")).isBlank() || textValue(action.get("name")).isBlank()) {
                return List.of();
            }
            actions.add(Map.copyOf(action));
        }
        return List.copyOf(actions);
    }

    /**
     * 处理{@code requestHash}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private String requestHash(CreateConversationTurnRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("input", required(request.input(), 131072, "会话消息"));
        value.put("agentId", request.agentId());
        value.put("agentVersionId", request.agentVersionId());
        value.put("attachmentIds", request.attachmentIds());
        return ContentHashing.sha256(jsonMapper.writeValueAsString(value));
    }

    /**
     * 处理{@code rollingSummary}并返回对应结果。
     *
     * @param previous {@code previous}参数
     * @param response {@code response}参数
     * @param status 目标状态
     * @return 处理结果
     */
    private String rollingSummary(String previous, String response, String status) {
        StringBuilder result = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            result.append(previous.strip()).append('\n');
        }
        result.append("最近一次助手回复(").append(status).append("): ")
            .append(response == null || response.isBlank() ? "无正文" : response.strip());
        if (result.length() > MAX_SUMMARY_CHARS) {
            int start = result.length() - MAX_SUMMARY_CHARS;
            if (start > 0 && Character.isLowSurrogate(result.charAt(start))
                && Character.isHighSurrogate(result.charAt(start - 1))) {
                start++;
            }
            return result.substring(start);
        }
        return result.toString();
    }

    /**
     * 处理{@code boundedResponse}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String boundedResponse(String value) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_ASSISTANT_BYTES) {
            return normalized;
        }
        int end = Math.min(normalized.length(), MAX_ASSISTANT_BYTES);
        while (end > 0 && normalized.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_ASSISTANT_BYTES) {
            end -= Math.max(1, end / 32);
        }
        end = safeHeadEnd(normalized, end);
        return normalized.substring(0, end);
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "过长或包含非法字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        String normalized = value
            .replaceAll("agk_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[redacted]")
            .replace('\0', ' ').strip();
        return normalized.length() <= 2000
            ? normalized : normalized.substring(0, safeHeadEnd(normalized, 2000));
    }

    /**
     * 处理{@code safeHeadEnd}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param end {@code end}参数
     * @return 处理结果
     */
    private int safeHeadEnd(String value, int end) {
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
            && Character.isLowSurrogate(value.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    /**
     * 处理{@code bytes}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 封装会话回合Start相关的不可变数据。
     */
    public record TurnStart(
        AgentConversationTurn turn,
        AgentRunRequest runtimeRequest,
        boolean replayed
    ) {
    }
}
