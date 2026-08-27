package group.aitools.nhs.platform.project.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Put项目Member相关的不可变数据。
 * Creates or changes one non-owner project membership. */
public record PutProjectMemberRequest(
    @NotBlank @Pattern(regexp = "manager|member|viewer") String role
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的项目成员字段：" + field);
    }
}
