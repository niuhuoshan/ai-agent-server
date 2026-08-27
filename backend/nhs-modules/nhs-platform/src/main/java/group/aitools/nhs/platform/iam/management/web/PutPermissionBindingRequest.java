package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Put权限Binding相关的不可变数据。
 * Replaces a user's single active base permission binding. */
public record PutPermissionBindingRequest(
    @Pattern(regexp = "profile|snapshot") String bindingType,
    @Positive Long profileId,
    @Positive Integer profileVersion,
    @Valid @Size(max = 512) List<PermissionRuleInput> snapshotRules
) {

    /**
     * 创建 {@code PutPermissionBindingRequest} 实例并初始化所需依赖。
     *
     * @param bindingType 业务类型
     * @param profileId 资源标识
     * @param profileVersion 配置档案版本参数
     * @param snapshotRules 快照Rules参数
     */
    public PutPermissionBindingRequest {
        bindingType = bindingType == null ? "profile" : bindingType;
        snapshotRules = snapshotRules == null ? List.of() : List.copyOf(snapshotRules);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限绑定字段：" + field);
    }
}
