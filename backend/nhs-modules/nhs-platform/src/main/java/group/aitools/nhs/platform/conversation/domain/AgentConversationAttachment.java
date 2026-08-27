package group.aitools.nhs.platform.conversation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体会话附件相关的领域对象。
 * Metadata for one owner-scoped conversation attachment. */
@Data
@TableName("agent_conversation_attachment")
public class AgentConversationAttachment {

    @TableId
    private Long id;
    private Long conversationId;
    private Long userId;
    private Long turnId;
    private String originalName;
    private String storageType;
    private String storageRef;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private LocalDateTime createdAt;
}
