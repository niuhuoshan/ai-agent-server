package group.aitools.nhs.platform.scenario.web;

import java.util.List;
import java.util.Map;

/**
 * 表示Scenario模板Views相关的领域对象。
 * JSON projections for scenario catalog, precheck and delivery operations. */
public final class ScenarioTemplateViews {
    private ScenarioTemplateViews() {
    }

    /**
     * 封装资源Requirement相关的不可变数据。
     */
    public record ResourceRequirement(String type, String name, boolean required, String description) {
    }

    /**
     * 封装资源Option相关的不可变数据。
     */
    public record ResourceOption(
        String id,
        String name,
        String label,
        String description,
        String status,
        Map<String, Object> meta
    ) {
    }

    /**
     * 封装{@code Summary}相关的不可变数据。
     */
    public record Summary(
        String id,
        String name,
        String category,
        String description,
        List<String> tags,
        boolean recommended,
        List<String> targetDepartments,
        String deliveryTime,
        String maturity,
        List<String> includedCapabilities,
        List<String> deliverables,
        List<String> businessGoals,
        List<String> installSteps,
        List<String> acceptanceCriteria,
        List<ResourceRequirement> requiredResources,
        List<String> sampleQuestions
    ) {
    }

    /**
     * 封装{@code Detail}相关的不可变数据。
     */
    public record Detail(Summary summary, Map<String, Object> manifest) {
    }

    /**
     * 封装资源Options相关的不可变数据。
     */
    public record ResourceOptions(String templateId, Map<String, List<ResourceOption>> options) {
    }

    /**
     * 封装{@code Check}相关的不可变数据。
     */
    public record Check(String key, String label, String status, String message) {
    }

    /**
     * 封装{@code Precheck}相关的不可变数据。
     */
    public record Precheck(
        String templateId,
        String targetAgentName,
        boolean canInstall,
        List<Check> checks
    ) {
    }

    /**
     * 封装{@code Instance}相关的不可变数据。
     */
    public record Instance(
        String id,
        String templateId,
        String templateName,
        String status,
        String owner,
        Map<String, Object> agent,
        Map<String, Object> latestRun,
        List<Map<String, Object>> resourceSummary,
        List<String> acceptanceCriteria,
        List<String> sampleQuestions,
        List<String> nextSteps
    ) {
    }

    /**
     * 封装{@code Install}相关的不可变数据。
     */
    public record Install(
        String templateId,
        boolean created,
        Map<String, Object> instance,
        Map<String, Object> run,
        Map<String, Object> agent,
        Map<String, Object> version,
        Map<String, Object> resourceBindings,
        List<ResourceRequirement> missingResources,
        List<String> nextSteps,
        List<String> enabledTools,
        List<String> sampleQuestions,
        List<Map<String, Object>> resourceSummary
    ) {
    }

    /**
     * 封装{@code Uninstall}相关的不可变数据。
     */
    public record Uninstall(
        String instanceId,
        String templateId,
        String status,
        String agentStatus,
        String previousStatus,
        boolean idempotent,
        String warning,
        String runId,
        String reason
    ) {
    }
}
