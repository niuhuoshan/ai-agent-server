package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 封装运行时ConfirmationDecision相关的不可变数据。
 * User decision for one server-owned business confirmation card. */
public record RuntimeConfirmationDecisionRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @Size(max = 32) List<Map<String, Object>> fields,
    @Size(max = 1000) String comment
) {
    /**
     * 创建 {@code RuntimeConfirmationDecisionRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param fields {@code fields}参数
     * @param comment {@code comment}参数
     */
    public RuntimeConfirmationDecisionRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
