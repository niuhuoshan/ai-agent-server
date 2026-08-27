package group.aitools.nhs.platform.embed.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示嵌入式会话智能体运行时相关的领域对象。
 */
@Data
public class EmbedAgentRuntimeRow {
    private Long agentVersionId;
    private Long agentId;
    private String agentKey;
    private String agentName;
    private String agentDescription;
    private String agentStatus;
    private Integer versionNo;
    private String versionStatus;
    private LocalDateTime publishedAt;
    private String systemPrompt;
    private Long modelId;
    private Long synthesisModelId;
    private String runtimeConfigJson;
    private String welcomeConfigJson;
    private String routingTagsJson;
    private String contentHash;
}
