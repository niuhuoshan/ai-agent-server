package group.aitools.nhs.platform.agent.persistence.row;

import java.time.LocalDateTime;

/**
 * 设置{@code TotalTokens}。
 *
 * 获取{@code TotalTokens}。
 *
 * 设置{@code CompletionTokens}。
 *
 * 获取{@code CompletionTokens}。
 *
 * 设置提示词Tokens。
 *
 * 获取提示词Tokens。
 *
 * 设置智能体Name。
 *
 * 获取智能体Name。
 *
 * 设置智能体DisplayName。
 *
 * 获取智能体DisplayName。
 *
 * 设置会话回合Count。
 *
 * 获取会话回合Count。
 *
 * 设置{@code CreatedAt}。
 *
 * 获取{@code CreatedAt}。
 *
 * 设置执行TimeMs。
 *
 * 获取执行TimeMs。
 *
 * 设置模型Id。
 *
 * 获取模型Id。
 *
 * 设置智能体版本。
 *
 * 获取智能体版本。
 *
 * 设置{@code Status}。
 *
 * 获取{@code Status}。
 *
 * 设置{@code Summary}。
 *
 * 获取{@code Summary}。
 *
 * 设置查询。
 *
 * 获取查询。
 *
 * 设置{@code Username}。
 *
 * 获取{@code Username}。
 *
 * 设置会话Id。
 *
 * 获取会话Id。
 *
 * 设置智能体Id。
 *
 * 获取智能体Id。
 *
 * 设置链路追踪Id。
 *
 * 获取链路追踪Id。
 *
 * 设置{@code Id}。
 *
 * 获取{@code Id}。
 *
 * 表示智能体执行历史记录相关的领域对象。
 * Durable conversation-turn projection used by the Agent execution history API. */
public class AgentExecutionHistoryRow {

    private Long id;
    private String traceId;
    private Long agentId;
    private Long conversationId;
    private String username;
    private String query;
    private String summary;
    private String status;
    private String agentVersion;
    private String modelId;
    private Long executionTimeMs;
    private LocalDateTime createdAt;
    private Long turnCount;
    private String agentDisplayName;
    private String agentName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }

    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Long getAgentId() { return agentId; }

    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public Long getConversationId() { return conversationId; }

    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public String getUsername() { return username; }

    public void setUsername(String username) { this.username = username; }

    public String getQuery() { return query; }

    public void setQuery(String query) { this.query = query; }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public String getAgentVersion() { return agentVersion; }

    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getModelId() { return modelId; }

    public void setModelId(String modelId) { this.modelId = modelId; }

    public Long getExecutionTimeMs() { return executionTimeMs; }

    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getTurnCount() { return turnCount; }

    public void setTurnCount(Long turnCount) { this.turnCount = turnCount; }

    public String getAgentDisplayName() { return agentDisplayName; }

    public void setAgentDisplayName(String agentDisplayName) { this.agentDisplayName = agentDisplayName; }

    public String getAgentName() { return agentName; }

    public void setAgentName(String agentName) { this.agentName = agentName; }

    public Integer getPromptTokens() { return promptTokens; }

    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }

    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }

    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
}
