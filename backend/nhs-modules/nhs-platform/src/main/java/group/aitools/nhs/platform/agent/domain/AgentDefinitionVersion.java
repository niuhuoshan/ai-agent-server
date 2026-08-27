package group.aitools.nhs.platform.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体定义版本相关的领域对象。
 * Draft or immutable published/archived Agent configuration. */
@Data
@TableName("agent_definition_version")
public class AgentDefinitionVersion {

    @TableId
    private Long id;
    private Long agentId;
    private Integer versionNo;
    private String systemPrompt;
    private Long modelId;
    private Long synthesisModelId;
    private String runtimeConfigJson;
    private String welcomeConfigJson;
    private String routingTagsJson;
    private String status;
    private String contentHash;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
