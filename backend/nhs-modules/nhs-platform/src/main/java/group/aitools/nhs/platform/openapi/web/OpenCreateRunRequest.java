package group.aitools.nhs.platform.openapi.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code OpenCreateRun}相关的不可变数据。
 */
public record OpenCreateRunRequest(
    @NotNull @Positive Long taskVersionId,
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Size(max = 131072) String input,
    Boolean startImmediately
) {
    /**
     * 创建 {@code OpenCreateRunRequest} 实例并初始化所需依赖。
     *
     * @param taskVersionId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @param startImmediately {@code startImmediately}参数
     */
    public OpenCreateRunRequest {
        startImmediately = startImmediately == null ? Boolean.TRUE : startImmediately;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的开放任务运行字段：" + field);
    }
}
