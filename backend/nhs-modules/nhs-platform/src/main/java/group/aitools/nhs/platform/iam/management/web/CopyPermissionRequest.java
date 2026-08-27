package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Copy权限相关的不可变数据。
 * Idempotent request to copy a reusable baseline from a reference user. */
public record CopyPermissionRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
    @NotNull @Positive Long sourceUserId,
    @NotBlank @Pattern(regexp = "copy_base|append_missing|replace_base|save_template") String copyMode,
    @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String templateKey,
    @Size(max = 128) String templateName
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限复制字段：" + field);
    }
}
