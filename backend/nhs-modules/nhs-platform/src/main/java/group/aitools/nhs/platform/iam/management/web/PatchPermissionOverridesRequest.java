package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Patch权限Overrides相关的不可变数据。
 * Bounded batch of per-user permission override mutations. */
public record PatchPermissionOverridesRequest(
    @Valid @Size(min = 1, max = 128) List<PermissionOverrideMutation> mutations
) {

    /**
     * 创建 {@code PatchPermissionOverridesRequest} 实例并初始化所需依赖。
     *
     * @param mutations {@code mutations}参数
     */
    public PatchPermissionOverridesRequest {
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限覆盖批次字段：" + field);
    }
}
