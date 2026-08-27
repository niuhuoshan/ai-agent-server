package group.aitools.nhs.platform.connector.web;

import java.util.List;

/**
 * 封装{@code McpWizardValidation}相关的不可变数据。
 */
public record McpWizardValidationView(
    int step,
    boolean valid,
    Integer nextStep,
    String namespace,
    List<String> diagnostics
) {
}
