package group.aitools.nhs.platform.agent.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 封装智能体ReorderItem相关的不可变数据。
 * One Agent position in an atomic list reorder request. */
public record AgentReorderItemRequest(
    @NotNull @Positive Long id,
    @Min(-10_000) @Max(10_000) int sortOrder
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
