package group.aitools.nhs.platform.runtime.question.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装用户追问Cancel相关的不可变数据。
 * Idempotency key for cancelling an Agent-initiated question. */
public record UserQuestionCancelRequest(
    @NotBlank @Size(max = 128) String idempotencyKey
) {
}
