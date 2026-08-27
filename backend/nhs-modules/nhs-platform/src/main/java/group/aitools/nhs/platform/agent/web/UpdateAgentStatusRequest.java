package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update智能体Status相关的不可变数据。
 * Explicit Agent lifecycle transition request. */
public record UpdateAgentStatusRequest(
    @NotBlank @Pattern(regexp = "active|disabled|archived") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的 Agent 状态字段：" + field);
    }
}
