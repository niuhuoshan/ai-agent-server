package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.web.EmbedSessionView;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责嵌入式会话对话Persistence相关的业务编排与领域规则处理。
 */
@Service
public class EmbedChatPersistenceService {

    private final EmbedChatMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final EmbedRuntimeSnapshotFactory snapshotFactory;
    private final JsonMapper jsonMapper;
    private final EmbedAttachmentService attachmentService;

    /**
     * 创建 {@code EmbedChatPersistenceService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param snapshotFactory 快照Factory参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param attachmentService 附件Service参数
     */
    @Autowired
    public EmbedChatPersistenceService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        JsonMapper jsonMapper,
        EmbedAttachmentService attachmentService
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.snapshotFactory = snapshotFactory;
        this.jsonMapper = jsonMapper;
        this.attachmentService = attachmentService;
    }

    /**
     * 创建 {@code EmbedChatPersistenceService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param snapshotFactory 快照Factory参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    EmbedChatPersistenceService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        EmbedRuntimeSnapshotFactory snapshotFactory,
        JsonMapper jsonMapper
    ) {
        this(mapper, idGenerator, snapshotFactory, jsonMapper, null);
    }

    /**
     * 创建并保存会话。
     *
     * @param authenticated {@code authenticated}参数
     * @param agentVersionId 资源标识
     * @param externalUserKey external用户Key参数
     * @param expiresInMinutes {@code expiresInMinutes}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedSessionView createSession(
        AuthenticatedServiceAccount authenticated,
        Long agentVersionId,
        String externalUserKey,
        int expiresInMinutes
    ) {
        String external = required(externalUserKey, "Embed外部用户标识", 256);
        return createSessionWithHash(
            authenticated, agentVersionId, ContentHashing.sha256(external), expiresInMinutes
        );
    }

    /**
     * 创建并保存会话WithHash。
     *
     * @param authenticated {@code authenticated}参数
     * @param agentVersionId 资源标识
     * @param externalUserHash external用户Hash参数
     * @param expiresInMinutes {@code expiresInMinutes}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedSessionView createSessionWithHash(
        AuthenticatedServiceAccount authenticated,
        Long agentVersionId,
        String externalUserHash,
        int expiresInMinutes
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        snapshotFactory.validate(authenticated.principal(), agentVersionId);
        if (externalUserHash == null || !externalUserHash.matches("[0-9a-f]{64}")) {
            throw new ServiceException("Embed外部用户标识哈希无效", HttpStatus.BAD_REQUEST);
        }
        LocalDateTime now = utcNow();
        Long conversationId = idGenerator.nextId();
        String sessionKey = "embed-" + idGenerator.nextUuid();
        AgentConversation conversation = new AgentConversation();
        conversation.setId(conversationId);
        conversation.setUserId(authenticated.principal().id());
        conversation.setAgentVersionId(agentVersionId);
        conversation.setPrincipalType("service_account");
        conversation.setTitle("嵌入式会话");
        conversation.setSessionKey(sessionKey);
        conversation.setCreateBy(authenticated.principal().id());
        conversation.setCreateTime(now);
        if (mapper.insertConversation(conversation) != 1) {
            throw conflict("Embed会话创建失败");
        }
        EmbedSession session = new EmbedSession();
        session.setId(idGenerator.nextId());
        session.setSessionKey(sessionKey);
        session.setApplicationId(authenticated.applicationId());
        session.setServiceAccountId(authenticated.principal().id());
        session.setAgentVersionId(agentVersionId);
        session.setConversationId(conversationId);
        session.setExternalUserHash(externalUserHash);
        session.setStatus("active");
        session.setExpiresAt(now.plusMinutes(expiresInMinutes));
        session.setCreatedAt(now);
        if (mapper.insertSession(session) != 1) {
            throw conflict("Embed会话事实创建失败");
        }
        return view(session);
    }

    /**
     * 处理begin会话回合并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TurnStart beginTurn(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        String idempotencyKey,
        String input
    ) {
        String normalizedInput = required(input, "Embed消息", 131072);
        Map<String, Object> content = Map.of("source", "embed");
        EmbedAttachmentService.PreparedMessage prepared = new EmbedAttachmentService.PreparedMessage(
            normalizedInput, normalizedInput, jsonMapper.writeValueAsString(content),
            normalizedInput, List.of()
        );
        return beginPrepared(authenticated, sessionId, idempotencyKey, prepared);
    }

    /**
     * 处理beginWidget会话回合并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @param attachmentIds 资源标识集合
     * @param context 待处理内容
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TurnStart beginWidgetTurn(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        String idempotencyKey,
        String input,
        List<Long> attachmentIds,
        Map<String, Object> context
    ) {
        if (attachmentService == null) {
            throw new IllegalStateException("Embed附件服务未启用");
        }
        EmbedSession session = requireOwnedActiveSession(authenticated, sessionId, true);
        String normalizedInput = required(input, "Embed消息", 65536);
        String normalizedKey = required(idempotencyKey, "Embed回合幂等键", 128);
        EmbedAttachmentService.PreparedRequest request = attachmentService.prepareRequest(
            normalizedInput, attachmentIds, context
        );
        String keyHash = ContentHashing.sha256(normalizedKey);
        String requestHash = ContentHashing.sha256(request.requestMaterial());
        EmbedTurn existing = mapper.selectTurnByKey(session.getId(), keyHash);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw conflict("同一Embed幂等键不能用于不同消息");
            }
            return new TurnStart(session, existing, true, normalizedInput, List.of());
        }
        EmbedAttachmentService.PreparedMessage prepared = attachmentService.prepare(
            authenticated, session, request
        );
        return beginPrepared(authenticated, session, normalizedKey, prepared);
    }

    /**
     * 处理{@code beginPrepared}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private TurnStart beginPrepared(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        String idempotencyKey,
        EmbedAttachmentService.PreparedMessage prepared
    ) {
        EmbedSession session = requireOwnedActiveSession(authenticated, sessionId, true);
        return beginPrepared(authenticated, session, idempotencyKey, prepared);
    }

    /**
     * 处理{@code beginPrepared}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param session 会话参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private TurnStart beginPrepared(
        AuthenticatedServiceAccount authenticated,
        EmbedSession session,
        String idempotencyKey,
        EmbedAttachmentService.PreparedMessage prepared
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        snapshotFactory.validateInput(prepared.runtimeInput());
        String normalizedKey = required(idempotencyKey, "Embed回合幂等键", 128);
        String keyHash = ContentHashing.sha256(normalizedKey);
        String requestHash = ContentHashing.sha256(prepared.requestMaterial());
        EmbedTurn existing = mapper.selectTurnByKey(session.getId(), keyHash);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw conflict("同一Embed幂等键不能用于不同消息");
            }
            return new TurnStart(session, existing, true, prepared.runtimeInput());
        }
        LocalDateTime now = utcNow();
        EmbedTurn turn = new EmbedTurn();
        turn.setId(idGenerator.nextId());
        turn.setSessionId(session.getId());
        turn.setIdempotencyHash(keyHash);
        turn.setRequestHash(requestHash);
        turn.setTraceId(ContentHashing.sha256(
            "embed-turn\0" + session.getId() + "\0" + authenticated.principal().id() + "\0" + normalizedKey
        ));
        turn.setStatus("running");
        turn.setStartedAt(now);
        if (mapper.insertTurn(turn) != 1) {
            EmbedTurn raced = mapper.selectTurnByKey(session.getId(), keyHash);
            if (raced == null || !requestHash.equals(raced.getRequestHash())) {
                throw conflict("Embed回合幂等写入冲突");
            }
            return new TurnStart(session, raced, true, prepared.runtimeInput(), List.of());
        }
        int sequence = mapper.nextMessageSequence(session.getConversationId());
        if (mapper.insertUserMessage(
            idGenerator.nextId(), session.getConversationId(), sequence, turn.getTraceId(),
            prepared.input(), prepared.contentJson(),
            null, session.getAgentVersionId(), now
        ) != 1 || mapper.touchSession(session.getId(), now) != 1
            || mapper.touchConversation(session.getConversationId(), now) != 1) {
            throw conflict("Embed消息持久化失败");
        }
        if (attachmentService != null) {
            attachmentService.attach(
                session, authenticated.principal().id(), turn.getId(), prepared.attachmentIds()
            );
        }
        return new TurnStart(session, turn, false, prepared.runtimeInput(), prepared.media());
    }

    /**
     * 处理{@code replayEvents}并返回对应结果。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> replayEvents(EmbedSession session, EmbedTurn turn) {
        return mapper.selectTurnEvents(session.getConversationId(), turn.getTraceId()).stream()
            .map(event -> ExecutionEventView.forExternal(event, jsonMapper)).toList();
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param turnId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long turnId) {
        mapper.finishTurn(turnId, "succeeded", null, utcNow());
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param turnId 资源标识
     * @param throwable {@code throwable}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(Long turnId, Throwable throwable) {
        mapper.finishTurn(turnId, "failed", safeError(throwable), utcNow());
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param turnId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long turnId) {
        mapper.finishTurn(turnId, "cancelled", "client_disconnected", utcNow());
    }

    /**
     * 处理{@code finish}相关逻辑。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param status 目标状态
     * @param response {@code response}参数
     * @param throwable {@code throwable}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(
        EmbedSession session,
        EmbedTurn turn,
        String status,
        String response,
        Throwable throwable
    ) {
        finish(session, turn, null, status, response, throwable);
    }

    /**
     * 处理{@code finishOwned}相关逻辑。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param executionOwner 执行Owner参数
     * @param status 目标状态
     * @param response {@code response}参数
     * @param throwable {@code throwable}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void finishOwned(
        EmbedSession session,
        EmbedTurn turn,
        String executionOwner,
        String status,
        String response,
        Throwable throwable
    ) {
        finish(session, turn, executionOwner, status, response, throwable);
    }

    /**
     * 处理{@code finish}相关逻辑。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param executionOwner 执行Owner参数
     * @param status 目标状态
     * @param response {@code response}参数
     * @param throwable {@code throwable}参数
     */
    private void finish(
        EmbedSession session,
        EmbedTurn turn,
        String executionOwner,
        String status,
        String response,
        Throwable throwable
    ) {
        LocalDateTime now = utcNow();
        String error = throwable == null ? null : safeError(throwable);
        int updated = executionOwner == null
            ? mapper.finishTurn(turn.getId(), status, error, now)
            : mapper.finishOwnedTurn(turn.getId(), executionOwner, status, error, now);
        if (updated != 1) {
            return;
        }
        String content = response == null ? "" : response.strip();
        if (!content.isEmpty()) {
            Map<String, Object> contentJson = new LinkedHashMap<>();
            contentJson.put("source", "embed");
            contentJson.put("turnId", turn.getId());
            int sequence = mapper.nextMessageSequence(session.getConversationId());
            if (mapper.insertMessage(
                idGenerator.nextId(), session.getConversationId(), sequence, turn.getTraceId(),
                "assistant", content, jsonMapper.writeValueAsString(contentJson), null,
                session.getAgentVersionId(), status.equals("succeeded") ? "completed" : status, now
            ) != 1) {
                throw conflict("Embed回复持久化失败");
            }
        }
        mapper.touchSession(session.getId(), now);
        mapper.touchConversation(session.getConversationId(), now);
    }

    /**
     * 处理claim执行并返回对应结果。
     *
     * @param turnId 资源标识
     * @param executionOwner 执行Owner参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimExecution(Long turnId, String executionOwner) {
        return mapper.claimTurn(turnId, executionOwner, utcNow()) == 1;
    }

    /**
     * 处理heartbeat执行并返回对应结果。
     *
     * @param turnId 资源标识
     * @param executionOwner 执行Owner参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean heartbeatExecution(Long turnId, String executionOwner) {
        return mapper.heartbeatTurn(turnId, executionOwner, utcNow()) == 1;
    }

    /**
     * 处理{@code finishStaleExecutions}并返回对应结果。
     *
     * @param leaseTimeout {@code leaseTimeout}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public int finishStaleExecutions(Duration leaseTimeout) {
        LocalDateTime now = utcNow();
        return mapper.finishStaleTurns(now.minus(leaseTimeout), now);
    }

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedTurn requestStop(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        Long turnId
    ) {
        requireOwnedActiveSession(authenticated, sessionId, true);
        EmbedTurn turn = mapper.selectTurn(sessionId, turnId);
        if (turn == null) {
            throw new ServiceException("Embed回合不存在", HttpStatus.NOT_FOUND);
        }
        mapper.requestStop(sessionId, turnId, utcNow());
        return mapper.selectTurn(sessionId, turnId);
    }

    /**
     * 处理{@code stopRequested}并返回对应结果。
     *
     * @param turnId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean stopRequested(Long turnId) {
        return Boolean.TRUE.equals(mapper.stopRequested(turnId));
    }

    /**
     * 处理ownedActive会话并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    public EmbedSession ownedActiveSession(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        return requireOwnedActiveSession(authenticated, sessionId, false);
    }

    /**
 * 处理owned会话并返回对应结果。
 * Allows a fresh launch capability to rotate an expired but still-owned session. */
    public EmbedSession ownedSession(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        EmbedSession session = mapper.selectSession(sessionId);
        if (session == null
            || !authenticated.applicationId().equals(session.getApplicationId())
            || !authenticated.principal().id().equals(session.getServiceAccountId())) {
            throw new ServiceException("Embed会话不存在", HttpStatus.NOT_FOUND);
        }
        return session;
    }

    /**
     * 处理request会话StopsForReset并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> requestSessionStopsForReset(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        ownedSession(authenticated, sessionId);
        List<Long> active = mapper.selectActiveTurnIds(sessionId);
        if (!active.isEmpty()) {
            mapper.requestSessionStops(sessionId, utcNow());
        }
        return List.copyOf(active);
    }

    /**
     * 处理close会话ForReset相关逻辑。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeSessionForReset(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        ownedSession(authenticated, sessionId);
        mapper.closeSession(sessionId, authenticated.applicationId(), authenticated.principal().id(), utcNow());
    }

    /**
     * 处理owned会话回合并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    public EmbedTurn ownedTurn(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        Long turnId
    ) {
        requireOwnedActiveSession(authenticated, sessionId, false);
        EmbedTurn turn = mapper.selectTurn(sessionId, turnId);
        if (turn == null) throw new ServiceException("Embed回合不存在", HttpStatus.NOT_FOUND);
        return turn;
    }

    /**
     * 处理当前会话回合并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    EmbedTurn currentTurn(Long sessionId, Long turnId) {
        return mapper.selectTurn(sessionId, turnId);
    }

    /**
     * 处理{@code turns}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<EmbedTurn> turns(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        int limit
    ) {
        requireOwnedActiveSession(authenticated, sessionId, false);
        return mapper.selectTurns(sessionId, Math.max(1, Math.min(limit, 50)));
    }

    /**
     * 处理{@code messages}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConversationMessageRow> messages(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        int limit
    ) {
        EmbedSession session = requireOwnedActiveSession(authenticated, sessionId, false);
        return mapper.selectMessages(session.getConversationId(), Math.max(1, Math.min(limit, 200)));
    }

    /**
     * 处理{@code eventsAfter}并返回对应结果。
     *
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> eventsAfter(
        EmbedSession session,
        EmbedTurn turn,
        long afterCursor,
        int limit
    ) {
        return mapper.selectTurnEventsAfter(
            session.getConversationId(), turn.getTraceId(), Math.max(0, afterCursor),
            Math.max(1, Math.min(limit, 200))
        ).stream().map(event -> ExecutionEventView.forExternal(event, jsonMapper)).toList();
    }

    /**
     * 处理close会话相关逻辑。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeSession(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        requireOwnedActiveSession(authenticated, sessionId, true);
        if (mapper.closeSession(
            sessionId, authenticated.applicationId(), authenticated.principal().id(), utcNow()
        ) != 1) {
            throw conflict("Embed会话关闭冲突");
        }
    }

    /**
     * 处理request会话Stops并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> requestSessionStops(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        requireOwnedActiveSession(authenticated, sessionId, true);
        List<Long> active = mapper.selectActiveTurnIds(sessionId);
        if (!active.isEmpty()) {
            mapper.requestSessionStops(sessionId, utcNow());
        }
        return List.copyOf(active);
    }

    /**
     * 校验OwnedActive会话，并在条件不满足时终止处理。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param lock {@code lock}参数
     * @return 处理结果
     */
    private EmbedSession requireOwnedActiveSession(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        boolean lock
    ) {
        EmbedSession session = lock ? mapper.lockSession(sessionId) : mapper.selectSession(sessionId);
        LocalDateTime now = utcNow();
        if (session == null
            || !authenticated.applicationId().equals(session.getApplicationId())
            || !authenticated.principal().id().equals(session.getServiceAccountId())) {
            throw new ServiceException("Embed会话不存在", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(session.getStatus()) || !session.getExpiresAt().isAfter(now)) {
            throw conflict("Embed会话已关闭或过期");
        }
        return session;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param session 会话参数
     * @return 处理结果
     */
    private EmbedSessionView view(EmbedSession session) {
        return new EmbedSessionView(
            session.getId(), session.getAgentVersionId(), session.getStatus(),
            session.getExpiresAt(), session.getCreatedAt()
        );
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String required(String value, String label, int maximum) {
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
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        String normalized = value.replaceAll("agk_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[redacted]")
            .replace('\0', ' ').strip();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
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
        EmbedSession session,
        EmbedTurn turn,
        boolean replayed,
        String input,
        List<EmbedAttachmentService.RuntimeMedia> media
    ) {
        /**
         * 创建 {@code TurnStart} 实例并初始化所需依赖。
         *
         * @param session 会话参数
         * @param turn 会话回合参数
         * @param replayed {@code replayed}参数
         * @param input {@code input}参数
         */
        public TurnStart(
            EmbedSession session,
            EmbedTurn turn,
            boolean replayed,
            String input
        ) {
            this(session, turn, replayed, input, List.of());
        }
    }
}
