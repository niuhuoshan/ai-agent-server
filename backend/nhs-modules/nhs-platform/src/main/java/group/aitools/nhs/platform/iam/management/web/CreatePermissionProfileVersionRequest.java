package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Create权限配置档案版本相关的不可变数据。
 * Appends a draft version without mutating an existing published profile. */
public record CreatePermissionProfileVersionRequest(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 2000) String description,
    @Valid @Size(min = 1, max = 512) List<PermissionRuleInput> entries
) {

    /**
     * 创建 {@code CreatePermissionProfileVersionRequest} 实例并初始化所需依赖。
     *
     * @param name 名称
     * @param description {@code description}参数
     * @param entries {@code entries}参数
     */
    public CreatePermissionProfileVersionRequest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限包版本字段：" + field);
    }
}
