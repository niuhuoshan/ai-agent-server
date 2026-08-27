package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentChatFeedback;
import group.aitools.nhs.platform.conversation.domain.AgentChatResourceScope;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.mapper.ConversationGovernanceMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackRequest;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackView;
import group.aitools.nhs.platform.conversation.web.ConversationResourceScopeRequest;
import group.aitools.nhs.platform.conversation.web.ConversationResourceScopeView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责会话Governance相关的业务编排与领域规则处理。
 * Conversation-level governance that keeps feedback and resource scopes owner-bound. */
@Service
public class ConversationGovernanceService {

    private static final TypeReference<Map<String, List<Long>>> SCOPE_TYPE = new TypeReference<>() {
    };
    private static final Map<String, String> RESOURCE_TYPES = Map.of(
        "agent_ids", "agent_version",
        "agent_version_ids", "agent_version",
        "dataset_ids", "dataset",
        "knowledge_base_ids", "knowledge_base",
        "tool_ids", "tool",
        "skill_ids", "skill"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentConversationMapper conversationMapper;
    private final ConversationGovernanceMapper mapper;
    private final JsonMapper jsonMapper;
    private final ConversationFeedbackCandidateRecorder feedbackCandidateRecorder;

    /**
     * 创建 {@code ConversationGovernanceService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param conversationMapper 会话Mapper参数
     * @param mapper {@code mapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param feedbackCandidateRecorder 反馈CandidateRecorder参数
     */
    public ConversationGovernanceService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentConversationMapper conversationMapper,
        ConversationGovernanceMapper mapper,
        JsonMapper jsonMapper,
        ConversationFeedbackCandidateRecorder feedbackCandidateRecorder
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.conversationMapper = conversationMapper;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
        this.feedbackCandidateRecorder = feedbackCandidateRecorder;
    }

