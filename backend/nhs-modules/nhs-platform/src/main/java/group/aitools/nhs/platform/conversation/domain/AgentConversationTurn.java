package group.aitools.nhs.platform.conversation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体会话会话回合相关的领域对象。
 * Durable idempotency and lifecycle fact for one human conversation turn. */
@Data
@TableName("agent_conversation_turn")
public class AgentConversationTurn {

    @TableId
    private Long id;
    private Long conversationId;
    private Long userId;
    private String idempotencyHash;
    private String requestHash;
    private String traceId;
    private Long agentId;
    private Long agentVersionId;
    private String status;
    private String runtimeSnapshotJson;
    private String errorSummary;
    private String responseDraft;
    private LocalDateTime stopRequestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
