package group.aitools.nhs.platform.execution.persistence.row;

/**
 * 表示智能体Run运行时相关的领域对象。
 */
public class AgentRunRuntimeRow {

    private Long id;
    private Long taskId;
    private String traceId;
    private String status;
    private String runtimeSnapshotJson;
    private Long workflowVersionId;
    private Long stepId;
    private String stepStatus;

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
     * 获取{@code Status}。
     *
     * @return 处理结果
     */
    public String getStatus() {
        return status;
    }

    /**
     * 设置{@code Status}。
     *
     * @param status 目标状态
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 获取运行时快照Json。
     *
     * @return 处理结果
     */
    public String getRuntimeSnapshotJson() {
        return runtimeSnapshotJson;
    }

    /**
     * 设置运行时快照Json。
     *
     * @param runtimeSnapshotJson 运行时快照Json参数
     */
    public void setRuntimeSnapshotJson(String runtimeSnapshotJson) {
        this.runtimeSnapshotJson = runtimeSnapshotJson;
    }

    /**
     * 获取工作流版本Id。
     *
     * @return 处理结果
     */
    public Long getWorkflowVersionId() {
        return workflowVersionId;
    }

    /**
     * 设置工作流版本Id。
     *
     * @param workflowVersionId 资源标识
     */
    public void setWorkflowVersionId(Long workflowVersionId) {
        this.workflowVersionId = workflowVersionId;
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
     * 获取{@code StepStatus}。
     *
     * @return 处理结果
     */
    public String getStepStatus() {
        return stepStatus;
    }

    /**
     * 设置{@code StepStatus}。
     *
     * @param stepStatus 目标状态
     */
    public void setStepStatus(String stepStatus) {
        this.stepStatus = stepStatus;
    }
}
