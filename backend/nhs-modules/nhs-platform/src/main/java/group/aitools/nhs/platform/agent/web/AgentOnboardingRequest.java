package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装智能体Onboarding相关的不可变数据。
 * Atomically creates an Agent identity and its first executable draft. */
public record AgentOnboardingRequest(
    @NotBlank @Size(min = 8, max = 64) String onboardingKey,
    @Valid CreateAgentRequest agent,
    @Valid SaveAgentVersionRequest version
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的 Agent 引导字段：" + field);
    }
}