    /**
     * 保存反馈。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationFeedbackView saveFeedback(Long conversationId, ConversationFeedbackRequest request) {
        return saveFeedback(principalProvider.currentPrincipal(), conversationId, request);
    }

    /**
 * 保存反馈。
 *
     * Saves feedback for an already authenticated machine principal, such as an Embed
     * browser credential. Keeping the same authorization and audit path prevents the
     * public widget from becoming a second, weaker feedback implementation.
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationFeedbackView saveFeedback(
        CurrentPrincipal principal,
        Long conversationId,
        ConversationFeedbackRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireConversation(principal, conversationId, "feedback");
        if (request.messageId() == null) {
            throw badRequest("反馈必须关联消息");
        }
        ConversationMessageRow message = mapper.selectMessage(conversationId, request.messageId());
        if (message == null) {
            throw new ServiceException("反馈消息不存在", HttpStatus.NOT_FOUND);
        }
        if (!"assistant".equals(message.getRole())) {
            throw badRequest("只能评价助手消息");
        }
        String requestedTraceId = normalize(request.traceId(), 64);
        String messageTraceId = normalize(message.getTraceId(), 64);
        if (requestedTraceId != null && messageTraceId != null && !requestedTraceId.equals(messageTraceId)) {
            throw badRequest("反馈 Trace 与消息不一致");
        }
        String traceId = messageTraceId == null
            ? "conversation-" + conversationId + "-message-" + request.messageId()
            : messageTraceId;
        message.setTraceId(traceId);
        if (!Set.of("up", "down", "like", "dislike").contains(request.rating().strip().toLowerCase(Locale.ROOT))) {
            throw badRequest("反馈类型必须为 up 或 down");
        }
        String rating = switch (request.rating().strip().toLowerCase(Locale.ROOT)) {
            case "like" -> "up";
            case "dislike" -> "down";
            default -> request.rating().strip().toLowerCase(Locale.ROOT);
        };
        LocalDateTime now = LocalDateTime.now();
        AgentChatFeedback value = mapper.selectFeedback(conversationId, request.messageId(), principal.id());
        if (value == null) {
            value = new AgentChatFeedback();
            value.setId(idGenerator.nextId());
            value.setConversationId(conversationId);
            value.setMessageId(request.messageId());
            value.setUserId(principal.id());
            value.setCreatedAt(now);
            value.setTurnId(request.turnId());
            value.setRating(rating);
            value.setReason(normalize(request.reason(), 64));
            value.setComment(normalize(request.comment(), 2000));
            value.setTraceId(traceId);
            value.setUpdatedAt(now);
            if (mapper.insertFeedback(value) != 1) {
                value = mapper.selectFeedback(conversationId, request.messageId(), principal.id());
                if (value == null) {
                    throw new ServiceException("反馈保存失败", HttpStatus.CONFLICT);
                }
            }
        } else {
            value.setTurnId(request.turnId());
            value.setRating(rating);
            value.setReason(normalize(request.reason(), 64));
            value.setComment(normalize(request.comment(), 2000));
            value.setTraceId(traceId);
            value.setUpdatedAt(now);
            if (mapper.updateFeedback(value) != 1) {
                throw new ServiceException("反馈已被其他请求更新", HttpStatus.CONFLICT);
            }
        }
        ConversationMessageRow previousUserMessage = message.getSequenceNo() == null
            ? null
            : mapper.selectPreviousUserMessage(conversationId, message.getSequenceNo());
        feedbackCandidateRecorder.record(principal, message, previousUserMessage, rating);
        return ConversationFeedbackView.from(value);
    }

    /**
     * 处理资源范围并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    public ConversationResourceScopeView resourceScope(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        requireConversation(principal, conversationId, "read_resource_scope");
        AgentChatResourceScope stored = mapper.selectResourceScope(conversationId, principal.id());
        if (stored == null) {
            return new ConversationResourceScopeView(conversationId, 0, Map.of(), null);
        }
        return view(stored);
    }

    /**
 * 执行time范围相关的处理流程。
 * Reads the already validated owner scope while a durable turn snapshot is being built. */
    public Map<String, List<Long>> runtimeScope(CurrentPrincipal principal, Long conversationId) {
        if (conversationMapper.selectOwnedConversation(conversationId, principal.id()) == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        AgentChatResourceScope stored = mapper.selectResourceScope(conversationId, principal.id());
        return stored == null ? Map.of() : view(stored).resources();
    }

    /**
     * 处理active会话并返回对应结果。
     *
     * @return 处理结果
     */
    public Long activeConversation() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        requireConversationCapability(principal, null, "view_active");
        Long conversationId = mapper.selectActiveConversationId(principal.id());
        if (conversationId != null) {
            requireConversation(principal, conversationId, "view_active");
        }
        return conversationId;
    }

