package group.aitools.nhs.platform.conversation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话资源范围相关的领域对象。
 * JSON resource scope snapshot for one private conversation. */
@Data
@TableName("agent_chat_resource_scope")
public class AgentChatResourceScope {

    @TableId
    private Long conversationId;
    private Long userId;
    private String scopeJson;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
