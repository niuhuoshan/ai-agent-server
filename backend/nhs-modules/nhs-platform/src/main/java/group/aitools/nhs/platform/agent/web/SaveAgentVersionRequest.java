package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 封装Save智能体版本相关的不可变数据。
 * Complete draft version payload; bindings are replaced atomically. */
public record SaveAgentVersionRequest(
    @NotBlank @Size(max = 100_000) String systemPrompt,
    @Positive Long modelId,
    @Positive Long synthesisModelId,
    Map<String, Object> runtimeConfig,
    Map<String, Object> welcomeConfig,
    @Size(max = 32) List<@NotBlank @Size(max = 64) String> routingTags,
    @Size(max = 100) List<@Valid AgentResourceBindingRequest> tools,
    @Size(max = 100) List<@Valid AgentResourceBindingRequest> skills,
    @Size(max = 100) List<@Valid AgentResourceBindingRequest> knowledgeBases
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的 Agent 版本字段：" + field);
    }
}
