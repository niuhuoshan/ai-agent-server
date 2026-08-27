package group.aitools.nhs.platform.conversation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话反馈相关的领域对象。
 * Auditable owner feedback for one conversation message. */
@Data
@TableName("agent_chat_feedback")
public class AgentChatFeedback {

    @TableId
    private Long id;
    private Long conversationId;
    private Long messageId;
    private Long turnId;
    private Long userId;
    private String rating;
    private String reason;
    private String comment;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
