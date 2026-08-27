package group.aitools.nhs.platform.execution.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装Retry任务Run相关的不可变数据。
 * Creates one idempotent retry attempt from the latest terminal run. */
public record RetryTaskRunRequest(
    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9._:-]+")
    String idempotencyKey,
    Boolean autoStart
) {

    /**
     * 处理{@code startImmediately}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean startImmediately() {
        return autoStart == null || autoStart;
    }
}
