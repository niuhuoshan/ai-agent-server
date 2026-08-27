package group.aitools.nhs.platform.task.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 处理{@code title}并返回对应结果。
 *
 * 定义任务定义Input相关能力的服务契约。
 * Common normalized input used by task creation and versioned updates. */
public interface TaskDefinitionInput {

    String title();

    /**
     * 处理{@code objective}并返回对应结果。
     *
     * @return 处理结果
     */
    String objective();

    /**
     * 处理{@code background}并返回对应结果。
     *
     * @return 处理结果
     */
    String background();

    /**
     * 处理项目Id并返回对应结果。
     *
     * @return 处理结果
     */
    Long projectId();

    /**
     * 处理智能体版本Id并返回对应结果。
     *
     * @return 处理结果
     */
    Long agentVersionId();

    /**
     * 处理工作流版本Id并返回对应结果。
     *
     * @return 处理结果
     */
    Long workflowVersionId();

    /**
     * 处理工作流智能体Versions并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Long> workflowAgentVersions();

    /**
     * 处理{@code visibility}并返回对应结果。
     *
     * @return 处理结果
     */
    String visibility();

    /**
     * 处理{@code category}并返回对应结果。
     *
     * @return 处理结果
     */
    String category();

    /**
     * 处理{@code orchestrationMode}并返回对应结果。
     *
     * @return 处理结果
     */
    String orchestrationMode();

    /**
     * 处理{@code lifecycleLevel}并返回对应结果。
     *
     * @return 处理结果
     */
    String lifecycleLevel();

    /**
     * 处理风险Level并返回对应结果。
     *
     * @return 处理结果
     */
    String riskLevel();

    /**
     * 处理验收Mode并返回对应结果。
     *
     * @return 处理结果
     */
    String acceptanceMode();

    /**
     * 处理{@code importance}并返回对应结果。
     *
     * @return 处理结果
     */
    Integer importance();

    /**
     * 处理{@code urgency}并返回对应结果。
     *
     * @return 处理结果
     */
    Integer urgency();

    /**
     * 处理{@code startAt}并返回对应结果。
     *
     * @return 处理结果
     */
    LocalDateTime startAt();

    /**
     * 处理上下文快照并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> contextSnapshot();

    /**
     * 处理{@code resources}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    List<TaskResourceRequest> resources();

    /**
     * 处理验收快照并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> acceptanceSnapshot();

    /**
     * 处理input快照并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> inputSnapshot();

    /**
     * 处理{@code budget}并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> budget();

    /**
     * 处理{@code externalRefs}并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> externalRefs();

    /**
     * 处理{@code tags}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    List<String> tags();
}
