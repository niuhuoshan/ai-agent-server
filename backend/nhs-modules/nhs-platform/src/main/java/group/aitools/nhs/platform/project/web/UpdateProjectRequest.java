package group.aitools.nhs.platform.project.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 封装Update项目相关的不可变数据。
 * Mutable project metadata; task execution snapshots remain immutable. */
public record UpdateProjectRequest(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 12000) String description,
    @Positive Long defaultAgentVersionId,
    Map<String, Object> workspacePolicy,
    Map<String, Object> notificationPolicy,
    @Size(max = 32) List<@NotBlank @Size(max = 64) String> tags
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的项目字段：" + field);
    }
}
