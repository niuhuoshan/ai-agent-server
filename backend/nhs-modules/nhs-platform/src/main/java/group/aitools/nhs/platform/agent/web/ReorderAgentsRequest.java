package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装{@code ReorderAgents}相关的不可变数据。
 * Bounded batch used to persist an Agent list order in one transaction. */
public record ReorderAgentsRequest(
    @NotEmpty @Size(max = 200) List<@Valid AgentReorderItemRequest> items
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的 Agent 排序字段：" + field);
    }
}
