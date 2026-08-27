package group.aitools.nhs.platform.knowledge.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Update知识库Base相关的不可变数据。
 */
public record UpdateKnowledgeBaseRequest(
    @NotNull @Positive Long expectedRevision,
    @NotBlank @Size(max = 255) String name,
    @Size(max = 12000) String description,
    @NotBlank @Pattern(regexp = "private|enterprise_shared|restricted") String visibility,
    @NotBlank @Pattern(regexp = "active|disabled") String status,
    @NotNull Map<String, Object> config
) {
}
