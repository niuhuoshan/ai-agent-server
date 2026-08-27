package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code McpWizardPublish}相关的不可变数据。
 */
public record McpWizardPublishRequest(
    @NotBlank @Size(max = 128) String namespace,
    @Positive long expectedRevision
) {
}
