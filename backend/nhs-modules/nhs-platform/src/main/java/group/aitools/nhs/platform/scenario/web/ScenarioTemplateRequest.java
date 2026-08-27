package group.aitools.nhs.platform.scenario.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装Scenario模板相关的不可变数据。
 * Resource bindings and idempotency contract for a scenario delivery. */
public record ScenarioTemplateRequest(
    @Size(max = 128) String instanceKey,
    @Size(max = 255) String displayName,
    @Size(max = 12000) String description,
    Map<String, Object> resourceBindings,
    Boolean publish,
    @Size(max = 128) String idempotencyKey
) {
    /**
     * 创建 {@code ScenarioTemplateRequest} 实例并初始化所需依赖。
     *
     * @param instanceKey {@code instanceKey}参数
     * @param displayName 名称
     * @param description {@code description}参数
     * @param resourceBindings 资源Bindings参数
     * @param publish {@code publish}参数
     * @param idempotencyKey {@code idempotencyKey}参数
     */
    public ScenarioTemplateRequest {
        resourceBindings = resourceBindings == null
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceBindings));
        publish = publish == null || publish;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的场景交付字段：" + field);
    }
}
