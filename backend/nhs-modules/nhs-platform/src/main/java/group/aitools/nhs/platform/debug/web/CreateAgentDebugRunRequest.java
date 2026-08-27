package group.aitools.nhs.platform.debug.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Create智能体DebugRun相关的不可变数据。
 * Starts a private debug task against one immutable Agent version. */
public record CreateAgentDebugRunRequest(
    @NotBlank @Size(max = 96) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
    @NotNull @Positive Long agentId,
    @NotNull @Positive Long agentVersionId,
    @NotBlank @Size(max = 100_000) String input
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的Agent调试字段：" + field);
    }
}
