package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * 封装智能体资源Binding相关的不可变数据。
 * Resource usage requested by a draft Agent version. */
public record AgentResourceBindingRequest(
    @Positive Long resourceId,
    @NotBlank @Pattern(regexp = "use|invoke|read") String permission,
    Map<String, Object> config
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的 Agent 资源绑定字段：" + field);
    }
}
