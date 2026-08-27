package group.aitools.nhs.platform.artifact.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 封装验收Decision相关的不可变数据。
 * One final acceptance decision for a frozen run and artifact set. */
public record AcceptanceDecisionRequest(
    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9._:-]+")
    String idempotencyKey,
    @NotEmpty @Size(max = 100) List<@Positive Long> artifactIds,
    @NotBlank @Pattern(regexp = "passed|rework|rejected|taken_over") String result,
    @Size(max = 4000) String comment,
    Map<String, Object> ruleResult
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的验收字段：" + field);
    }
}
