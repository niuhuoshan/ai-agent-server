package group.aitools.nhs.platform.execution.domain;

import java.time.LocalDateTime;

/**
 * 获取{@code Id}。
 *
 * 表示智能体执行时间线快照相关的领域对象。
 * Persisted, redacted semantic execution timeline for one trace. */
public class AgentExecutionTimelineSnapshot {

    private Long id;
    private String traceId;
    private Long conversationId;
    private Long taskId;
    private Long runId;
    private String timelineJson;
    private String contentHash;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    /**
     * 设置{@code Id}。
     *
     * @param id 资源标识
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取链路追踪Id。
     *
     * @return 处理结果
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 设置链路追踪Id。
     *
     * @param traceId 资源标识
     */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * 获取会话Id。
     *
     * @return 处理结果
     */
    public Long getConversationId() {
        return conversationId;
    }

    /**
     * 设置会话Id。
     *
     * @param conversationId 资源标识
     */
    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * 获取任务Id。
     *
     * @return 处理结果
     */
    public Long getTaskId() {
        return taskId;
    }

    /**
     * 设置任务Id。
     *
     * @param taskId 资源标识
     */
    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    /**
     * 获取{@code RunId}。
     *
     * @return 处理结果
     */
    public Long getRunId() {
        return runId;
    }

    /**
     * 设置{@code RunId}。
     *
     * @param runId 资源标识
     */
    public void setRunId(Long runId) {
        this.runId = runId;
    }

    /**
     * 获取时间线Json。
     *
     * @return 处理结果
     */
    public String getTimelineJson() {
        return timelineJson;
    }

    /**
     * 设置时间线Json。
     *
     * @param timelineJson 时间线Json参数
     */
    public void setTimelineJson(String timelineJson) {
        this.timelineJson = timelineJson;
    }

    /**
     * 获取{@code ContentHash}。
     *
     * @return 处理结果
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * 设置{@code ContentHash}。
     *
     * @param contentHash 待处理内容
     */
    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    /**
     * 获取{@code GeneratedAt}。
     *
     * @return 处理结果
     */
    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    /**
     * 设置{@code GeneratedAt}。
     *
     * @param generatedAt {@code generatedAt}参数
     */
    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    /**
     * 获取{@code CreatedAt}。
     *
     * @return 处理结果
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置{@code CreatedAt}。
     *
     * @param createdAt {@code createdAt}参数
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 获取{@code UpdatedAt}。
     *
     * @return 处理结果
     */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置{@code UpdatedAt}。
     *
     * @param updatedAt {@code updatedAt}参数
     */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
