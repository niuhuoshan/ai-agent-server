package group.aitools.nhs.platform.task.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 封装Put任务AccessRule相关的不可变数据。
 * Upserts one explicit task allow or deny rule. */
public record PutTaskAccessRuleRequest(
    @NotBlank @Pattern(regexp = "user|platform_role|service_account") String subjectType,
    @Positive Long subjectId,
    @Size(max = 128) String subjectKey,
    @NotBlank @Pattern(regexp = "view|comment|operate|admin") String action,
    @NotBlank @Pattern(regexp = "allow|deny") String effect,
    @Future LocalDateTime expiresAt
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的任务ACL字段：" + field);
    }
}
