package group.aitools.nhs.platform.artifact.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Register制品相关的不可变数据。
 * Registers an already materialized immutable artifact object. */
public record RegisterArtifactRequest(
    @Pattern(regexp = "code_change|document|data_table|chart|test_report|log_summary|json|file")
    String artifactType,
    @NotBlank @Size(max = 255) String name,
    @Pattern(regexp = "local|oss|s3|external") String storageType,
    @NotBlank @Size(max = 1024) String storageRef,
    @Size(max = 128) String mimeType,
    @PositiveOrZero Long sizeBytes,
    @NotBlank @Pattern(regexp = "[A-Fa-f0-9]{64}") String contentHash,
    @Pattern(regexp = "public|internal|sensitive|secret") String sensitiveLevel,
    @Pattern(regexp = "inherit|private|enterprise_shared|restricted") String visibility,
    Long stepId,
    Map<String, Object> metadata
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的制品字段：" + field);
    }
}
