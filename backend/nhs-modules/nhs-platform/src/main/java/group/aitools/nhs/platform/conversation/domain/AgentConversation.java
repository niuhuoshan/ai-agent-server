package group.aitools.nhs.platform.conversation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体会话相关的领域对象。
 * Private conversation owned by one human user. */
@Data
@TableName("agent_conversation")
public class AgentConversation {

    @TableId
    private Long id;
    private Long userId;
    private Long projectId;
    private Long taskId;
    private Long agentId;
    private Long agentVersionId;
    private String branchId;
    private Long parentConversationId;
    private Long forkMessageId;
    private Integer contextCutoffSequence;
    private String principalType;
    private String title;
    private String visibility;
    private String status;
    private String sessionKey;
    private LocalDateTime lastMessageAt;
    private String summary;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
