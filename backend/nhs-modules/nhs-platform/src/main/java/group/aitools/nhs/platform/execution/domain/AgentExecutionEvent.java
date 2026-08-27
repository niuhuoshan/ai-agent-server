package group.aitools.nhs.platform.execution.domain;

import java.time.LocalDateTime;

/**
 * 表示智能体执行相关的领域对象。
 */
public class AgentExecutionEvent {

    private Long id;
    private String eventId;
    private String traceId;
    private Long conversationId;
    private Long runId;
    private Long stepId;
    private Long cursor;
    private String eventType;
    private String eventStatus;
    private String summary;
    private String payloadJson;
    private String queryProjectionJson;
    private String sensitiveLevel;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

    /**
     * 获取{@code Id}。
     *
     * @return 处理结果
     */
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
     * 获取事件Id。
     *
     * @return 处理结果
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 设置事件Id。
     *
     * @param eventId 资源标识
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
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
     * 获取{@code StepId}。
     *
     * @return 处理结果
     */
    public Long getStepId() {
        return stepId;
    }

    /**
     * 设置{@code StepId}。
     *
     * @param stepId 资源标识
     */
    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    /**
     * 获取{@code Cursor}。
     *
     * @return 处理结果
     */
    public Long getCursor() {
        return cursor;
    }

    /**
     * 设置{@code Cursor}。
     *
     * @param cursor {@code cursor}参数
     */
    public void setCursor(Long cursor) {
        this.cursor = cursor;
    }

    /**
     * 获取事件Type。
     *
     * @return 处理结果
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * 设置事件Type。
     *
     * @param eventType 业务类型
     */
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /**
     * 获取事件Status。
     *
     * @return 处理结果
     */
    public String getEventStatus() {
        return eventStatus;
    }

    /**
     * 设置事件Status。
     *
     * @param eventStatus 目标状态
     */
    public void setEventStatus(String eventStatus) {
        this.eventStatus = eventStatus;
    }

    /**
     * 获取{@code Summary}。
     *
     * @return 处理结果
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 设置{@code Summary}。
     *
     * @param summary {@code summary}参数
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * 获取{@code PayloadJson}。
     *
     * @return 处理结果
     */
    public String getPayloadJson() {
        return payloadJson;
    }

    /**
     * 设置{@code PayloadJson}。
     *
     * @param payloadJson {@code payloadJson}参数
     */
    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    /**
     * 获取查询ProjectionJson。
     *
     * @return 处理结果
     */
    public String getQueryProjectionJson() {
        return queryProjectionJson;
    }

    /**
     * 设置查询ProjectionJson。
     *
     * @param queryProjectionJson 查询ProjectionJson参数
     */
    public void setQueryProjectionJson(String queryProjectionJson) {
        this.queryProjectionJson = queryProjectionJson;
    }

    /**
     * 获取{@code SensitiveLevel}。
     *
     * @return 处理结果
     */
    public String getSensitiveLevel() {
        return sensitiveLevel;
    }

    /**
     * 设置{@code SensitiveLevel}。
     *
     * @param sensitiveLevel {@code sensitiveLevel}参数
     */
    public void setSensitiveLevel(String sensitiveLevel) {
        this.sensitiveLevel = sensitiveLevel;
    }

    /**
     * 获取{@code OccurredAt}。
     *
     * @return 处理结果
     */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    /**
     * 设置{@code OccurredAt}。
     *
     * @param occurredAt {@code occurredAt}参数
     */
    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
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
}
