package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Create权限配置档案相关的不可变数据。
 * Creates version one of a reusable permission profile. */
public record CreatePermissionProfileRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String profileKey,
    @NotBlank @Size(max = 128) String name,
    @Size(max = 2000) String description,
    @Pattern(regexp = "system|custom") String profileType,
    @Valid @Size(min = 1, max = 512) List<PermissionRuleInput> entries
) {

    /**
     * 创建 {@code CreatePermissionProfileRequest} 实例并初始化所需依赖。
     *
     * @param profileKey 配置档案Key参数
     * @param name 名称
     * @param description {@code description}参数
     * @param profileType 业务类型
     * @param entries {@code entries}参数
     */
    public CreatePermissionProfileRequest {
        profileType = profileType == null ? "custom" : profileType;
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
        throw new IllegalArgumentException("不支持的权限包字段：" + field);
    }
}
