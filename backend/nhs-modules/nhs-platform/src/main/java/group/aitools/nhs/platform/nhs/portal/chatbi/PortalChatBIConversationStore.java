package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示门户对话BI会话Store相关的领域对象。
 * Atomically appends one ChatBI question/answer pair to a private conversation. */
@Service
public class PortalChatBIConversationStore {

    private final PlatformIdGenerator idGenerator;
    private final ConversationTurnMapper mapper;
    private final JsonMapper jsonMapper;

    public PortalChatBIConversationStore(
        PlatformIdGenerator idGenerator,
        ConversationTurnMapper mapper,
        JsonMapper jsonMapper
    ) {
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code append}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param modelId 资源标识
     * @param datasetId 资源标识
     * @param queryId 资源标识
     * @param sql {@code sql}参数
     * @param question 追问参数
     * @param answer {@code answer}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(
        CurrentPrincipal principal,
        Long conversationId,
        String traceId,
        Long modelId,
        Long datasetId,
        Long queryId,
        String sql,
        String question,
        String answer
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        AgentConversation conversation = mapper.lockOwnedActiveConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("ChatBI 私有会话不存在", HttpStatus.NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        int sequence = mapper.nextMessageSequence(conversationId);
        Map<String, Object> userMetadata = new LinkedHashMap<>();
        userMetadata.put("source", "chatbi");
        userMetadata.put("datasetId", datasetId);
        if (queryId != null) {
            userMetadata.put("queryId", queryId);
        }
        Map<String, Object> assistantMetadata = new LinkedHashMap<>(userMetadata);
        assistantMetadata.put("modelId", modelId);
        if (sql != null && !sql.isBlank()) {
            assistantMetadata.put("sql", sql);
        }
        if (mapper.insertMessage(
            idGenerator.nextId(), conversationId, sequence, traceId, "user", question,
            jsonMapper.writeValueAsString(userMetadata), conversation.getAgentId(),
            conversation.getAgentVersionId(), "completed", now
        ) != 1 || mapper.insertMessage(
            idGenerator.nextId(), conversationId, sequence + 1, traceId, "assistant", answer,
            jsonMapper.writeValueAsString(assistantMetadata), conversation.getAgentId(),
            conversation.getAgentVersionId(), "completed", now
        ) != 1 || mapper.touchConversation(
            conversationId, principal.id(), conversation.getAgentId(),
            conversation.getAgentVersionId(), now
        ) != 1) {
            throw new ServiceException("ChatBI 会话消息持久化失败", HttpStatus.CONFLICT);
        }
        mapper.updateConversationSummary(
            conversationId, principal.id(), summary(answer), now
        );
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param answer {@code answer}参数
     * @return 处理结果
     */
    private String summary(String answer) {
        String normalized = answer == null ? "" : answer.strip();
        return normalized.length() <= 4000 ? normalized : normalized.substring(0, 4000);
    }
}
