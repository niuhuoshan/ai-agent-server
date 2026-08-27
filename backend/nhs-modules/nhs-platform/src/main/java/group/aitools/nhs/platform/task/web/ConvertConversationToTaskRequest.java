package group.aitools.nhs.platform.task.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装Convert会话To任务相关的不可变数据。
 * Explicit confirmation payload for converting selected private context into a formal task. */
public record ConvertConversationToTaskRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Size(max = 255) String title,
    @NotBlank @Size(max = 12000) String objective,
    @Size(max = 12000) String background,
    @Positive Long projectId,
    @NotNull @Positive Long agentVersionId,
    @Positive Long workflowVersionId,
    @Size(max = 8) Map<@Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String, @Positive Long> workflowAgentVersions,
    @Pattern(regexp = "enterprise_shared|restricted") String visibility,
    @Pattern(regexp = "development|data|knowledge|operations|document|general") String category,
    @Pattern(regexp = "single_agent|multi_agent_template|human_in_loop|hybrid") String orchestrationMode,
    @Pattern(regexp = "L0_chat|L1_short_task|L2_workflow_task|L3_recurring_task") String lifecycleLevel,
    @Pattern(regexp = "R0|R1|R2|R3") String riskLevel,
    @Pattern(regexp = "rule|human|combined") String acceptanceMode,
    @Min(0) @Max(1) Integer importance,
    @Min(0) @Max(1) Integer urgency,
    LocalDateTime startAt,
    Map<String, Object> contextSnapshot,
    @Valid @Size(max = 128) List<TaskResourceRequest> resources,
    Map<String, Object> acceptanceSnapshot,
    Map<String, Object> inputSnapshot,
    Map<String, Object> budget,
    Map<String, Object> externalRefs,
    @Size(max = 32) List<@NotBlank @Size(max = 64) String> tags,
    @Pattern(regexp = "[a-f0-9]{64}") String draftHash
) implements TaskDefinitionInput {

    /**
     * 创建 {@code ConvertConversationToTaskRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param title {@code title}参数
     * @param objective {@code objective}参数
     * @param background {@code background}参数
     * @param projectId 资源标识
     * @param agentVersionId 资源标识
     * @param workflowVersionId 资源标识
     * @param visibility {@code visibility}参数
     * @param category {@code category}参数
     * @param orchestrationMode {@code orchestrationMode}参数
     * @param lifecycleLevel {@code lifecycleLevel}参数
     * @param riskLevel 风险Level参数
     * @param acceptanceMode 验收Mode参数
     * @param importance {@code importance}参数
     * @param urgency {@code urgency}参数
     * @param startAt {@code startAt}参数
     * @param contextSnapshot 待处理内容
     * @param resources {@code resources}参数
     * @param acceptanceSnapshot 验收快照参数
     * @param inputSnapshot input快照参数
     * @param budget {@code budget}参数
     * @param externalRefs {@code externalRefs}参数
     * @param tags {@code tags}参数
     * @param draftHash {@code draftHash}参数
     */
    public ConvertConversationToTaskRequest(
        String idempotencyKey,
        String title,
        String objective,
        String background,
        Long projectId,
        Long agentVersionId,
        Long workflowVersionId,
        String visibility,
        String category,
        String orchestrationMode,
        String lifecycleLevel,
        String riskLevel,
        String acceptanceMode,
        Integer importance,
        Integer urgency,
        LocalDateTime startAt,
        Map<String, Object> contextSnapshot,
        List<TaskResourceRequest> resources,
        Map<String, Object> acceptanceSnapshot,
        Map<String, Object> inputSnapshot,
        Map<String, Object> budget,
        Map<String, Object> externalRefs,
        List<String> tags,
        String draftHash
    ) {
        this(
            idempotencyKey, title, objective, background, projectId, agentVersionId,
            workflowVersionId, Map.of(), visibility, category, orchestrationMode,
            lifecycleLevel, riskLevel, acceptanceMode, importance, urgency, startAt,
            contextSnapshot, resources, acceptanceSnapshot, inputSnapshot, budget,
            externalRefs, tags, draftHash
        );
    }

    /**
     * 创建 {@code ConvertConversationToTaskRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param title {@code title}参数
     * @param objective {@code objective}参数
     * @param background {@code background}参数
     * @param projectId 资源标识
     * @param agentVersionId 资源标识
     * @param workflowVersionId 资源标识
     * @param workflowAgentVersions 工作流智能体Versions参数
     * @param visibility {@code visibility}参数
     * @param category {@code category}参数
     * @param orchestrationMode {@code orchestrationMode}参数
     * @param lifecycleLevel {@code lifecycleLevel}参数
     * @param riskLevel 风险Level参数
     * @param acceptanceMode 验收Mode参数
     * @param importance {@code importance}参数
     * @param urgency {@code urgency}参数
     * @param startAt {@code startAt}参数
     * @param contextSnapshot 待处理内容
     * @param resources {@code resources}参数
     * @param acceptanceSnapshot 验收快照参数
     * @param inputSnapshot input快照参数
     * @param budget {@code budget}参数
     * @param externalRefs {@code externalRefs}参数
     * @param tags {@code tags}参数
     * @param draftHash {@code draftHash}参数
     */
    public ConvertConversationToTaskRequest {
        visibility = visibility == null ? "enterprise_shared" : visibility;
        category = category == null ? "general" : category;
        orchestrationMode = orchestrationMode == null ? "single_agent" : orchestrationMode;
        workflowAgentVersions = immutableCopy(workflowAgentVersions);
        lifecycleLevel = TaskInputDefaults.lifecycle(lifecycleLevel, orchestrationMode);
        riskLevel = riskLevel == null ? "R1" : riskLevel;
        acceptanceMode = acceptanceMode == null ? "human" : acceptanceMode;
        importance = importance == null ? 0 : importance;
        urgency = urgency == null ? 0 : urgency;
        contextSnapshot = immutableCopy(contextSnapshot);
        resources = TaskInputDefaults.list(resources);
        acceptanceSnapshot = immutableCopy(acceptanceSnapshot);
        inputSnapshot = immutableCopy(inputSnapshot);
        budget = immutableCopy(budget);
        externalRefs = immutableCopy(externalRefs);
        tags = TaskInputDefaults.list(tags);
    }

    /**
     * 处理{@code withDraftHash}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public ConvertConversationToTaskRequest withDraftHash(String value) {
        return new ConvertConversationToTaskRequest(
            idempotencyKey, title, objective, background, projectId, agentVersionId,
            workflowVersionId, workflowAgentVersions, visibility, category, orchestrationMode, lifecycleLevel,
            riskLevel, acceptanceMode, importance, urgency, startAt, contextSnapshot,
            resources, acceptanceSnapshot, inputSnapshot, budget, externalRefs, tags, value
        );
    }

    /**
     * 处理{@code immutableCopy}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private static <T> Map<String, T> immutableCopy(Map<String, T> source) {
        return source == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的会话转任务字段：" + field);
    }
}
