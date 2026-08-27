package group.aitools.nhs.platform.task.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * 封装任务资源相关的不可变数据。
 * One explicitly authorized resource frozen into a task version. */
public record TaskResourceRequest(
    @NotNull @Pattern(regexp = "agent_version|tool|skill|knowledge_base|data_source|dataset|artifact|connector")
    String resourceType,
    @NotNull @Positive Long resourceId,
    @NotNull @Pattern(regexp = "read|query|use|write|admin") String permission,
    Boolean required,
    @Pattern(regexp = "user|project|agent|template") String grantSource,
    Map<String, Object> grantSnapshot
) {

    /**
     * 创建 {@code TaskResourceRequest} 实例并初始化所需依赖。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param permission 权限参数
     * @param required {@code required}参数
     * @param grantSource grant数据源参数
     * @param grantSnapshot grant快照参数
     */
    public TaskResourceRequest {
        required = required == null || required;
        grantSource = grantSource == null ? "user" : grantSource;
        grantSnapshot = TaskInputDefaults.map(grantSnapshot);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的任务资源字段：" + field);
    }
}