    /**
     * 设置Active会话。
     *
     * @param conversationId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void setActiveConversation(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        requireConversationCapability(principal, null, "update_active");
        requireConversation(principal, conversationId, "update_active");
        if (mapper.upsertActiveConversation(principal.id(), conversationId, LocalDateTime.now()) != 1) {
            throw new ServiceException("活动会话保存失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 更新资源范围。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationResourceScopeView updateResourceScope(
        Long conversationId,
        ConversationResourceScopeRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        requireConversation(principal, conversationId, "update_resource_scope");
        Map<String, List<Long>> normalized = normalizeScope(principal, request.resources());
        String json = writeScope(normalized);
        LocalDateTime now = LocalDateTime.now();
        AgentChatResourceScope stored = mapper.selectResourceScope(conversationId, principal.id());
        if (stored == null) {
            if (request.expectedRevision() != null && request.expectedRevision() != 0) {
                throw new ServiceException("资源范围版本已变化", HttpStatus.CONFLICT);
            }
            if (mapper.insertResourceScope(conversationId, principal.id(), json, now) != 1) {
                stored = mapper.selectResourceScope(conversationId, principal.id());
                if (stored == null) {
                    throw new ServiceException("资源范围保存失败", HttpStatus.CONFLICT);
                }
            } else {
                stored = mapper.selectResourceScope(conversationId, principal.id());
            }
        } else {
            int expected = request.expectedRevision() == null ? stored.getRevision() : request.expectedRevision();
            if (expected != stored.getRevision()) {
                throw new ServiceException("资源范围版本已变化", HttpStatus.CONFLICT);
            }
            if (mapper.updateResourceScope(conversationId, principal.id(), json, expected, now) != 1) {
                throw new ServiceException("资源范围已被其他请求更新", HttpStatus.CONFLICT);
            }
            stored = mapper.selectResourceScope(conversationId, principal.id());
        }
        return view(stored);
    }

    /**
     * 删除会话。
     *
     * @param conversationId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        requireConversation(principal, conversationId, "delete");
        if (mapper.deleteConversation(conversationId, principal.id()) != 1) {
            throw new ServiceException("会话不存在或已删除", HttpStatus.NOT_FOUND);
        }
        mapper.clearActiveConversation(principal.id(), conversationId);
    }

    /**
     * 校验会话，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param action {@code action}参数
     */
    private void requireConversation(CurrentPrincipal principal, Long conversationId, String action) {
        if (conversationMapper.selectOwnedConversation(conversationId, principal.id()) == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        requireConversationCapability(principal, conversationId, action);
    }

    /**
     * 校验会话Capability，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param action {@code action}参数
     */
    private void requireConversationCapability(CurrentPrincipal principal, Long conversationId, String action) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, action, ResourceState.ACTIVE, true, Set.of(), null
        ));
    }

    /**
     * 处理normalize范围并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Map<String, List<Long>> normalizeScope(CurrentPrincipal principal, Map<String, List<Long>> raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null) {
            return Map.of();
        }
        Map<String, List<Long>> normalized = new TreeMap<>();
        for (Map.Entry<String, List<Long>> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            String resourceType = RESOURCE_TYPES.get(key);
            if (resourceType == null) {
                throw badRequest("不支持的资源范围字段: " + entry.getKey());
            }
            List<Long> ids = entry.getValue() == null ? List.of() : entry.getValue();
            if (ids.size() > 200) {
                throw badRequest("单类资源最多选择200项");
            }
            List<Long> values = ids.stream().distinct().sorted().toList();
            for (Long id : values) {
                if (id == null || id <= 0) {
                    throw badRequest("资源ID必须为正数");
                }
                String action = switch (resourceType) {
                    case "dataset" -> "query";
                    case "knowledge_base" -> "read";
                    case "tool", "skill" -> "use";
                    default -> "use";
                };
                authorizationEnforcer.requireAllowed(principal, new PermissionContext(
                    resourceType, id, null, action, ResourceState.ACTIVE, true, Set.of(), null
                ));
            }
            normalized.put(key, new ArrayList<>(values));
        }
        return normalized;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param stored {@code stored}参数
     * @return 处理结果
     */
    private ConversationResourceScopeView view(AgentChatResourceScope stored) {
        Map<String, List<Long>> resources;
        try {
            resources = jsonMapper.readValue(stored.getScopeJson(), SCOPE_TYPE);
        } catch (RuntimeException exception) {
            throw new ServiceException("资源范围数据损坏", HttpStatus.ERROR);
        }
        return new ConversationResourceScopeView(
            stored.getConversationId(), stored.getRevision(), resources == null ? Map.of() : resources,
            stored.getUpdatedAt()
        );
    }

    /**
     * 处理write范围并返回对应结果。
     *
     * @param scope 范围参数
     * @return 处理结果
     */
    private String writeScope(Map<String, List<Long>> scope) {
        try {
            return jsonMapper.writeValueAsString(scope);
        } catch (RuntimeException exception) {
            throw new ServiceException("资源范围序列化失败", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code normalizeKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeKey(String value) {
        if (value == null) {
            throw badRequest("资源范围字段不能为空");
        }
        String normalized = value.strip().replace('-', '_').replace(' ', '_')
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toLowerCase(Locale.ROOT);
        return normalized;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String normalize(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().replace('\0', ' ');
        if (normalized.length() > max) {
            throw badRequest("反馈字段过长");
        }
        return normalized;
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
