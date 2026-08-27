package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update权限配置档案Status相关的不可变数据。
 * Publishes or archives one immutable permission profile version. */
public record UpdatePermissionProfileStatusRequest(
    @NotNull @Pattern(regexp = "published|archived") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限包状态字段：" + field);
    }
}
