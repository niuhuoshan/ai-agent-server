package group.aitools.nhs.platform.runtime.question.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装用户追问Answer相关的不可变数据。
 * User answer for an Agent-initiated question. */
public record UserQuestionAnswerRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @Size(max = 12) List<@Size(max = 128) String> selectedOptionIds,
    @Size(max = 4000) String customInput
) {
    /**
     * 创建 {@code UserQuestionAnswerRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param selectedOptionIds 资源标识集合
     * @param customInput {@code customInput}参数
     */
    public UserQuestionAnswerRequest {
        selectedOptionIds = selectedOptionIds == null ? List.of() : List.copyOf(selectedOptionIds);
    }
}
